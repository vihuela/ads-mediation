package com.cashcraft.ads.mediation

import com.cashcraft.ads.mediation.internal.formatAdEventLogMessage
import com.cashcraft.ads.mediation.internal.formatNativeDebugLogMessage
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdEventTest {
    @Test
    fun `loading omits position from analytics and every log format while display keeps it`() {
        for (format in AdFormat.entries) {
            for (name in AdEventName.entries) {
                val event = AdEvent(name, AdPlatform.TOPON, format, "business-position",
                    "load-session", "unit", 1L, requestId = "load-request")
                val loading = name == AdEventName.LOAD_REQUEST || name == AdEventName.LOAD_RESULT
                val parameters = event.analyticsParameters()
                assertEquals(!loading, parameters.containsKey("position"))
                assertFalse(parameters.containsKey("position_id"))
                assertEquals("load-request", parameters["request_id"])
                assertEquals(format.analyticsValue, parameters["ad_type"])
                val log = when (format) {
                    AdFormat.APP_OPEN -> event.appOpenLogLines().joinToString("\n")
                    AdFormat.NATIVE -> formatNativeDebugLogMessage(event)
                    else -> formatAdEventLogMessage(event)
                }
                assertEquals(!loading, log.contains("business-position"))
                if (loading) {
                    assertFalse(log.contains("position="))
                    assertFalse(log.contains("pos="))
                    assertTrue(log.contains("load-request"))
                } else {
                    assertEquals("business-position", parameters["position"])
                }
            }
        }
    }

    @Test
    fun `banner event exposes slot identity without changing legacy event shape`() {
        val banner = AdEvent(
            name = AdEventName.BANNER_REFRESH,
            platform = AdPlatform.ADMOB,
            format = AdFormat.BANNER,
            position = "home_banner",
            sessionId = "display-1",
            adUnitId = "banner-unit",
            number = 1L,
            slotId = "slot-1",
            result = "filled",
        ).analyticsParameters()

        assertEquals("ad_banner_refresh", AdEventName.BANNER_REFRESH.analyticsName)
        assertEquals("banner", banner["ad_type"])
        assertEquals("slot-1", banner["slot_id"])
        assertEquals("filled", banner["result"])
        assertFalse(AdEvent(
            name = AdEventName.IMPRESSION,
            platform = AdPlatform.ADMOB,
            format = AdFormat.REWARDED,
            position = "game_rewarded",
            sessionId = "display-2",
            adUnitId = "rewarded-unit",
            number = 1L,
        ).analyticsParameters().containsKey("slot_id"))
    }

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
