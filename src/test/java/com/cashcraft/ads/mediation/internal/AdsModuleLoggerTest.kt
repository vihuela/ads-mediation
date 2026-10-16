package com.cashcraft.ads.mediation.internal

import android.util.Log
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AdsModuleLoggerTest {
    @Test fun `invalid business events warn while intentional diagnostics and disabled logs stay quiet`() {
        for (format in AdFormat.entries) {
            ShadowLog.clear()
            val event = AdEvent(AdEventName.LOADED, AdPlatform.ADMOB, format,
                "position", "session", "unit", 1, requestId = "request")
            val logger = AdsModuleLogger(true, "contract-test")
            logger.event(event)
            val warnings = ShadowLog.getLogsForTag("contract-test").filter { it.type == Log.WARN }
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().msg.contains("ad_event_contract_violation"))
            assertTrue(warnings.single().msg.contains("event=ad_loaded"))
            assertTrue(warnings.single().msg.contains("format=${format.analyticsValue}"))
            ShadowLog.clear()
            logger.event(event.copy(name = AdEventName.BANNER_REFRESH))
            logger.event(event.copy(name = AdEventName.DISMISS, format = AdFormat.BANNER))
            assertFalse(ShadowLog.getLogsForTag("contract-test").any {
                it.msg.contains("ad_event_contract_violation")
            })
            ShadowLog.clear()
            AdsModuleLogger(false, "contract-test").event(event)
            assertTrue(ShadowLog.getLogsForTag("contract-test").isEmpty())
        }
    }

    @Test fun `invalid full screen revenue logs diagnostic without impression or consuming valid payment`() {
        for (platform in AdPlatform.entries) {
            val events = mutableListOf<AdEvent>()
            val session = AdShowSession(AdEventListener(events::add), platform, AdMediationMode.BIDDING,
                AdFormat.INTERSTITIAL, "position", "unit", "session", 1,
                logger = AdsModuleLogger(true, "revenue-test"))
            session.admit()
            ShadowLog.clear()
            for ((amount, currency) in listOf(null to "USD", -1L to "USD", 1L to null,
                1L to "", 1L to "US", 1L to "usd")) {
                assertFalse(session.revenue(valueMicros = amount, currency = currency))
            }
            val diagnostics = ShadowLog.getLogsForTag("revenue-test").filter {
                it.type == Log.WARN && it.msg.contains("invalid_revenue")
            }
            assertEquals(6, diagnostics.size)
            assertTrue(diagnostics.all { it.msg.contains("platform=${platform.analyticsValue}") &&
                it.msg.contains("format=interstitial") && it.msg.contains("session_id=session") })
            assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
            assertTrue(session.revenue(valueMicros = 0, currency = "USD"))
            assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
        }
    }

    @Test
    fun `app open container failure log contains show failure details`() {
        val message = formatAdEventLogMessage(
            AdEvent(
                name = AdEventName.SHOW_FAIL,
                platform = AdPlatform.TOPON,
                mediationMode = AdMediationMode.BIDDING,
                format = AdFormat.APP_OPEN,
                position = "launcher_minus_one_app_open",
                sessionId = "show-session",
                adUnitId = "placement",
                number = 1L,
                reason = "app_open_container_unavailable",
                errorCode = "container_not_found",
            ),
        )

        assertTrue(message.contains("ad_event=ad_show_fail"))
        assertTrue(message.contains("reason=app_open_container_unavailable"))
        assertTrue(message.contains("error_code=container_not_found"))
    }

    @Test
    fun `load result log contains correlation result and latency fields`() {
        val message = formatAdEventLogMessage(
            AdEvent(
                name = AdEventName.LOADED,
                platform = AdPlatform.TOPON,
                format = AdFormat.REWARDED,
                position = "preload_rewarded",
                sessionId = "load-session",
                adUnitId = "placement",
                number = 4L,
                requestId = "load-request",
                slotId = "banner-slot",
                result = "no_fill",
                latencyMillis = 1_250L,
                bufferSize = 1,
            ),
        )

        assertTrue(message.contains("request_id=load-request"))
        assertTrue(message.contains("slot_id=banner-slot"))
        assertTrue(message.contains("result=no_fill"))
        assertTrue(message.contains("latency_ms=1250"))
        assertTrue(message.contains("buffer_size=1"))
    }
}
