package com.cashcraft.ads.mediation

/**
 * One normalized impression-level revenue event.
 *
 * Common fields are provider-neutral so hosts can send revenue to their own backend without
 * branching on AdMob versus TopOn. Provider subclasses retain only fields needed by native ILRD
 * integrations.
 */
sealed interface AdRevenuePayload {
    /** Stable for a received event; uses the provider impression ID or local showToken/show session ID. */
    val eventId: String

    /** Wall-clock time at which the SDK delivered the revenue callback. */
    val occurredAtMillis: Long

    val platform: AdPlatform
    val mediationMode: AdMediationMode
    val format: AdFormat
    val sessionId: String
    val position: String

    /** AdMob ad unit ID or TopOn placement ID. */
    val placementId: String

    /** Revenue in millionths of [currencyCode]. */
    val valueMicros: Long
    val currencyCode: String

    /** Actual filling network when supplied by the provider. */
    val adNetwork: String?

    /** AdMob response ID or TopOn show ID when supplied by the provider. */
    val impressionId: String?
    val precisionType: String?
}

/** AdMob revenue plus the adapter identity needed by provider-specific ILRD integrations. */
data class AdMobRevenuePayload(
    override val eventId: String,
    override val occurredAtMillis: Long,
    override val mediationMode: AdMediationMode,
    override val format: AdFormat,
    override val sessionId: String,
    override val position: String,
    override val placementId: String,
    override val valueMicros: Long,
    override val currencyCode: String,
    override val adNetwork: String?,
    override val impressionId: String?,
    override val precisionType: String?,
    val mediationAdapterClassName: String?,
) : AdRevenuePayload {
    override val platform: AdPlatform = AdPlatform.ADMOB

    init {
        validateRevenuePayload()
    }
}

/**
 * TopOn revenue retaining the original ad-info object for native integrations such as Tenjin.
 * Hosts must not persist [adInfo]; persist only the normalized common fields.
 */
data class TopOnRevenuePayload(
    override val eventId: String,
    override val occurredAtMillis: Long,
    override val mediationMode: AdMediationMode,
    override val format: AdFormat,
    override val sessionId: String,
    override val position: String,
    override val placementId: String,
    override val valueMicros: Long,
    override val currencyCode: String,
    override val adNetwork: String?,
    override val impressionId: String?,
    override val precisionType: String?,
    val adInfo: Any,
) : AdRevenuePayload {
    override val platform: AdPlatform = AdPlatform.TOPON

    init {
        validateRevenuePayload()
    }
}

private fun AdRevenuePayload.validateRevenuePayload() {
    require(eventId.isNotBlank()) { "eventId must not be blank" }
    require(occurredAtMillis > 0L) { "occurredAtMillis must be positive" }
    require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    require(position.isNotBlank()) { "position must not be blank" }
    require(placementId.isNotBlank()) { "placementId must not be blank" }
    require(valueMicros >= 0L) { "valueMicros must not be negative" }
    require(currencyCode.isNotBlank()) { "currencyCode must not be blank" }
}

internal fun revenueEventId(
    platform: AdPlatform,
    impressionId: String?,
    sessionId: String,
): String = "${platform.analyticsValue}:${impressionId?.takeIf(String::isNotBlank) ?: sessionId}"

fun interface AdRevenueListener {
    fun onRevenuePaid(payload: AdRevenuePayload)

    companion object {
        val NONE = AdRevenueListener { }
    }
}

/**
 * TopOn revenue whose originating show cannot be safely attributed to a business session.
 * This is separate from [AdRevenuePayload] and deliberately has no sessionId or position.
 * Hosts must not persist [adInfo]; persist only normalized fields.
 */
data class UnattributedTopOnRevenuePayload(
    /**
     * Uses a stable provider ID or local showToken when available, including when showId is absent.
     * Without either, stability covers only this received callback, not repeated SDK callbacks.
     */
    val eventId: String,
    /** Wall-clock time at which the SDK delivered the revenue callback. */
    val occurredAtMillis: Long,
    val mediationMode: AdMediationMode,
    val format: AdFormat,
    val placementId: String,
    /** Revenue in millionths of [currencyCode]. */
    val valueMicros: Long,
    val currencyCode: String,
    val adNetwork: String?,
    val impressionId: String?,
    val precisionType: String?,
    val adInfo: Any,
) {
    val platform: AdPlatform = AdPlatform.TOPON

    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(occurredAtMillis > 0L) { "occurredAtMillis must be positive" }
        require(placementId.isNotBlank()) { "placementId must not be blank" }
        require(valueMicros >= 0L) { "valueMicros must not be negative" }
        require(currencyCode.isNotBlank()) { "currencyCode must not be blank" }
    }
}

/** Opt in to unattributed TopOn revenue while continuing to receive attributed revenue. */
interface AdRevenueListenerWithUnattributed : AdRevenueListener {
    fun onUnattributedRevenuePaid(payload: UnattributedTopOnRevenuePayload)
}
