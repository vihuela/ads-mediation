package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdRevenuePayload
import com.cashcraft.ads.mediation.internal.admob.AdMobBannerEvents
import com.cashcraft.ads.mediation.internal.admob.BannerResponse
import org.junit.Assert.*
import org.junit.Test

class BannerCallbackIsolationTest {
    @Test
    fun `each throwing event callback leaves load and display terminal and a new slot usable`() {
        val expected = listOf(
            AdEventName.POSITION, AdEventName.LOAD_REQUEST, AdEventName.LOAD_RESULT,
            AdEventName.IMPRESSION, AdEventName.PAID, AdEventName.CLICK, AdEventName.DISMISS,
        )
        for (throwAt in expected) {
            val events = mutableListOf<AdEvent>()
            val revenues = mutableListOf<AdRevenuePayload>()
            var throws = 0
            val listener = AdEventListener { event ->
                events += event
                if (event.name == throwAt) {
                    throws++
                    error("host rejected $throwAt")
                }
            }
            val revenueListener = AdRevenueListener(revenues::add)
            fun slot(id: String) = BannerSlot(
                listener = listener, platform = AdPlatform.ADMOB,
                mediationMode = AdMediationMode.ADMOB, position = "home_banner",
                adUnitId = "unit", slotId = id, number = 1L,
                revenueListener = revenueListener,
            )

            val firstSlot = slot("first")
            val load = AdLoadSession(
                listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
                "home_banner", "unit", "request", "request", 1L, null, 0L,
                AdLoadClock { 10L }, slotId = "first",
            )
            load.request()
            load.loaded("network", "response-1")
            load.failed("failed", "late", "late failure", null)
            val first = checkNotNull(firstSlot.newDisplay("response-1", "request"))
            first.impression()
            first.impression()
            first.showFailure("late failure")
            first.paid(1L, "USD", null, 1L)
            first.paid(1L, "USD", null, 1L)
            first.click()
            first.close()
            first.close()
            first.refreshSucceeded()

            assertEquals("throwing at $throwAt", expected, events.map(AdEvent::name))
            assertEquals("throwing at $throwAt", 1, throws)
            assertEquals("filled", events[2].result)
            assertEquals("request", events[2].requestId)
            assertEquals(1, revenues.size)
            assertEquals(first.sessionId, revenues.single().sessionId)
            assertTrue(firstSlot.isEnded)
            assertNull(firstSlot.newDisplay("late-response"))

            val nextSlot = slot("next")
            val next = checkNotNull(nextSlot.newDisplay("response-2"))
            next.impression()
            next.paid(2L, "USD", null, 2L)
            assertEquals(
                "new slot after $throwAt",
                listOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.PAID),
                events.takeLast(3).map(AdEvent::name),
            )
            assertEquals(2, revenues.size)
            assertEquals(next.sessionId, revenues.last().sessionId)
            assertNotEquals(revenues.first().eventId, revenues.last().eventId)
        }
    }

    @Test
    fun `failed load result and revenue listener exceptions leave recovered display deliverable`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        var resultThrows = 0
        var revenueThrows = 0
        val listener = AdEventListener { event ->
            events += event
            if (event.name == AdEventName.LOAD_RESULT) {
                resultThrows++
                error("load result listener")
            }
        }
        val slot = BannerSlot(
            listener, AdPlatform.ADMOB, AdMediationMode.ADMOB,
            "home_banner", "unit", "slot", 1L,
            revenueListener = AdRevenueListener { payload ->
                revenues += payload
                revenueThrows++
                error("revenue listener")
            },
        )
        val load = AdLoadSession(
            listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
            "home_banner", "unit", "request", "request", 1L, null, 0L,
            AdLoadClock { 10L }, slotId = "slot",
        )
        load.request()
        val relay = AdMobBannerEvents(slot, load)
        relay.failed("NO_FILL", "empty", null)
        val recovered = BannerResponse("recovered")
        relay.loaded(recovered)
        relay.loaded(recovered)
        relay.impression(recovered)
        relay.paid(recovered, 0L, "USD", null, 1L)
        relay.paid(recovered, 0L, "USD", null, 1L)
        relay.end()
        relay.paid(recovered, 0L, "USD", null, 1L)

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.LOAD_REQUEST, AdEventName.LOAD_RESULT,
                AdEventName.IMPRESSION, AdEventName.PAID),
            events.map(AdEvent::name),
        )
        assertEquals("failed", events[2].result)
        assertEquals(1, resultThrows)
        assertNull(events[3].requestId)
        assertEquals(1, revenueThrows)
        assertEquals("recovered", revenues.single().impressionId)
        assertEquals(events.last().sessionId, revenues.single().sessionId)
    }
}
