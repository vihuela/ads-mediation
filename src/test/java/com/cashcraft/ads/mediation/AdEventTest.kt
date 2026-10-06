package com.cashcraft.ads.mediation

import com.cashcraft.ads.mediation.internal.formatAdEventLogMessage
import com.cashcraft.ads.mediation.internal.formatNativeDebugLogMessage
import org.junit.Assert.*
import org.junit.Test

class AdEventTest {
    private fun event(name: AdEventName = AdEventName.IMPRESSION, format: AdFormat = AdFormat.INTERSTITIAL) =
        AdEvent(name, AdPlatform.ADMOB, format, "business-position", "display-1", "unit", 1,
            requestId = "load-1", latencyMillis = 25, valueMicros = 1_250, currency = "USD",
            adSource = "Google", slotId = "slot-1", responseId = "diagnostic")

    @Test fun `public load and reward names are v2 at their source`() {
        assertEquals(listOf("ad_load", "ad_loaded", "ad_load_fail", "ad_reward"),
            listOf(AdEventName.LOAD, AdEventName.LOADED, AdEventName.LOAD_FAIL, AdEventName.REWARD).map { it.analyticsName })
    }

    @Test fun `all formats serialize v2 with independent load and display identities`() {
        for (format in AdFormat.entries) for (name in AdEventName.entries) {
            val ad = event(name, format).copy(reason = "platform_disabled")
            val parameters = ad.analyticsParameters()
            val unsupported = name in setOf(AdEventName.BID_RESULT, AdEventName.BANNER_REFRESH) ||
                (name == AdEventName.DISMISS && format == AdFormat.BANNER) ||
                (name == AdEventName.REWARD && format != AdFormat.REWARDED)
            if (unsupported) { assertTrue(parameters.isEmpty()); continue }
            assertEquals(format.analyticsValue, parameters["ad_type"])
            if (name in setOf(AdEventName.POSITION, AdEventName.SCENE_SKIP)) {
                assertFalse(parameters.containsKey("ad_platform"))
                assertFalse(parameters.containsKey("ad_unit_id"))
            } else {
                assertEquals("admob", parameters["ad_platform"])
                assertEquals("unit", parameters["ad_unit_id"])
            }
            for (key in listOf("position", "session_id", "slot_id", "number", "response_id", "result",
                "platform_known", "value", "value_micros", "buffer_size", "tracking_plan_version")) {
                assertFalse("$name must not expose $key", parameters.containsKey(key))
            }
            if (ad.isLoadEvent) {
                assertEquals("load-1", parameters["request_id"])
                assertFalse(parameters.containsKey("position_id"))
                assertFalse(parameters.containsKey("ad_session_id"))
                if (name != AdEventName.LOAD) assertEquals(25L, parameters["latency_ms"])
            } else {
                assertEquals("business-position", parameters["position_id"])
                assertFalse(parameters.containsKey("request_id"))
                if (name != AdEventName.SCENE_SKIP) {
                    assertEquals(if (format == AdFormat.BANNER || format == AdFormat.NATIVE) "slot-1" else "display-1", parameters["ad_session_id"])
                }
            }
        }
    }

    @Test fun `仅 Banner 业务曝光携带从一开始的刷新序号且无页面身份时保持原会话`() {
        for (format in AdFormat.entries) for (name in AdEventName.entries) {
            val parameters = event(name, format).copy(refreshIndex = 2).analyticsParameters()
            if (format == AdFormat.BANNER && name == AdEventName.IMPRESSION) {
                assertEquals(2L, parameters["refresh_index"])
            } else {
                assertFalse(parameters.containsKey("refresh_index"))
            }
        }
        for (index in listOf(null, 0L, -1L)) {
            assertFalse(event(format = AdFormat.BANNER).copy(refreshIndex = index)
                .analyticsParameters().containsKey("refresh_index"))
        }
        assertEquals("display-1", event(format = AdFormat.BANNER).copy(slotId = null)
            .analyticsParameters()["ad_session_id"])
    }

    @Test fun `native and banner business identity falls back to the original session without a slot`() {
        for (format in listOf(AdFormat.NATIVE, AdFormat.BANNER)) {
            for (name in listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL, AdEventName.IMPRESSION, AdEventName.CLICK)) {
                val ad = event(name, format).copy(slotId = null)
                assertEquals("display-1", ad.analyticsParameters()["ad_session_id"])
                assertEquals("display-1", ad.sessionId)
            }
        }
    }

    @Test fun `loading omits scene from every log format`() {
        for (format in AdFormat.entries) for (name in listOf(AdEventName.LOAD, AdEventName.LOADED, AdEventName.LOAD_FAIL)) {
            val ad = event(name, format)
            val log = when (format) {
                AdFormat.APP_OPEN -> ad.appOpenLogLines().joinToString("\n")
                AdFormat.NATIVE -> formatNativeDebugLogMessage(ad)
                else -> formatAdEventLogMessage(ad)
            }
            assertFalse(log.contains("business-position"))
            assertTrue(log.contains("load-1"))
        }
    }

    @Test fun `scene skip has only canonical identity type and reason`() {
        for ((raw, canonical) in mapOf("platform_disabled" to "platform_disabled",
            "daily_show_limit" to "show_rate_limited", "daily_click_limit" to "click_rate_limited")) {
            assertEquals(mapOf("position_id" to "business-position", "ad_type" to "interstitial", "reason" to canonical),
                event(AdEventName.SCENE_SKIP).copy(reason = raw, platformKnown = false).analyticsParameters())
        }
        assertTrue(event(AdEventName.SCENE_SKIP).copy(reason = "private-body").analyticsParameters().isEmpty())
    }

    @Test fun `position omits provider fields for every format platform and mediation mode`() {
        for (format in AdFormat.entries) for (platform in AdPlatform.entries) {
            for (mode in AdMediationMode.entries) for (known in listOf(true, false)) {
                val parameters = event(AdEventName.POSITION, format).copy(
                    platform = platform, mediationMode = mode, platformKnown = known,
                ).analyticsParameters()
                assertEquals(mapOf(
                    "ad_type" to format.analyticsValue,
                    "mediation_mode" to mode.analyticsValue,
                    "position_id" to "business-position",
                    "ad_session_id" to if (format == AdFormat.BANNER || format == AdFormat.NATIVE) "slot-1" else "display-1",
                ), parameters)
            }
        }
    }

    @Test fun `unselected show failure is unknown and never exposes a configured unit`() {
        for (format in AdFormat.entries) {
            val parameters = event(AdEventName.SHOW_FAIL, format)
                .copy(platformKnown = false, mediationMode = AdMediationMode.BIDDING).analyticsParameters()
            assertEquals("unknown", parameters["ad_platform"])
            assertEquals("bidding", parameters["mediation_mode"])
            assertFalse(parameters.containsKey("ad_unit_id"))
        }
        for (name in listOf(AdEventName.LOAD, AdEventName.LOADED, AdEventName.LOAD_FAIL,
            AdEventName.IMPRESSION, AdEventName.CLICK, AdEventName.DISMISS, AdEventName.IMPRESSION, AdEventName.REWARD)) {
            assertTrue(event(name).copy(platformKnown = false).analyticsParameters().isEmpty())
        }
    }

    @Test fun `bid result reports both candidates and actual winner with a stable business session`() {
        for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED, AdFormat.NATIVE)) {
            for (winner in AdPlatform.entries) {
                val parameters = event(AdEventName.BID_RESULT, format).copy(
                    mediationMode = AdMediationMode.BIDDING, platformKnown = false,
                    winnerPlatform = winner, admobAvailable = true, topOnAvailable = true,
                    admobValue = 0.0, topOnValue = 0.00125, winningValue = if (winner == AdPlatform.ADMOB) 0.0 else 0.00125,
                    admobAdUnitId = "admob-unit", topOnAdUnitId = "topon-unit", currency = "USD",
                ).analyticsParameters()
                assertEquals("won", parameters["result"])
                assertEquals(winner.analyticsValue, parameters["ad_platform"])
                assertEquals(winner.analyticsValue, parameters["winner_platform"])
                assertEquals(if (winner == AdPlatform.ADMOB) "admob-unit" else "topon-unit", parameters["ad_unit_id"])
                assertEquals("admob-unit", parameters["admob_ad_unit_id"])
                assertEquals("topon-unit", parameters["topon_ad_unit_id"])
                assertEquals(true, parameters["admob_available"])
                assertEquals(true, parameters["topon_available"])
                assertEquals(true, parameters["admob_price_available"])
                assertEquals(true, parameters["topon_price_available"])
                assertEquals(0.0, parameters["admob_value"])
                assertEquals(0.00125, parameters["topon_value"])
                assertEquals(if (winner == AdPlatform.ADMOB) 0.0 else 0.00125, parameters["winning_value"])
                assertEquals("USD", parameters["currency"])
                assertEquals(if (format == AdFormat.NATIVE) "slot-1" else "display-1", parameters["ad_session_id"])
            }
        }
    }

    @Test fun `no bid candidate reports unknow amounts without inventing a winner unit or zero prices`() {
        val parameters = event(AdEventName.BID_RESULT, AdFormat.NATIVE).copy(
            mediationMode = AdMediationMode.BIDDING, platformKnown = false,
            admobAvailable = false, topOnAvailable = false, currency = "USD",
            admobAdUnitId = "admob-unit", topOnAdUnitId = "topon-unit",
        ).analyticsParameters()
        assertEquals("no_candidate", parameters["result"])
        assertEquals("unknown", parameters["ad_platform"])
        assertEquals("unknown", parameters["winner_platform"])
        assertEquals(false, parameters["admob_available"])
        assertEquals(false, parameters["topon_available"])
        assertEquals(false, parameters["admob_price_available"])
        assertEquals(false, parameters["topon_price_available"])
        assertFalse(parameters.containsKey("ad_unit_id"))
        for (key in listOf("admob_value", "topon_value", "winning_value")) {
            assertEquals(key, "unknow", parameters[key])
        }
    }

    @Test fun `unknown invalid and nonfinite bid prices report unknow without dropping the result`() {
        for (price in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val bid = event(AdEventName.BID_RESULT).copy(mediationMode = AdMediationMode.BIDDING,
                winnerPlatform = AdPlatform.TOPON, admobAvailable = true, topOnAvailable = true,
                admobValue = price, topOnValue = price, winningValue = price, currency = "USD")
            val parameters = bid.analyticsParameters()
            assertEquals("won", parameters["result"])
            assertEquals(false, parameters["admob_price_available"])
            assertEquals(false, parameters["topon_price_available"])
            for (key in listOf("admob_value", "topon_value", "winning_value")) assertEquals("unknow", parameters[key])
            assertTrue(bid.copy(position = "").analyticsParameters().isEmpty())
            assertTrue(bid.copy(sessionId = "").analyticsParameters().isEmpty())
            assertTrue(bid.copy(currency = "EUR").analyticsParameters().isEmpty())
            assertTrue(bid.copy(mediationMode = AdMediationMode.ADMOB).analyticsParameters().isEmpty())
        }
    }

    @Test fun `failures emit controlled reasons and suppress exception details`() {
        val reasons = mapOf("no_candidate" to "no_fill", "native_bid_timeout" to "timeout",
            "opportunity_cancelled" to "cancelled", "another_full_screen_ad_showing" to "ad_busy",
            "ad_platform_disabled" to "platform_disabled", "native_inactive" to "scene_inactive",
            "daily_show_limit" to "show_rate_limited", "daily_click_limit" to "click_rate_limited",
            "banner_configuration_failed" to "exception", "private SDK body" to "ad_error")
        for (format in AdFormat.entries) for (name in listOf(AdEventName.LOAD_FAIL, AdEventName.SHOW_FAIL)) {
            for ((raw, canonical) in reasons) {
                val properties = event(name, format).copy(reason = raw, errorCode = "SDK_ERROR").analyticsParameters()
                assertEquals(canonical, properties["reason"])
                assertEquals(if (canonical == "exception") null else "SDK_ERROR", properties["error_code"])
            }
            val thrown = event(name, format).copy(reason = "private body", errorCode = "load_exception").analyticsParameters()
            assertEquals("exception", thrown["reason"])
            assertFalse(thrown.containsKey("error_code"))
        }
    }

    @Test fun `impression revenue is in currency units while raw provider fields remain in the callback`() {
        val ad = event(AdEventName.IMPRESSION)
        assertEquals(0.00125, ad.analyticsParameters()["revenue_amount"])
        assertEquals("USD", ad.analyticsParameters()["currency"])
        assertEquals(1_250L, ad.valueMicros)
        assertEquals("diagnostic", ad.responseId)
        for (invalid in listOf(ad.copy(valueMicros = null), ad.copy(valueMicros = -1),
            ad.copy(currency = "usd"), ad.copy(currency = null))) {
            assertFalse(invalid.analyticsParameters().isEmpty())
            assertFalse(invalid.analyticsParameters().containsKey("revenue_amount"))
            assertFalse(invalid.analyticsParameters().containsKey("currency"))
        }
        assertEquals(0.0, ad.copy(valueMicros = 0).analyticsParameters()["revenue_amount"])
        assertFalse(AdEventName.entries.any { it.analyticsName == "ad_paid" })
    }

    @Test fun `missing identities and terminal latency cannot create valid business events`() {
        assertTrue(event().copy(position = "").analyticsParameters().isEmpty())
        assertTrue(event().copy(sessionId = "").analyticsParameters().isEmpty())
        assertTrue(event(AdEventName.LOAD).copy(requestId = null).analyticsParameters().isEmpty())
        for (name in listOf(AdEventName.LOADED, AdEventName.LOAD_FAIL)) {
            assertTrue(event(name).copy(latencyMillis = null).analyticsParameters().isEmpty())
            assertTrue(event(name).copy(latencyMillis = -1).analyticsParameters().isEmpty())
        }
    }

    @Test fun `TopOn uses the same v2 business schema`() {
        val ad = event()
        assertEquals(ad.analyticsParameters() + ("ad_platform" to "topon") + ("mediation_mode" to "topon"),
            ad.copy(platform = AdPlatform.TOPON, mediationMode = AdMediationMode.TOPON).analyticsParameters())
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
