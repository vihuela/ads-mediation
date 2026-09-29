package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BidCandidateSelectorTest {
    @Test
    fun `Banner is rejected before touching SDK caches or starting a load`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdBiddingCoordinator.select(AdFormat.BANNER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdBiddingCoordinator.selectAvailable(AdFormat.BANNER)
        }
    }

    @Test
    fun `higher available price wins`() {
        val selection = BidCandidateSelector.select(
            admobAvailable = true,
            admobPriceUsd = 0.001,
            toponAvailable = true,
            toponPriceUsd = 0.002,
        )

        assertEquals(AdPlatform.TOPON, selection?.winner)
        assertEquals(0.002, selection?.priceUsd ?: -1.0, 0.0)
    }

    @Test
    fun `known zero price beats an unavailable price`() {
        val selection = BidCandidateSelector.select(
            admobAvailable = true,
            admobPriceUsd = null,
            toponAvailable = true,
            toponPriceUsd = 0.0,
        )

        assertEquals(AdPlatform.TOPON, selection?.winner)
        assertNull(selection?.admobPriceUsd)
        assertEquals(0.0, selection?.toponPriceUsd ?: -1.0, 0.0)
    }

    @Test
    fun `AdMob wins when both available prices are genuine zeroes`() {
        val selection = BidCandidateSelector.select(
            admobAvailable = true,
            admobPriceUsd = 0.0,
            toponAvailable = true,
            toponPriceUsd = 0.0,
        )

        assertEquals(AdPlatform.ADMOB, selection?.winner)
    }

    @Test
    fun `only available provider wins when its price is unavailable`() {
        val selection = BidCandidateSelector.select(
            admobAvailable = false,
            admobPriceUsd = 1.0,
            toponAvailable = true,
            toponPriceUsd = null,
        )

        assertEquals(AdPlatform.TOPON, selection?.winner)
        assertNull(selection?.priceUsd)
        assertNull(selection?.toponPriceUsd)
    }

    @Test
    fun `unknown price stays distinct from a genuine zero price`() {
        val selection = BidCandidateSelector.select(
            admobAvailable = true,
            admobPriceUsd = 0.0,
            toponAvailable = true,
            toponPriceUsd = Double.NaN,
        )

        assertEquals(AdPlatform.ADMOB, selection?.winner)
        assertEquals(0.0, selection?.admobPriceUsd ?: -1.0, 0.0)
        assertNull(selection?.toponPriceUsd)
        assertTrue(selection?.priceUsd == 0.0)
    }

    @Test
    fun `no available provider produces no winner`() {
        assertNull(
            BidCandidateSelector.select(
                admobAvailable = false,
                admobPriceUsd = 1.0,
                toponAvailable = false,
                toponPriceUsd = 2.0,
            ),
        )
    }
}
