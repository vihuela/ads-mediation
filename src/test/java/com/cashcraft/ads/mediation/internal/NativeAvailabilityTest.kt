package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdsState
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.internal.nativeads.nativeAvailability
import org.junit.Assert.*
import org.junit.Test

class NativeAvailabilityTest {
    @Test fun `configuration consent and the selected provider all gate native requests`() {
        assertEquals("native_platform_not_configured", nativeAvailability(false, false, true, AdsState.READY).failure)
        assertFalse(nativeAvailability(true, true, false, AdsState.READY).ready)
        assertEquals("consent_not_obtained", nativeAvailability(true, false, false, AdsState.READY).failure)
        assertEquals("native_platform_initialization_failed", nativeAvailability(true, false, true, AdsState.FAILED).failure)
        assertFalse(nativeAvailability(true, false, true, AdsState.INITIALIZING).ready)
        assertTrue(nativeAvailability(true, false, true, AdsState.READY).ready)
    }

    @Test fun `invalid native fields and nonfinite template dimensions cannot reach the SDK`() {
        assertEquals("invalid_ad_unit_id", ResolvedNativeRequest(AdPlatform.ADMOB, " ", "home").failureReason())
        assertEquals("invalid_position", ResolvedNativeRequest(AdPlatform.TOPON, "id", "").failureReason())
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals("invalid_template_ratio", ResolvedNativeRequest(AdPlatform.TOPON, "id", "home", it).failureReason())
        }
        assertNull(ResolvedNativeRequest(AdPlatform.TOPON, "id", "home", 1.5f).failureReason())
        assertEquals(ResolvedNativeRequest(AdPlatform.ADMOB, "id", "home"), ResolvedNativeRequest(AdPlatform.ADMOB, "id", "home"))
    }
}
