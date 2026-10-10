package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdBidCandidate
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdProviderConfig
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.BiddingProviderConfig
import com.cashcraft.ads.mediation.TopOnProviderConfig
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.topon.TopOnAds

internal data class BidDecision(
    val formats: List<AdFormat>,
    val candidates: List<AdBidCandidate>,
    val selection: AdBidCandidate?,
)

internal object BidCandidateSelector {
    /** Price, requested format order, then AdMob. Input order never decides a tie. */
    fun select(candidates: List<AdBidCandidate>, formats: List<AdFormat>): AdBidCandidate? =
        candidates.asSequence()
            .filter { it.available && it.format in formats }
            .map { it.copy(priceUsd = it.priceUsd.validPriceOrNull()) }
            .maxWithOrNull(
                compareBy<AdBidCandidate> { it.priceUsd != null }
                    .thenBy { it.priceUsd ?: 0.0 }
                    .thenBy { -formats.indexOf(it.format) }
                    .thenBy { it.platform == AdPlatform.ADMOB },
            )
}

internal fun Double?.validPriceOrNull(): Double? =
    this?.takeIf { it.isFinite() && it >= 0.0 }

/** Reads all eligible cached candidates without polling or waiting for a new network load. */
internal object AdBiddingCoordinator {
    fun select(provider: AdProviderConfig, formats: List<AdFormat>): BidDecision {
        val providers = when (provider) {
            is AdMobProviderConfig -> listOf(provider)
            is TopOnProviderConfig -> listOf(provider)
            is BiddingProviderConfig -> listOf(provider.admob, provider.topon)
        }
        val candidates = providers.flatMap { source ->
            formats.map { format ->
                val available: Boolean
                val price: Double?
                when (source.platform) {
                    AdPlatform.ADMOB -> {
                        available = AdMobAds.isReady(format)
                        price = if (available) AdMobAds.bidPrice(format) else null
                    }
                    AdPlatform.TOPON -> {
                        TopOnAds.ensureLoaded(format)
                        available = TopOnAds.isReady(format)
                        price = if (available) TopOnAds.bidPrice(format) else null
                    }
                }
                AdBidCandidate(source.platform, format, source.adUnitId(format), available, price.validPriceOrNull())
            }
        }
        return BidDecision(formats, candidates, BidCandidateSelector.select(candidates, formats))
    }
}
