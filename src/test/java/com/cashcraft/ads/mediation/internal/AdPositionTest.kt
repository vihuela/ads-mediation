package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class AdPositionTest {
    @Test
    fun `ad type is appended to the business scene`() {
        assertEquals(
            "app_foreground_app_open",
            "app_foreground".withAdType(AdFormat.APP_OPEN),
        )
        assertEquals(
            "game_level_complete_interstitial",
            "game_level_complete".withAdType(AdFormat.INTERSTITIAL),
        )
        assertEquals(
            "game_tool_refresh_rewarded",
            "game_tool_refresh".withAdType(AdFormat.REWARDED),
        )
    }

    @Test
    fun `existing type suffix is not duplicated`() {
        assertEquals(
            "game_tool_refresh_rewarded",
            "game_tool_refresh_rewarded".withAdType(AdFormat.REWARDED),
        )
    }

    @Test
    fun `blank scene keeps a typed fallback`() {
        assertEquals("unknown_interstitial", "  ".withAdType(AdFormat.INTERSTITIAL))
    }
}
