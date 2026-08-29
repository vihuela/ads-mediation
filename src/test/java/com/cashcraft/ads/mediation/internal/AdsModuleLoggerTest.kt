package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsModuleLoggerTest {
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
                name = AdEventName.LOAD_RESULT,
                platform = AdPlatform.TOPON,
                format = AdFormat.REWARDED,
                position = "preload_rewarded",
                sessionId = "load-session",
                adUnitId = "placement",
                number = 4L,
                requestId = "load-request",
                result = "no_fill",
                latencyMillis = 1_250L,
                bufferSize = 1,
            ),
        )

        assertTrue(message.contains("request_id=load-request"))
        assertTrue(message.contains("result=no_fill"))
        assertTrue(message.contains("latency_ms=1250"))
        assertTrue(message.contains("buffer_size=1"))
    }
}
