package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.topon.TopOnAds

internal data class BidSelection(
    val winner: AdPlatform,
    /** The actual cached price. Null means the provider had an ad but exposed no usable price. */
    val priceUsd: Double?,
    val admobPriceUsd: Double?,
    val toponPriceUsd: Double?,
)

internal data class BidDecision(
    val selection: BidSelection?,
    val admobAvailable: Boolean,
    val topOnAvailable: Boolean,
    val admobPriceUsd: Double?,
    val topOnPriceUsd: Double?,
)

internal object BidCandidateSelector {
    fun select(
        admobAvailable: Boolean,
        admobPriceUsd: Double?,
        toponAvailable: Boolean,
        toponPriceUsd: Double?,
    ): BidSelection? {
        val admobPrice = admobPriceUsd.validPriceOrNull()
        val toponPrice = toponPriceUsd.validPriceOrNull()
        val winner = when {
            admobAvailable && toponAvailable -> when {
                admobPrice == null && toponPrice != null -> AdPlatform.TOPON
                admobPrice != null && toponPrice == null -> AdPlatform.ADMOB
                admobPrice != null && toponPrice != null && toponPrice > admobPrice ->
                    AdPlatform.TOPON
                else -> AdPlatform.ADMOB
            }
            admobAvailable -> AdPlatform.ADMOB
            toponAvailable -> AdPlatform.TOPON
            else -> return null
        }
        return BidSelection(
            winner = winner,
            priceUsd = if (winner == AdPlatform.ADMOB) admobPrice else toponPrice,
            admobPriceUsd = admobPrice,
            toponPriceUsd = toponPrice,
        )
    }

    private fun Double?.validPriceOrNull(): Double? =
        this?.takeIf { it.isFinite() && it >= 0.0 }
}

/** Immediately compares the same-format ads already cached by both providers. */
internal object AdBiddingCoordinator {
    fun select(format: AdFormat, onSelected: (BidDecision) -> Unit) {
        // Match each standalone provider's show semantics: this opportunity never waits for a
        // network load. TopOn still starts a background fill for the next opportunity.
        TopOnAds.ensureLoaded(format)
        val admobAvailable = AdMobAds.isReady(format)
        val topOnAvailable = TopOnAds.isReady(format)
        val selection = BidCandidateSelector.select(
            admobAvailable = admobAvailable,
            admobPriceUsd = AdMobAds.bidPrice(format),
            toponAvailable = topOnAvailable,
            toponPriceUsd = TopOnAds.bidPrice(format),
        )
        onSelected(
            BidDecision(
                selection = selection,
                admobAvailable = admobAvailable,
                topOnAvailable = topOnAvailable,
                admobPriceUsd = selection?.admobPriceUsd,
                topOnPriceUsd = selection?.toponPriceUsd,
            ),
        )
    }
}
