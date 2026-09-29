package com.cashcraft.ads.mediation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BannerRequestTest {
    @Test
    fun `request identity uses values including platform position and size`() {
        val request = BannerRequest(AdPlatform.ADMOB, "banner-id", "home_bottom", BannerSize.Standard320x50)
        val equal = BannerRequest(AdPlatform.ADMOB, "banner-id", "home_bottom", BannerSize.Standard320x50)

        assertEquals(request, equal)
        assertEquals(request.hashCode(), equal.hashCode())
        assertEquals(AdPlatform.ADMOB, request.platform)
        assertEquals("banner-id", request.adUnitId)
        assertEquals("home_bottom", request.position)
        assertEquals(BannerSize.Standard320x50, request.size)
        assertNotEquals(request, request.copy(platform = AdPlatform.TOPON))
        assertNotEquals(request, request.copy(adUnitId = "another-id"))
        assertNotEquals(request, request.copy(position = "detail_bottom"))
        assertNotEquals(request, request.copy(size = BannerSize.AnchoredAdaptive))
        assertNotEquals(request.copy(size = BannerSize.AnchoredAdaptive),
            request.copy(size = BannerSize.StandardAnchoredAdaptive))
    }

    @Test
    fun `blank ad unit and position are rejected`() {
        listOf("", " \t\n").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) {
                BannerRequest(AdPlatform.ADMOB, blank, "home", BannerSize.Standard320x50)
            }
            assertThrows(IllegalArgumentException::class.java) {
                BannerRequest(AdPlatform.TOPON, "banner-id", blank, BannerSize.Standard320x50)
            }
        }
    }

    @Test
    fun `fixed size fails before loading when content is narrower than 320 dp`() {
        val fixed = BannerRequest(AdPlatform.ADMOB, "banner-id", "home", BannerSize.Standard320x50)
        assertNotNull(fixed.sizeError(319))
        assertNull(fixed.sizeError(320))
        assertNull(fixed.sizeError(400))
    }

    @Test
    fun `adaptive width is structural and platform capability remains a separate gate`() {
        for (size in listOf(BannerSize.AnchoredAdaptive, BannerSize.StandardAnchoredAdaptive)) {
            val adaptive = BannerRequest(AdPlatform.TOPON, "banner-id", "home", size)
            assertNull(adaptive.sizeError(1))
            assertThrows(IllegalArgumentException::class.java) { adaptive.sizeError(0) }
            assertThrows(IllegalArgumentException::class.java) { adaptive.sizeError(-1) }
        }
    }

    @Test
    fun `formal TopOn requests fail explicitly for all sizes without a fallback`() {
        for (size in listOf(BannerSize.Standard320x50, BannerSize.AnchoredAdaptive, BannerSize.StandardAnchoredAdaptive)) {
            val request = BannerRequest(AdPlatform.TOPON, "placement", "home", size)
            assertEquals("topon_banner_not_supported", request.supportError())
            assertEquals(AdPlatform.TOPON, request.platform)
            assertNull(request.copy(platform = AdPlatform.ADMOB).supportError())
        }
    }
}
