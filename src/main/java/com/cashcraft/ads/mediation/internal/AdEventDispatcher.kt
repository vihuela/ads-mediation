package com.cashcraft.ads.mediation.internal

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.internal.nativeads.NativeSlot
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

    private fun nextNumber(format: AdFormat, kind: String): Long = synchronized(preferences) {
        val key = "${format.analyticsValue}_${kind}_count"
        val next = preferences.getLong(key, 0L) + 1L
        preferences.edit { putLong(key, next) }
        next
    }

    fun nativeSlot(request: ResolvedNativeRequest, revenueListener: AdRevenueListener): NativeSlot = NativeSlot(
        request = request,
        number = nextNumber(AdFormat.NATIVE, "position"),
        nextLoadNumber = { nextNumber(AdFormat.NATIVE, "load") },
        listener = AdEventListener { event ->
            logger.event(event)
            runCatching { listener.onEvent(event) }.onFailure { logger.eventDispatchFailed(event, it) }
        },
        revenueListener = revenueListener,
    )

    fun begin(
        format: AdFormat,
        position: String,
        adUnitId: String,
        attempt: FullScreenShowAttempt? = null,
        onCreated: (AdShowSession) -> Unit = {},
    ): AdShowSession {
        require(format != AdFormat.BANNER) { "Banner requires beginBannerSlot" }
        val typedPosition = position.withAdType(format)
        val number = nextNumber(format, "position")
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
            attempt = attempt ?: FullScreenShowAttempt(),
            onCreated = onCreated,
        )
    }

    fun beginLoad(format: AdFormat, adUnitId: String, bufferSize: Int): AdLoadSession {
        require(format != AdFormat.BANNER) { "Banner requires createBannerLoad" }
        val number = nextNumber(format, "load")
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

    fun beginBannerSlot(
        position: String,
        adUnitId: String,
        revenueListener: AdRevenueListener,
        onCreated: (BannerSlot) -> Unit = {},
    ): BannerSlot = BannerSlot(
        listener = listener,
        platform = platform,
        mediationMode = mediationMode,
        position = position.withAdType(AdFormat.BANNER),
        adUnitId = adUnitId,
        slotId = UUID.randomUUID().toString(),
        number = nextNumber(AdFormat.BANNER, "position"),
        logger = logger,
        revenueListener = revenueListener,
        onCreated = onCreated,
    )

    /** The caller emits request() only after it has actually invoked the SDK load method. */
    fun createBannerLoad(slot: BannerSlot): AdLoadSession {
        check(!slot.isEnded) { "Banner slot has ended" }
        require(slot.platform == platform) { "Banner slot belongs to another platform" }
        val requestId = UUID.randomUUID().toString()
        return AdLoadSession(
            listener = listener,
            platform = platform,
            mediationMode = mediationMode,
            format = AdFormat.BANNER,
            position = slot.position,
            adUnitId = slot.adUnitId,
            sessionId = requestId,
            requestId = requestId,
            number = nextNumber(AdFormat.BANNER, "load"),
            bufferSize = null,
            startedAtMillis = SystemClock.elapsedRealtime(),
            logger = logger,
            slotId = slot.slotId,
        )
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
    val requestId: String,
    private val number: Long,
    private val bufferSize: Int?,
    private val startedAtMillis: Long,
    private val clock: AdLoadClock = AdLoadClock(SystemClock::elapsedRealtime),
    private val logger: AdsModuleLogger? = null,
    private val slotId: String? = null,
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
            slotId = slotId,
        )
        logger?.event(event)
        runCatching { listener.onEvent(event) }
            .onFailure { error -> logger?.eventDispatchFailed(event, error) }
    }
}

/** One business slot can contain several independently identified Banner displays. */
internal class BannerSlot(
    private val listener: AdEventListener,
    val platform: AdPlatform,
    private val mediationMode: AdMediationMode,
    val position: String,
    val adUnitId: String,
    val slotId: String,
    private val number: Long,
    private val logger: AdsModuleLogger? = null,
    private val revenueListener: AdRevenueListener = AdRevenueListener.NONE,
    onCreated: (BannerSlot) -> Unit = {},
) {
    private val ended = AtomicBoolean(false)

    val isEnded: Boolean get() = ended.get()

    init {
        // Publish ownership before the external POSITION listener can reenter the host.
        onCreated(this)
        emit(AdEventName.POSITION, sessionId = slotId)
    }

    fun end() { ended.set(true) }

    /**
     * Called only when the adapter has confirmed a NEW stable response. The adapter retains the
     * returned session for duplicate/late callbacks; this is not an identity lookup or a fallback.
     */
    fun newDisplay(
        responseId: String?,
        requestId: String? = null,
        adSource: String? = null,
        mediationAdapterClassName: String? = null,
    ): BannerDisplaySession? {
        if (isEnded) return null
        val identity = responseId?.trim()?.takeIf(String::isNotEmpty)
        if (identity == null) {
            logger?.bannerDiagnostic(slotId, "missing_display_identity", responseId)
            return null
        }
        return BannerDisplaySession(
            event = AdEvent(
                name = AdEventName.IMPRESSION,
                platform = platform,
                mediationMode = mediationMode,
                format = AdFormat.BANNER,
                position = position,
                sessionId = UUID.randomUUID().toString(),
                adUnitId = adUnitId,
                number = number,
                slotId = slotId,
                requestId = requestId,
                responseId = identity,
                adSource = adSource,
                mediationAdapterClassName = mediationAdapterClassName,
            ),
            listener = listener,
            revenueListener = revenueListener,
            slotEnded = ended,
            logger = logger,
        )
    }

    /** Refresh failure need not identify a new display and must leave the old one usable. */
    fun refreshFailed(errorCode: String?, reason: String?) {
        if (isEnded) return
        emit(AdEventName.BANNER_REFRESH, slotId, result = "failed", errorCode = errorCode, reason = reason)
    }

    private fun emit(
        name: AdEventName,
        sessionId: String,
        result: String? = null,
        reason: String? = null,
        errorCode: String? = null,
    ) {
        val event = AdEvent(
            name = name,
            platform = platform,
            mediationMode = mediationMode,
            format = AdFormat.BANNER,
            position = position,
            sessionId = sessionId,
            adUnitId = adUnitId,
            number = number,
            slotId = slotId,
            result = result,
            reason = reason,
            errorCode = errorCode,
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
    val attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
    onCreated: (AdShowSession) -> Unit = {},
) {
    private val terminal = AtomicBoolean(false)

    init {
        onCreated(this)
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
)
