package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual-call boundary used by both full-screen providers, without SDK networking. */
class ActualLoadContractTest {
    private val formats = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)

    @Test fun `allocation or display wait alone does not publish a load`() {
        for (format in formats) {
            val events = mutableListOf<AdEvent>()
            load(events, format)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun `synchronous SDK terminal follows request exactly once on both platforms`() {
        for (platform in AdPlatform.entries) for (format in formats) {
            val events = mutableListOf<AdEvent>()
            val session = load(events, format, platform)
            var calls = 0
            session.invokeLoad {
                calls++
                session.loaded("network", "response")
                session.failed("no_fill", "NO_FILL", "duplicate body", null)
                assertTrue(events.isEmpty())
            }
            session.request()
            assertEquals(1, calls)
            assertPair(events, "filled")
        }
    }

    @Test fun `asynchronous no fill uses same request and drops repeated terminal`() {
        for (format in formats) {
            val events = mutableListOf<AdEvent>()
            val session = load(events, format)
            session.invokeLoad { }
            assertEquals(listOf(AdEventName.LOAD), events.map { it.name })
            session.failed("no_fill", "NO_FILL", "private SDK body", null)
            session.loaded(null, "late")
            assertPair(events, "no_fill")
            assertEquals("no_fill", events.last().analyticsParameters()["reason"])
        }
    }

    @Test fun `thrown SDK invocation publishes request and original exception remains visible`() {
        for (format in formats) {
            val events = mutableListOf<AdEvent>()
            val session = load(events, format)
            val original = IllegalStateException("SDK body")
            val failure = runCatching { session.invokeLoad { throw original } }.exceptionOrNull()
            assertSame(original, failure)
            session.failed("error", "exception", failure?.message, null)
            assertPair(events, "error")
            assertEquals("exception", events.last().reason)
        }
    }

    @Test fun `policy destroys outstanding pool once and late SDK callback cannot change cancelled result`() {
        val events = mutableListOf<AdEvent>()
        val session = load(events, AdFormat.APP_OPEN)
        session.invokeLoad { }
        repeat(2) { session.failed("cancelled", null, null, null) }
        session.loaded(null, "late")
        assertPair(events, "cancelled")
    }

    @Test fun `request listener may cancel actual started load without reversing event order`() {
        val events = mutableListOf<AdEvent>()
        lateinit var session: AdLoadSession
        session = load(events, AdFormat.INTERSTITIAL, listener = AdEventListener {
            events += it
            if (it.name == AdEventName.LOAD) session.failed("cancelled", null, null, null)
        })
        var started = false
        session.invokeLoad { started = true }
        assertTrue(started)
        assertPair(events, "cancelled")
    }

    @Test fun `synchronous actual success wins over policy cancellation from request listener`() {
        val events = mutableListOf<AdEvent>()
        lateinit var session: AdLoadSession
        session = load(events, AdFormat.REWARDED, listener = AdEventListener {
            events += it
            if (it.name == AdEventName.LOAD) session.failed("cancelled", null, null, null)
        })
        session.invokeLoad { session.loaded(null, "response") }
        assertPair(events, "filled")
    }

    private fun load(events: MutableList<AdEvent>, format: AdFormat,
        platform: AdPlatform = AdPlatform.ADMOB,
        listener: AdEventListener = AdEventListener(events::add)) = AdLoadSession(
        listener, platform, if (platform == AdPlatform.ADMOB) AdMediationMode.ADMOB else AdMediationMode.TOPON,
        format, "preload", "unit", "request", "request", 1, 1, 0,
        AdLoadClock { 25 }, deferUntilRequest = true,
    )

    private fun assertPair(events: List<AdEvent>, result: String) {
        assertEquals(listOf(AdEventName.LOAD, if (result == "filled") AdEventName.LOADED else AdEventName.LOAD_FAIL), events.map { it.name })
        assertEquals(listOf("ad_load", if (result == "filled") "ad_loaded" else "ad_load_fail"),
            events.map { it.name.analyticsName })
        assertEquals(result, events.last().result)
        events.forEach {
            assertEquals("request", it.analyticsParameters()["request_id"])
            assertTrue("all formats emit valid v2 properties", it.analyticsParameters().isNotEmpty())
            assertTrue(!it.analyticsParameters().containsKey("position_id"))
        }
        assertEquals(events.first().requestId, events.last().requestId)
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals(25L, events.last().latencyMillis)
    }
}
