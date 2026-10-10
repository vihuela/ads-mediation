package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdBidCandidate
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BidCandidateSelectorTest {
    private val appOpenFormats = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL)
    private val rewardFormats = listOf(AdFormat.REWARDED, AdFormat.INTERSTITIAL)

    private fun candidate(
        platform: AdPlatform = AdPlatform.ADMOB,
        format: AdFormat = AdFormat.APP_OPEN,
        price: Double? = null,
        available: Boolean = true,
    ) = AdBidCandidate(platform, format, "${platform.name}_${format.name}", available, price)

    @Test
    fun `highest price wins across all four platform and format combinations`() {
        val candidates = listOf(
            candidate(price = 0.001),
            candidate(AdPlatform.TOPON, price = 0.002),
            candidate(format = AdFormat.INTERSTITIAL, price = 0.004),
            candidate(AdPlatform.TOPON, AdFormat.INTERSTITIAL, price = 0.003),
        )
        val winner = BidCandidateSelector.select(candidates, appOpenFormats)
        assertEquals(AdPlatform.ADMOB, winner?.platform)
        assertEquals(AdFormat.INTERSTITIAL, winner?.format)
        assertEquals(0.004, winner?.priceUsd ?: -1.0, 0.0)
    }

    @Test
    fun `same format bidding still chooses TopOn at a higher price`() {
        val winner = BidCandidateSelector.select(
            listOf(candidate(price = 0.001), candidate(AdPlatform.TOPON, price = 0.002)),
            listOf(AdFormat.APP_OPEN),
        )
        assertEquals(AdPlatform.TOPON, winner?.platform)
    }

    @Test
    fun `known zero beats unknown price even on the secondary format`() {
        val winner = BidCandidateSelector.select(
            listOf(candidate(), candidate(AdPlatform.TOPON, AdFormat.INTERSTITIAL, price = 0.0)),
            appOpenFormats,
        )
        assertEquals(AdPlatform.TOPON, winner?.platform)
        assertEquals(0.0, winner?.priceUsd ?: -1.0, 0.0)
    }

    @Test
    fun `ties prefer primary format before provider regardless of input order`() {
        val candidates = listOf(
            candidate(AdPlatform.TOPON, AdFormat.REWARDED, price = 0.01),
            candidate(format = AdFormat.INTERSTITIAL, price = 0.01),
        )
        for (order in listOf(candidates, candidates.reversed())) {
            val winner = BidCandidateSelector.select(order, rewardFormats)
            assertEquals(AdPlatform.TOPON, winner?.platform)
            assertEquals(AdFormat.REWARDED, winner?.format)
        }
    }

    @Test
    fun `AdMob wins same format ties including genuine zero`() {
        for (price in listOf(null, 0.0, 0.01)) {
            val winner = BidCandidateSelector.select(
                listOf(candidate(AdPlatform.TOPON, price = price), candidate(price = price)),
                appOpenFormats,
            )
            assertEquals(AdPlatform.ADMOB, winner?.platform)
        }
    }

    @Test
    fun `unknown prices prefer original format`() {
        val winner = BidCandidateSelector.select(
            listOf(candidate(format = AdFormat.INTERSTITIAL), candidate(AdPlatform.TOPON)),
            appOpenFormats,
        )
        assertEquals(AdFormat.APP_OPEN, winner?.format)
        assertNull(winner?.priceUsd)
    }

    @Test
    fun `unavailable expensive candidates cannot win`() {
        val winner = BidCandidateSelector.select(
            listOf(candidate(price = 100.0, available = false), candidate(AdPlatform.TOPON)),
            appOpenFormats,
        )
        assertEquals(AdPlatform.TOPON, winner?.platform)
        assertNull(winner?.priceUsd)
    }

    @Test
    fun `only available interstitial can satisfy reward or app open request`() {
        val candidates = listOf(
            candidate(available = false),
            candidate(format = AdFormat.REWARDED, available = false),
            candidate(format = AdFormat.INTERSTITIAL),
        )
        for (formats in listOf(appOpenFormats, rewardFormats)) {
            assertEquals(AdFormat.INTERSTITIAL, BidCandidateSelector.select(candidates, formats)?.format)
        }
    }

    @Test
    fun `invalid prices become unknown instead of zero`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val candidates = listOf(candidate(price = invalid), candidate(AdPlatform.TOPON, price = 0.0))
            assertEquals(AdPlatform.TOPON, BidCandidateSelector.select(candidates, appOpenFormats)?.platform)
            assertNull(BidCandidateSelector.select(listOf(candidate(price = invalid)), appOpenFormats)?.priceUsd)
        }
    }

    @Test
    fun `unrequested formats cannot participate`() {
        val winner = BidCandidateSelector.select(
            listOf(candidate(price = 0.01), candidate(format = AdFormat.REWARDED, price = 100.0)),
            appOpenFormats,
        )
        assertEquals(AdFormat.APP_OPEN, winner?.format)
    }

    @Test
    fun `no available candidate produces no winner`() {
        assertNull(BidCandidateSelector.select(emptyList(), appOpenFormats))
        assertNull(BidCandidateSelector.select(listOf(candidate(available = false)), appOpenFormats))
    }
}
