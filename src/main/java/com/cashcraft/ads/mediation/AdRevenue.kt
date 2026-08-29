package com.cashcraft.ads.mediation

/** Provider-specific payload sent only to revenue integrations, never to generic analytics. */
sealed interface AdRevenuePayload {
    val platform: AdPlatform
    val valueMicros: Long?
    val currencyCode: String?
}

/** AdMob ILRD fields required by Tenjin's `eventAdImpressionAdMob` endpoint. */
data class AdMobRevenuePayload(
    override val valueMicros: Long,
    override val currencyCode: String?,
    val adUnitId: String,
    val responseId: String?,
    val mediationAdapterClassName: String?,
    val precisionType: String?,
) : AdRevenuePayload {
    override val platform: AdPlatform = AdPlatform.ADMOB
}

/**
 * TopOn ILRD payload retaining the original `TUAdInfo` required by
 * Tenjin's `eventAdImpressionTopOn` endpoint.
 */
data class TopOnRevenuePayload(
    val adInfo: Any,
    override val valueMicros: Long?,
    override val currencyCode: String?,
) : AdRevenuePayload {
    override val platform: AdPlatform = AdPlatform.TOPON
}

fun interface AdRevenueListener {
    fun onRevenuePaid(payload: AdRevenuePayload)

    companion object {
        val NONE = AdRevenueListener { }
    }
}
