package com.cashcraft.ads.mediation

import org.junit.Assert.assertEquals
import org.junit.Test

class AdEventTest {
    @Test
    fun `paid event exposes Tenjin AdMob revenue fields`() {
        val parameters = AdEvent(
            name = AdEventName.PAID,
            platform = AdPlatform.ADMOB,
            format = AdFormat.REWARDED,
            position = "game_tool_refresh_rewarded",
            sessionId = "session-1",
            adUnitId = "test-ad-unit",
            number = 1L,
            adSource = "Google",
            responseId = "response-1",
            value = 0.00125,
            valueMicros = 1_250L,
            currency = "USD",
            mediationAdapterClassName = "GoogleAdapter",
            precisionType = "PRECISE",
        ).analyticsParameters()

        assertEquals(1_250L, parameters["value_micros"])
        assertEquals("USD", parameters["currency"])
        assertEquals("test-ad-unit", parameters["ad_unit_id"])
        assertEquals("response-1", parameters["response_id"])
        assertEquals("GoogleAdapter", parameters["mediation_adapter_class_name"])
        assertEquals("PRECISE", parameters["precision_type"])
        assertEquals("admob", parameters["ad_platform"])
    }

    @Test
    fun `TopOn keeps event fields and changes only platform identity`() {
        val parameters = AdEvent(
            name = AdEventName.IMPRESSION,
            platform = AdPlatform.TOPON,
            format = AdFormat.INTERSTITIAL,
            position = "game_level_complete_interstitial",
            sessionId = "session-2",
            adUnitId = "topon-placement",
            number = 2L,
        ).analyticsParameters()

        assertEquals("topon", parameters["ad_platform"])
        assertEquals("interstitial", parameters["ad_type"])
        assertEquals("game_level_complete_interstitial", parameters["position"])
        assertEquals("topon-placement", parameters["ad_unit_id"])
    }

    @Test
    fun `provider revenue payloads retain separate platform identities`() {
        val adMob = AdMobRevenuePayload(
            valueMicros = 1_250L,
            currencyCode = "USD",
            adUnitId = "admob-unit",
            responseId = "response",
            mediationAdapterClassName = "adapter",
            precisionType = "PRECISE",
        )
        val topOnInfo = Any()
        val topOn = TopOnRevenuePayload(
            adInfo = topOnInfo,
            valueMicros = 2_500L,
            currencyCode = "USD",
        )

        assertEquals(AdPlatform.ADMOB, adMob.platform)
        assertEquals(AdPlatform.TOPON, topOn.platform)
        assertEquals(topOnInfo, topOn.adInfo)
    }
}
