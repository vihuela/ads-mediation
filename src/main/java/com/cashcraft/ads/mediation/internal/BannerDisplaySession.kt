package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdMobRevenuePayload
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.TopOnRevenuePayload
import com.cashcraft.ads.mediation.revenueEventId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One already-identified display. Contains immutable event metadata and global outlets only,
 * never a platform ad, Activity, View, or page callback. Adapters must retain the correct session
 * at callback ingress; creating a session does not prove an impression or platform refresh.
 */
internal class BannerDisplaySession(
    private val event: AdEvent,
    private val listener: AdEventListener,
    private val revenueListener: AdRevenueListener,
    private val slotEnded: AtomicBoolean,
    private val logger: AdsModuleLogger? = null,
) {
    val sessionId: String get() = event.sessionId
    val responseId: String get() = checkNotNull(event.responseId)
    val requestId: String? get() = event.requestId
    private val impressionOrFailure = AtomicBoolean(false)
    private val paid = AtomicBoolean(false)
    private val refreshed = AtomicBoolean(false)

    fun impression() {
        if (!slotEnded.get() && impressionOrFailure.compareAndSet(false, true)) dispatch(event)
    }

    fun click() {
        if (!slotEnded.get()) dispatch(event.copy(name = AdEventName.CLICK))
    }

    fun refreshSucceeded() {
        if (!slotEnded.get() && refreshed.compareAndSet(false, true)) {
            dispatch(event.copy(name = AdEventName.BANNER_REFRESH, result = "filled"))
        }
    }

    /** Only an actual failed display attempt, not load failure or lifecycle cleanup. */
    fun showFailure(reason: String, errorCode: String? = null) {
        if (!slotEnded.get() && impressionOrFailure.compareAndSet(false, true)) {
            dispatch(event.copy(name = AdEventName.SHOW_FAIL, reason = reason, errorCode = errorCode))
        }
    }

    /** Only a provider-confirmed Banner body close, never a landing page dismissal. */
    fun close() {
        if (slotEnded.compareAndSet(false, true)) dispatch(event.copy(name = AdEventName.DISMISS))
    }

    /**
     * Money has already been converted to micros using the provider's actual revenue field.
     * A known late payment remains deliverable after slot end; invalid money never consumes dedup.
     * TopOn's SDK object is delivered synchronously and is not retained by this session.
     */
    fun paid(
        valueMicros: Long?,
        currencyCode: String?,
        precisionType: String?,
        occurredAtMillis: Long,
        topOnAdInfo: Any? = null,
    ) {
        val currency = currencyCode?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
        if (valueMicros == null || valueMicros < 0L || currency == null || occurredAtMillis <= 0L ||
            (event.platform == AdPlatform.TOPON && topOnAdInfo == null)
        ) {
            logger?.bannerDiagnostic(
                checkNotNull(event.slotId), "invalid_revenue", event.responseId,
                valueMicros?.toString(), currencyCode,
            )
            return
        }
        // Scope dedup IDs to this display: two slots must not collide even if an SDK reuses an ID.
        val eventId = revenueEventId(event.platform, null, sessionId)
        val payload = when (event.platform) {
            AdPlatform.ADMOB -> AdMobRevenuePayload(
                eventId, occurredAtMillis, event.mediationMode, event.format, sessionId,
                event.position, event.adUnitId, valueMicros, currency, event.adSource,
                responseId, precisionType, event.mediationAdapterClassName,
            )
            AdPlatform.TOPON -> TopOnRevenuePayload(
                eventId, occurredAtMillis, event.mediationMode, event.format, sessionId,
                event.position, event.adUnitId, valueMicros, currency, event.adSource,
                responseId, precisionType, checkNotNull(topOnAdInfo),
            )
        }
        if (!paid.compareAndSet(false, true)) return
        dispatch(event.copy(
            name = AdEventName.PAID,
            value = valueMicros / 1_000_000.0,
            valueMicros = valueMicros,
            currency = currency,
            precisionType = precisionType,
        ))
        runCatching { revenueListener.onRevenuePaid(payload) }
            .onFailure { logger?.bannerRevenueDispatchFailed(event, it) }
    }

    private fun dispatch(event: AdEvent) {
        logger?.event(event)
        runCatching { listener.onEvent(event) }
            .onFailure { logger?.eventDispatchFailed(event, it) }
    }
}
