@file:Suppress("OVERRIDE_DEPRECATION")

package com.cashcraft.ads.mediation.internal

import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.TUAdConst

// Implement only the public abstract API reported by the Kotlin compiler; no SDK internals.
internal class TestTopOnAdInfo(
    private val id: String?,
    private val source: String,
    private val amount: Double = 0.25,
    private val token: String? = null,
    private val placement: String? = "unit",
) : TUAdInfo() {
    override fun getNetworkFirmId(): Int = 0
    override fun getAdsourceId(): String? = null
    override fun getAdsourceIndex(): Int = 0
    override fun getEcpm(): Double = 0.0
    override fun getEcpm(currency: TUAdConst.CURRENCY?): Double = 0.0
    override fun getEcpmWithCurrency(currency: TUAdConst.CURRENCY?): Double = 0.0
    override fun getEncEcpmInfo(): String? = null
    override fun getRmbEncEcpmInfo(): String? = null
    override fun getSecretId(): Int = 0
    override fun isHeaderBiddingAdsource(): Int = 0
    override fun getShowId(): String? = id
    override fun getPublisherRevenue(): Double? = amount
    override fun getPublisherRevenue(currency: TUAdConst.CURRENCY?): Double? = amount
    override fun getCurrency(): String? = null
    override fun getCountry(): String? = null
    override fun getTopOnPlacementId(): String? = placement
    override fun getTopOnAdFormat(): String? = null
    override fun getPlacementId(): String? = null
    override fun getFormat(): String? = null
    override fun getEcpmPrecision(): String? = "publisher_defined"
    override fun getAdNetworkType(): String? = null
    override fun getNetworkPlacementId(): String? = null
    override fun getEcpmLevel(): Int = 0
    override fun getSegmentId(): Int = 0
    override fun getScenarioId(): String? = null
    override fun getScenarioRewardName(): String? = "coin"
    override fun getScenarioRewardNumber(): Int = 1
    override fun getSubChannel(): String? = null
    override fun getChannel(): String? = null
    override fun getCustomRule(): String? = null
    override fun getExtInfoMap(): MutableMap<String, Any>? = null
    override fun getRewardUserCustomData(): String? = null
    override fun getLocalExtra(): MutableMap<String, Any>? = null
    override fun getTpBidId(): String? = null
    override fun getABTestId(): Int = 0
    override fun getDismissType(): Int = 0
    override fun getPlacementType(): Int = 0
    override fun getSharedPlacementId(): String? = null
    override fun getBidFloor(): Double = 0.0
    override fun getWaterfallId(): String? = null
    override fun getAdxHandler(): Nothing? = null
    override fun getAdSourceAdType(): Int = 0
    override fun getAdSourceCustomExt(): String? = null
    override fun getNetworkName(): String? = source
    override fun getShowCustomExt(): String? = token
    override fun getUrlTagParams(): MutableMap<String, String>? = null
    override fun getRequestId(): String? = null
    override fun getBidType(): Int = 0
    override fun getCustomContentResult(): Nothing? = null
}
