package com.cashcraft.ads.mediation.internal.nativeads

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdMobRevenuePayload
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.TopOnRevenuePayload
import com.cashcraft.ads.mediation.internal.BidDecision
import com.cashcraft.ads.mediation.internal.AdLoadClock
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.normalizedAdPosition
import com.cashcraft.ads.mediation.revenueEventId
import java.util.UUID

/** Contains only analytics identity and application listeners, never page/SDK objects. */
internal class NativeSlot(
    val request: ResolvedNativeRequest,
    private val number: Long,
    private val nextLoadNumber: () -> Long,
    private val listener: AdEventListener,
    private val revenueListener: AdRevenueListener,
    val id: String = request.position,
) {
    private val position = request.position.normalizedAdPosition()
    private val primary = request.candidates().first()
    private val platform = requireNotNull(primary.platform)
    private val mode = if (request.isBidding) AdMediationMode.BIDDING
        else if (platform == AdPlatform.ADMOB) AdMediationMode.ADMOB else AdMediationMode.TOPON
    private var attempted = false
    private var ended = false
    private var announced = false

    fun position() {
        if (announced) return
        announced = true
        emit(AdEventName.POSITION)
    }

    fun end(reason: String) {
        if (ended) return
        ended = true
        if (!attempted) emit(AdEventName.SHOW_FAIL, reason)
    }

    fun attempt(clock: () -> Long, recordLoadEvents: Boolean = true): NativeAttempt {
        attempted = true
        val requestId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        return NativeAttempt(
            base = AdEvent(
                name = AdEventName.IMPRESSION, platform = platform, format = AdFormat.NATIVE,
                position = position, sessionId = sessionId, adUnitId = primary.adUnitId, number = number,
                requestId = requestId, mediationMode = mode, slotId = id,
            ),
            load = AdLoadSession(
                listener, platform, mode, AdFormat.NATIVE, position, primary.adUnitId,
                requestId, requestId, nextLoadNumber(), 1, clock(), AdLoadClock(clock), slotId = id,
            ),
            listener = listener,
            revenueListener = revenueListener,
            recordLoadEvents = recordLoadEvents,
            candidates = if (!request.isBidding) emptyMap() else request.candidates().associate { candidate ->
                val candidateId = UUID.randomUUID().toString()
                val candidatePlatform = requireNotNull(candidate.platform)
                candidatePlatform to NativeCandidateLoad(candidate, candidateId, AdLoadSession(
                    listener, candidatePlatform, mode, AdFormat.NATIVE, position, candidate.adUnitId,
                    candidateId, candidateId, nextLoadNumber(), 1, clock(), AdLoadClock(clock), slotId = id,
                ))
            },
        )
    }

    private fun emit(name: AdEventName, reason: String? = null) {
        runCatching {
            listener.onEvent(AdEvent(name, platform, AdFormat.NATIVE, position, id,
                primary.adUnitId, number, reason = reason, mediationMode = mode, slotId = id))
        }
    }
}

internal class NativeCandidateLoad(
    val request: ResolvedNativeRequest,
    val requestId: String,
    val events: AdLoadSession,
    var started: Boolean = false,
)

internal class NativeAttempt(
    private var base: AdEvent,
    val load: AdLoadSession,
    private val listener: AdEventListener,
    private val revenueListener: AdRevenueListener,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val candidates: Map<AdPlatform, NativeCandidateLoad> = emptyMap(),
    private val recordLoadEvents: Boolean = true,
) {
    val id: String get() = base.sessionId
    private var shown = false
    private var failed = false
    private var closed = false
    private var started = false
    private val paidEvents = mutableSetOf<String>()

    private var bidRecorded = false

    fun start() { started = true; if (recordLoadEvents && candidates.isEmpty()) load.request() }
    fun loaded(ad: NativeAdHandle) { if (recordLoadEvents && candidates.isEmpty()) load.loaded(ad.adSource, ad.responseId) }
    fun loadStarted(platform: AdPlatform) {
        val candidate = candidates[platform] ?: return
        if (candidate.started) return
        candidate.started = true
        if (recordLoadEvents) candidate.events.request()
    }
    fun candidateLoaded(platform: AdPlatform, ad: NativeAdHandle) {
        candidates[platform]?.takeIf { recordLoadEvents && it.started }?.events?.loaded(ad.adSource, ad.responseId)
    }
    fun candidateFailed(platform: AdPlatform, reason: String, errorCode: String?) {
        candidates[platform]?.takeIf { recordLoadEvents && it.started }?.events?.failed(
            if (reason == "no_fill") "no_fill" else "failed", errorCode, reason, null,
        )
    }
    fun bidResult(decision: BidDecision) {
        if (bidRecorded || candidates.isEmpty()) return
        bidRecorded = true
        val selected = decision.selection
        val winner = candidates[selected?.winner]
        if (selected != null && winner != null) base = base.copy(
            platform = selected.winner, adUnitId = winner.request.adUnitId, requestId = winner.requestId,
        )
        emit(base.copy(
            name = AdEventName.BID_RESULT, result = if (selected == null) "unavailable" else "selected",
            winnerPlatform = selected?.winner, currency = "USD",
            admobAvailable = decision.admobAvailable, topOnAvailable = decision.topOnAvailable,
            admobPriceAvailable = decision.admobPriceUsd != null, topOnPriceAvailable = decision.topOnPriceUsd != null,
            admobValue = decision.admobPriceUsd, topOnValue = decision.topOnPriceUsd,
            winningValue = selected?.priceUsd,
            admobAdUnitId = candidates[AdPlatform.ADMOB]?.request?.adUnitId,
            topOnAdUnitId = candidates[AdPlatform.TOPON]?.request?.adUnitId,
        ))
    }
    fun fail(reason: String, errorCode: String? = null) {
        if (started) finishLoads(if (reason == "no_fill") "no_fill" else "failed", errorCode, reason)
        showFailure(reason, errorCode)
    }
    fun cancel(reason: String) {
        if (started) finishLoads("cancelled", null, reason)
        showFailure(reason)
    }
    private fun finishLoads(result: String, code: String?, reason: String) {
        if (!recordLoadEvents) return
        if (candidates.isEmpty()) load.failed(result, code, reason, null)
        else candidates.values.filter { it.started }.forEach { it.events.failed(result, code, reason, null) }
    }
    private fun showFailure(reason: String, errorCode: String? = null) {
        if (shown || failed) return
        failed = true
        emit(base.copy(name = AdEventName.SHOW_FAIL, reason = reason, errorCode = errorCode))
    }
    fun impression(source: String?, response: String?) {
        if (shown || failed) return
        shown = true
        emit(base.copy(name = AdEventName.IMPRESSION, adSource = source, responseId = response))
    }
    fun click(source: String?, response: String?) {
        if (!failed && !closed) emit(base.copy(name = AdEventName.CLICK, adSource = source, responseId = response))
    }
    fun close() {
        if (closed) return
        closed = true
        emit(base.copy(name = AdEventName.DISMISS))
    }
    fun paid(revenue: NativeRevenue) {
        // Never require an impression or a live page for an already identified paid callback.
        if (revenue.valueMicros < 0L || revenue.currencyCode.isBlank()) {
            diagnostic("invalid_native_revenue")
            return
        }
        val eventId = revenueEventId(base.platform, revenue.responseId, base.sessionId)
        if (eventId in paidEvents) return
        val payload = runCatching {
            when (base.platform) {
                AdPlatform.ADMOB -> AdMobRevenuePayload(eventId, wallClock(), base.mediationMode,
                    AdFormat.NATIVE, base.sessionId, base.position, base.adUnitId, revenue.valueMicros,
                    revenue.currencyCode, revenue.adSource, revenue.responseId, revenue.precisionType,
                    revenue.mediationAdapterClassName)
                AdPlatform.TOPON -> TopOnRevenuePayload(eventId, wallClock(), base.mediationMode,
                    AdFormat.NATIVE, base.sessionId, base.position, base.adUnitId, revenue.valueMicros,
                    revenue.currencyCode, revenue.adSource, revenue.responseId, revenue.precisionType,
                    requireNotNull(revenue.topOnAdInfo))
            }
        }.getOrElse { diagnostic("invalid_native_revenue"); return }
        paidEvents += eventId
        emit(base.copy(name = AdEventName.PAID, adSource = revenue.adSource, responseId = revenue.responseId,
            value = revenue.valueMicros / 1_000_000.0, valueMicros = revenue.valueMicros,
            currency = revenue.currencyCode, precisionType = revenue.precisionType,
            mediationAdapterClassName = revenue.mediationAdapterClassName))
        runCatching { revenueListener.onRevenuePaid(payload) }.onFailure { diagnostic("native_revenue_callback_failed") }
    }
    private fun emit(event: AdEvent) { runCatching { listener.onEvent(event) } }
    private fun diagnostic(reason: String) {
        runCatching {
            com.cashcraft.ads.mediation.Ads.nativeLog(base.slotId ?: base.position, debug = true) {
                "收益交付异常 | 原因标识=$reason | 展示记录=${base.sessionId}"
            }
        }
    }
}
