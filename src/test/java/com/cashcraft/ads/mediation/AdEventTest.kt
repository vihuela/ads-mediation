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
    fun `provider revenue payloads share normalized fields and retain native details`() {
        val adMob = AdMobRevenuePayload(
            eventId = "admob:response",
            occurredAtMillis = 1_700_000_000_000L,
            mediationMode = AdMediationMode.BIDDING,
            format = AdFormat.REWARDED,
            sessionId = "session-1",
            position = "game_tool_refresh_rewarded",
            placementId = "admob-unit",
            valueMicros = 1_250L,
            currencyCode = "USD",
            adNetwork = "Google",
            impressionId = "response",
            mediationAdapterClassName = "adapter",
            precisionType = "PRECISE",
        )
        val topOnInfo = Any()
        val topOn = TopOnRevenuePayload(
            eventId = "topon:show",
            occurredAtMillis = 1_700_000_000_001L,
            mediationMode = AdMediationMode.BIDDING,
            format = AdFormat.INTERSTITIAL,
            sessionId = "session-2",
            position = "game_level_complete_interstitial",
            placementId = "topon-placement",
            valueMicros = 2_500L,
            currencyCode = "USD",
            adNetwork = "Facebook",
            impressionId = "show",
            precisionType = "publisher_defined",
            adInfo = topOnInfo,
        )

        assertEquals(AdPlatform.ADMOB, adMob.platform)
        assertEquals(AdFormat.REWARDED, adMob.format)
        assertEquals("Google", adMob.adNetwork)
        assertEquals("admob-unit", adMob.placementId)
        assertEquals(AdPlatform.TOPON, topOn.platform)
        assertEquals(AdFormat.INTERSTITIAL, topOn.format)
        assertEquals("Facebook", topOn.adNetwork)
        assertEquals("topon-placement", topOn.placementId)
        assertEquals(topOnInfo, topOn.adInfo)
    }

    @Test
    fun `revenue event id prefers provider impression id and falls back to session`() {
        assertEquals(
            "admob:response-1",
            revenueEventId(AdPlatform.ADMOB, "response-1", "session-1"),
        )
        assertEquals(
            "topon:session-2",
            revenueEventId(AdPlatform.TOPON, null, "session-2"),
        )
    }
}
