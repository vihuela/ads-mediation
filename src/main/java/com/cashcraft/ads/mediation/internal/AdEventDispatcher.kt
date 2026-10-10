package com.cashcraft.ads.mediation.internal

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.cashcraft.ads.mediation.AdBidCandidate
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdShowResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class AdEventDispatcher(
    context: Context,
    private val platform: AdPlatform,
    private val mediationMode: AdMediationMode,
    private val listener: AdEventListener,
    loggingEnabled: Boolean,
    logTag: String,
) {
    private val preferences = context.getSharedPreferences(
        if (platform == AdPlatform.ADMOB) ADMOB_PREFERENCES_NAME else TOPON_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val logger = AdsModuleLogger(loggingEnabled, logTag)

    fun begin(format: AdFormat, position: String, adUnitId: String): AdShowSession {
        val typedPosition = position.withAdType(format)
        val number = synchronized(preferences) {
            val key = "${format.analyticsValue}_position_count"
            val next = preferences.getLong(key, 0L) + 1L
            preferences.edit { putLong(key, next) }
            next
        }
        return AdShowSession(
            listener = listener,
            platform = platform,
            mediationMode = mediationMode,
            format = format,
            position = typedPosition,
            adUnitId = adUnitId,
            sessionId = UUID.randomUUID().toString(),
            number = number,
            logger = logger,
        )
    }

    fun beginLoad(format: AdFormat, adUnitId: String, bufferSize: Int): AdLoadSession {
        val number = synchronized(preferences) {
            val key = "${format.analyticsValue}_load_count"
            val next = preferences.getLong(key, 0L) + 1L
            preferences.edit { putLong(key, next) }
            next
        }
        val requestId = UUID.randomUUID().toString()
        return AdLoadSession(
            listener = listener,
            platform = platform,
            mediationMode = mediationMode,
            format = format,
            position = "preload_${format.analyticsValue}",
            adUnitId = adUnitId,
            sessionId = requestId,
            requestId = requestId,
            number = number,
            bufferSize = bufferSize,
            startedAtMillis = SystemClock.elapsedRealtime(),
            logger = logger,
        ).also(AdLoadSession::request)
    }

    private companion object {
        const val ADMOB_PREFERENCES_NAME = "lcb_admob_events"
        const val TOPON_PREFERENCES_NAME = "lcb_topon_events"
    }
}

/** Analytics positions always use the stable `<business_scene>_<ad_type>` convention. */
internal fun String.withAdType(format: AdFormat): String {
    val scene = trim().ifEmpty { "unknown" }
    val suffix = "_${format.analyticsValue}"
    return if (scene.endsWith(suffix, ignoreCase = true)) scene else scene + suffix
}

internal fun interface AdLoadClock {
    fun nowMillis(): Long
}

internal class AdLoadSession(
    private val listener: AdEventListener,
    private val platform: AdPlatform,
    private val mediationMode: AdMediationMode,
    private val format: AdFormat,
    private val position: String,
    private val adUnitId: String,
    private val sessionId: String,
    private val requestId: String,
    private val number: Long,
    private val bufferSize: Int,
    private val startedAtMillis: Long,
    private val clock: AdLoadClock = AdLoadClock(SystemClock::elapsedRealtime),
    private val logger: AdsModuleLogger? = null,
) {
    private val terminal = AtomicBoolean(false)

    fun request() = emit(AdEventName.LOAD_REQUEST)

    fun loaded(adSource: String?, responseId: String?) {
        finish(
            result = "filled",
            adSource = adSource,
            responseId = responseId,
        )
    }

    fun failed(result: String, errorCode: String?, reason: String?, responseId: String?) {
        finish(
            result = result,
            errorCode = errorCode,
            reason = reason,
            responseId = responseId,
        )
    }

    private fun finish(
        result: String,
        errorCode: String? = null,
        reason: String? = null,
        adSource: String? = null,
        responseId: String? = null,
    ) {
        if (!terminal.compareAndSet(false, true)) return
        emit(
            name = AdEventName.LOAD_RESULT,
            result = result,
            errorCode = errorCode,
            reason = reason,
            adSource = adSource,
            responseId = responseId,
            latencyMillis = (clock.nowMillis() - startedAtMillis).coerceAtLeast(0L),
        )
    }

    private fun emit(
        name: AdEventName,
        result: String? = null,
        errorCode: String? = null,
        reason: String? = null,
        adSource: String? = null,
        responseId: String? = null,
        latencyMillis: Long? = null,
    ) {
        val event = AdEvent(
            name = name,
            platform = platform,
            mediationMode = mediationMode,
            format = format,
            position = position,
            sessionId = sessionId,
            adUnitId = adUnitId,
            number = number,
            requestId = requestId,
            result = result,
            errorCode = errorCode,
            reason = reason,
            adSource = adSource,
            responseId = responseId,
            latencyMillis = latencyMillis,
            bufferSize = bufferSize,
        )
        logger?.event(event)
        runCatching { listener.onEvent(event) }
            .onFailure { error -> logger?.eventDispatchFailed(event, error) }
    }
}

internal class AdShowSession(
    private val listener: AdEventListener,
    private val platform: AdPlatform,
    val mediationMode: AdMediationMode,
    val format: AdFormat,
    val position: String,
    val adUnitId: String,
    val sessionId: String,
    private val number: Long,
    private val logger: AdsModuleLogger? = null,
) {
    private val terminal = AtomicBoolean(false)

    init {
        emit(AdEventName.POSITION)
    }

    val hasTerminalEvent: Boolean
        get() = terminal.get()

    fun impression(adSource: String?, responseId: String?) {
        if (terminal.compareAndSet(false, true)) {
            emit(AdEventName.IMPRESSION, adSource = adSource, responseId = responseId)
        }
    }

    fun showFailure(reason: String, errorCode: String? = null, cause: Throwable? = null) {
        if (terminal.compareAndSet(false, true)) {
            emit(AdEventName.SHOW_FAIL, reason = reason, errorCode = errorCode)
            cause?.let { error ->
                logger?.showFailureException(
                    format = format,
                    position = position,
                    sessionId = sessionId,
                    reason = reason,
                    errorCode = errorCode,
                    error = error,
                )
            }
        }
    }

    fun bidResult(data: AdBidEventData) {
        dispatch(
            AdEvent(
                name = AdEventName.BID_RESULT,
                platform = platform,
                mediationMode = mediationMode,
                format = format,
                position = position,
                sessionId = sessionId,
                adUnitId = adUnitId,
                number = number,
                result = if (data.winnerPlatform == null) "no_candidate" else "won",
                currency = "USD",
                winnerPlatform = data.winnerPlatform,
                requestedFormat = data.requestedFormat,
                eligibleFormats = data.eligibleFormats,
                winnerFormat = data.winnerFormat,
                bidCandidates = data.candidates,
                admobAvailable = data.admobAvailable,
                topOnAvailable = data.topOnAvailable,
                admobPriceAvailable = data.admobValue != null,
                topOnPriceAvailable = data.topOnValue != null,
                admobValue = data.admobValue,
                topOnValue = data.topOnValue,
                winningValue = data.winningValue,
                admobAdUnitId = data.admobAdUnitId,
                topOnAdUnitId = data.topOnAdUnitId,
            ),
        )
    }

    fun emit(
        name: AdEventName,
        reason: String? = null,
        errorCode: String? = null,
        adSource: String? = null,
        responseId: String? = null,
        value: Double? = null,
        valueMicros: Long? = null,
        currency: String? = null,
        mediationAdapterClassName: String? = null,
        precisionType: String? = null,
    ) {
        val event = AdEvent(
            name = name,
            platform = platform,
            mediationMode = mediationMode,
            format = format,
            position = position,
            sessionId = sessionId,
            adUnitId = adUnitId,
            number = number,
            reason = reason,
            errorCode = errorCode,
            adSource = adSource,
            responseId = responseId,
            value = value,
            valueMicros = valueMicros,
            currency = currency,
            mediationAdapterClassName = mediationAdapterClassName,
            precisionType = precisionType,
        )
        dispatch(event)
    }

    private fun dispatch(event: AdEvent) {
        logger?.event(event)
        runCatching { listener.onEvent(event) }
            .onFailure { error -> logger?.eventDispatchFailed(event, error) }
    }
}

/** A close callback is successful only after the SDK has reported an impression. */
internal fun AdShowSession.dismissedResult(): AdShowResult = if (hasTerminalEvent) {
    AdShowResult.Dismissed
} else {
    showFailure("dismissed_before_impression")
    AdShowResult.Failed("dismissed_before_impression")
}

internal data class AdBidEventData(
    val winnerPlatform: AdPlatform?,
    val admobAvailable: Boolean,
    val topOnAvailable: Boolean,
    val admobValue: Double?,
    val topOnValue: Double?,
    val winningValue: Double?,
    val admobAdUnitId: String,
    val topOnAdUnitId: String,
    val requestedFormat: AdFormat? = null,
    val eligibleFormats: List<AdFormat> = emptyList(),
    val winnerFormat: AdFormat? = null,
    val candidates: List<AdBidCandidate> = emptyList(),
)
