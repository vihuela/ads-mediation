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
import com.cashcraft.ads.mediation.Ads
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
        platformKnown: Boolean = true,
        platformDisabled: (() -> Boolean)? = null,
    ): AdShowSession {
        require(format != AdFormat.BANNER) { "Banner requires beginBannerSlot" }
        val number = nextNumber(format, "position")
        return AdShowSession(
            listener = listener,
            platform = platform,
            mediationMode = mediationMode,
            format = format,
            position = if (format == AdFormat.NATIVE) position.normalizedAdPosition() else position,
            adUnitId = adUnitId,
            sessionId = UUID.randomUUID().toString(),
            number = number,
            logger = logger,
            attempt = attempt ?: FullScreenShowAttempt(),
            onCreated = onCreated,
            platformKnown = platformKnown,
            platformDisabled = platformDisabled,
        )
    }

    fun beginLoad(format: AdFormat, adUnitId: String, bufferSize: Int): AdLoadSession {
        return createLoad(format, adUnitId, bufferSize).also(AdLoadSession::request)
    }

    /** Allocate ownership before invoking the SDK; publish only for an actual load call. */
    fun createLoad(format: AdFormat, adUnitId: String, bufferSize: Int): AdLoadSession {
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
            deferUntilRequest = true,
        )
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
        position = position.normalizedAdPosition(),
        adUnitId = adUnitId,
        slotId = UUID.randomUUID().toString(),
        number = nextNumber(AdFormat.BANNER, "position"),
        logger = logger,
        revenueListener = revenueListener,
        onCreated = onCreated,
    )

    /** A one-shot preload has no display slot; invokeLoad publishes its actual SDK request. */
    fun createBannerPreloadLoad(adUnitId: String): AdLoadSession {
        val requestId = UUID.randomUUID().toString()
        return AdLoadSession(
            listener = listener,
            platform = platform,
            mediationMode = mediationMode,
            format = AdFormat.BANNER,
            position = "preload_banner",
            adUnitId = adUnitId,
            sessionId = requestId,
            requestId = requestId,
            number = nextNumber(AdFormat.BANNER, "load"),
            bufferSize = null,
            startedAtMillis = SystemClock.elapsedRealtime(),
            logger = logger,
            deferUntilRequest = true,
        )
    }

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

/** Keep the business ID intact; ad_type already identifies the format. */
internal fun String.normalizedAdPosition(): String = trim().ifEmpty { "unknown" }

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
    private val deferUntilRequest: Boolean = false,
) {
    private val terminal = AtomicBoolean(false)

    private var requested = false
    private var pendingTerminal: (() -> Unit)? = null

    @Synchronized
    fun request() {
        if (requested) return
        requested = true
        emit(AdEventName.LOAD)
        val pending = pendingTerminal
        pendingTerminal = null
        pending?.invoke()
    }

    /** Even a synchronous callback must follow LOAD; thrown calls are still real attempts. */
    fun invokeLoad(load: () -> Unit) {
        try {
            load()
        } finally {
            request()
        }
    }

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
            reason = if (format in setOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
                when {
                    result in setOf("no_fill", "timeout", "cancelled") -> result
                    result == "error" && errorCode == "exception" -> "exception"
                    else -> "ad_error"
                }
            } else reason,
            responseId = responseId,
        )
    }

    @Synchronized
    private fun finish(
        result: String,
        errorCode: String? = null,
        reason: String? = null,
        adSource: String? = null,
        responseId: String? = null,
    ) {
        if (!terminal.compareAndSet(false, true)) return
        val latency = (clock.nowMillis() - startedAtMillis).coerceAtLeast(0L)
        val publish = {
            emit(
                name = if (result == "filled") AdEventName.LOADED else AdEventName.LOAD_FAIL,
                result = result,
                errorCode = errorCode,
                reason = reason,
                adSource = adSource,
                responseId = responseId,
                latencyMillis = latency,
            )
        }
        if (requested || !deferUntilRequest) publish() else pendingTerminal = publish
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
    private var initialTerminal = AtomicBoolean(false)
    private var initialSessionId = UUID.randomUUID().toString()
    private var initialDisplayBound = false
    private var currentDisplay: BannerDisplaySession? = null
    private var refreshIndex = 0L

    val isEnded: Boolean get() = ended.get()

    init {
        // Publish ownership before the external POSITION listener can reenter the host.
        onCreated(this)
        emit(AdEventName.POSITION, sessionId = initialSessionId)
    }

    fun end(reason: String = "scene_inactive") {
        // Commit before dispatch: host callbacks cannot revive or end this opportunity twice.
        if (ended.compareAndSet(false, true)) finishPending(reason)
    }

    private fun finishPending(reason: String, errorCode: String? = null) {
        val display = currentDisplay
        if (display != null) display.endOpportunity(reason, errorCode)
        else if (initialTerminal.compareAndSet(false, true)) {
            emit(AdEventName.SHOW_FAIL, initialSessionId, reason = reason, errorCode = errorCode)
        }
    }

    /** 后续宿主加载更新广告身份，但仍属于同一页面周期。 */
    fun prepareForLoad() {
        if (isEnded || (!initialDisplayBound && !initialTerminal.get())) return
        finishPending("cancelled")
        if (isEnded) return
        currentDisplay = null
        initialTerminal = AtomicBoolean(false)
        initialSessionId = UUID.randomUUID().toString()
        initialDisplayBound = false
    }

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
        val initial = !initialDisplayBound && !initialTerminal.get()
        if (!initial) {
            finishPending("cancelled")
            if (isEnded) return null
        }
        initialDisplayBound = true
        return BannerDisplaySession(
            event = AdEvent(
                name = AdEventName.IMPRESSION,
                platform = platform,
                mediationMode = mediationMode,
                format = AdFormat.BANNER,
                position = position,
                sessionId = if (initial) initialSessionId else UUID.randomUUID().toString(),
                adUnitId = adUnitId,
                number = number,
                slotId = slotId,
                refreshIndex = ++refreshIndex,
                requestId = requestId,
                responseId = identity,
                adSource = adSource,
                mediationAdapterClassName = mediationAdapterClassName,
            ),
            listener = listener,
            revenueListener = revenueListener,
            slotEnded = ended,
            logger = logger,
            impressionOrFailure = if (initial) initialTerminal else AtomicBoolean(false),
        ).also {
            currentDisplay = it
        }
    }

    /** Failure belongs to the qualified host opportunity, even before an SDK response exists. */
    fun showFailure(reason: String, errorCode: String? = null) {
        if (!isEnded) finishPending(reason, errorCode)
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
    private val platformKnown: Boolean = true,
    private val platformDisabled: (() -> Boolean)? = null,
) {
    private val terminal = AtomicBoolean(false)
    var hasImpression: Boolean = false
        private set
    private var admitted = false
    private var skipped = false
    private var pendingBid: AdBidEventData? = null
    private val paid = AtomicBoolean(false)
    private val impressionReported = AtomicBoolean(false)
    private val rewarded = AtomicBoolean(false)
    private val dismissed = AtomicBoolean(false)
    var onImpressionConfirmed: (() -> Unit)? = null

    init {
        attempt.eventSession = this
        attempt.policy?.logMaterial(format, platform)
        onCreated(this)
        // The migration is limited to the three SDK full-screen formats.
        if (format == AdFormat.NATIVE) {
            admitted = true
            emit(AdEventName.POSITION)
        }
    }

    /** Called near show, or at a no-fill terminal. Never consumes show quota. */
    fun admit(): Boolean {
        if (admitted) return true
        if (skipped || position.isBlank()) return false
        val block = attempt.policy?.telemetryBlockReason()
        val reason = block?.sceneSkipReason()
            ?: if (block == null && (platformDisabled?.invoke() ?: (attempt.policy != null &&
                    Ads.fullScreenPlatformsDisabled(attempt.telemetryFormats ?: listOf(format)))))
                "platform_disabled" else null
        if (reason != null) {
            skipped = true
            terminal.set(true)
            pendingBid = null
            dispatch(AdEvent(AdEventName.SCENE_SKIP, platform, format, position, "", "", 0,
                reason = reason, mediationMode = mediationMode, platformKnown = false))
            return false
        }
        // Invalid scene identity must not become a fabricated qualified opportunity.
        if (block != null) return false
        admitted = true
        emit(AdEventName.POSITION)
        pendingBid?.let { pendingBid = null; bidResult(it) }
        return true
    }

    val hasTerminalEvent: Boolean
        get() = terminal.get()

    fun impression(
        adSource: String?, responseId: String?, valueMicros: Long? = null,
        currency: String? = null, precisionType: String? = null,
    ) {
        // Actual callbacks still count if teardown/failure reached the main queue first.
        attempt.policy?.impression()
        if (!admit()) return
        if (terminal.compareAndSet(false, true)) {
            hasImpression = true
            val callback = onImpressionConfirmed
            onImpressionConfirmed = null
            runCatching { callback?.invoke() }
            if (reportsImpressionOnShow) {
                emit(AdEventName.IMPRESSION, adSource = adSource, responseId = responseId,
                    value = valueMicros?.div(1_000_000.0), valueMicros = valueMicros,
                    currency = currency, precisionType = precisionType)
            }
        }
    }

    private val reportsImpressionOnShow: Boolean
        get() = platform == AdPlatform.TOPON && format in setOf(AdFormat.APP_OPEN, AdFormat.NATIVE)

    /** Revenue delivery has its own lifetime and dedup, independent of the SDK show callback. */
    fun revenue(
        adSource: String? = null, responseId: String? = null, value: Double? = null,
        valueMicros: Long?, currency: String?, mediationAdapterClassName: String? = null,
        precisionType: String? = null,
    ): Boolean {
        if (valueMicros == null || valueMicros < 0 || currency?.matches(Regex("[A-Z]{3}")) != true) return false
        if (!admit() || !paid.compareAndSet(false, true)) return false
        if (!reportsImpressionOnShow) {
            emit(AdEventName.IMPRESSION, adSource = adSource, responseId = responseId,
                value = value ?: valueMicros / 1_000_000.0, valueMicros = valueMicros,
                currency = currency, mediationAdapterClassName = mediationAdapterClassName,
                precisionType = precisionType)
        }
        return true
    }

    fun showFailure(reason: String, errorCode: String? = null, cause: Throwable? = null) {
        if (!admit()) return
        if (terminal.compareAndSet(false, true)) {
            onImpressionConfirmed = null
            emit(AdEventName.SHOW_FAIL, reason = attempt.policy?.blocked?.let { it.sceneSkipReason() ?: it.code } ?: reason, errorCode = errorCode)
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
        if (!admitted) { if (!skipped) pendingBid = data; return }
        dispatch(
            AdEvent(
                name = AdEventName.BID_RESULT,
                platformKnown = platformKnown,
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
    ): Boolean {
        if (name != AdEventName.POSITION && !admit()) return false
        when (name) {
            AdEventName.IMPRESSION -> if (!impressionReported.compareAndSet(false, true)) return false
            AdEventName.REWARD -> {
                if (format != AdFormat.REWARDED || (hasTerminalEvent && !hasImpression)) return false
                if (!rewarded.compareAndSet(false, true)) return false
            }
            AdEventName.DISMISS -> {
                if (!hasImpression) { showFailure("dismissed_before_impression"); return false }
                if (!dismissed.compareAndSet(false, true)) return false
            }
            else -> Unit
        }
        if (name == AdEventName.CLICK) attempt.policy?.click()
        val event = AdEvent(
            name = name,
            platformKnown = platformKnown,
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
        return true
    }

    private fun dispatch(event: AdEvent) {
        logger?.event(event)
        runCatching { listener.onEvent(event) }
            .onFailure { error -> logger?.eventDispatchFailed(event, error) }
    }
}

/** A close callback is successful only after the SDK has reported an impression. */
internal fun AdShowSession.dismissedResult(): AdShowResult = if (hasImpression) {
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
