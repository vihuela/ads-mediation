package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeFormatBoundaryTest {
    @Test
    fun `bidding rejects native before loading or reading provider caches`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AdBiddingCoordinator.select(AdFormat.NATIVE)
        }

        assertEquals("unsupported_ad_format", error.message)
    }

    @Test
    fun `available bidding lookup also rejects native`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AdBiddingCoordinator.selectAvailable(AdFormat.NATIVE)
        }

        assertEquals("unsupported_ad_format", error.message)
    }
}
