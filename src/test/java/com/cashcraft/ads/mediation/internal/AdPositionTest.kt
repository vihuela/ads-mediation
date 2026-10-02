package com.cashcraft.ads.mediation.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class AdPositionTest {
    @Test
    fun `business IDs stay intact without generated type suffixes`() {
        listOf("BA_Home_bottom", "SP_AppStart", "IV_BloodSugarTrack_back",
            "RV_BloodSugar_Note", "NA_Home_exit_dialog", "custom_banner").forEach { position ->
            assertEquals(position, position.normalizedAdPosition())
        }
    }

    @Test
    fun `whitespace is trimmed and blank positions use an untyped fallback`() {
        assertEquals("SP_AppStart", "  SP_AppStart  ".normalizedAdPosition())
        assertEquals("unknown", "  ".normalizedAdPosition())
    }
}
