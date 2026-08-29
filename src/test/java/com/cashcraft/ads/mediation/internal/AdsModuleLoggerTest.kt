package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsModuleLoggerTest {
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
