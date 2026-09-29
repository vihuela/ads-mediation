package com.cashcraft.ads.mediation.internal.admob

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdRevenuePayload
import com.cashcraft.ads.mediation.internal.AdLoadClock
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.BannerSlot
import org.junit.Assert.*
import org.junit.Test

class AdMobBannerEventsTest {
    @Test
    fun `failed local load can recover internally without a second result or request association`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val relay = relay(events, revenues)
        relay.failed("NO_FILL", "empty", null)
        relay.loaded(BannerResponse("recovered"))
        relay.impression(BannerResponse("recovered"))
        relay.paid(BannerResponse("recovered"), 0L, "USD", null, 1L)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.LOAD_REQUEST, AdEventName.LOAD_RESULT,
            AdEventName.IMPRESSION, AdEventName.PAID), events.map { it.name })
        assertEquals("failed", events[2].result)
        assertNull(events[3].requestId)
        assertEquals(1, revenues.size)
    }

    @Test
    fun `callback snapshots retain earlier response during refresh and known paid survives release`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val relay = relay(events, revenues)
        val first = BannerResponse("first", "network-a", "adapter-a")
        relay.prepareLoaded(first)
        // The paid listener can run as soon as it is installed, before external load notification.
        relay.paid(first, 0L, "USD", null, 1L)
        relay.loaded(first)
        relay.impression(first)
        val second = BannerResponse("second", "network-b", "adapter-b")
        relay.refreshed(second)
        relay.refreshed(second)
        relay.impression(second)
        relay.end()
        relay.end()
        relay.loaded(BannerResponse("obsolete-delivery"))
        relay.refreshed(BannerResponse("obsolete-refresh"))
        relay.click(first)
        relay.paid(second, 4L, "USD", null, 2L)
        relay.paid(first, 0L, "USD", null, 3L)
        assertEquals(2, revenues.size)
        assertEquals(listOf("first", "second"), revenues.map { it.impressionId })
        assertEquals(listOf("network-a", "network-b"), revenues.map { it.adNetwork })
        assertNotEquals(revenues[0].sessionId, revenues[1].sessionId)
        assertEquals(1, events.count { it.name == AdEventName.BANNER_REFRESH })
        assertEquals(2, events.count { it.name == AdEventName.IMPRESSION })
        assertTrue(events.none { it.name == AdEventName.CLICK })
        assertEquals("request", events.first { it.name == AdEventName.PAID }.requestId)
        assertNull(events.last().requestId)
    }

    @Test
    fun `unknown and evicted identities cannot become normal revenue or inherit the newest display`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val relay = relay(events, revenues)
        relay.loaded(BannerResponse("first"))
        repeat(32) { relay.refreshed(BannerResponse("refresh-$it")) }
        for (id in listOf(null, "unseen", "first")) {
            relay.impression(BannerResponse(id))
            relay.paid(BannerResponse(id), 7L, "USD", null, 1L)
        }
        assertTrue(revenues.isEmpty())
        assertTrue(events.none { it.name == AdEventName.IMPRESSION || it.name == AdEventName.PAID })
        relay.paid(BannerResponse("refresh-31"), 7L, "USD", null, 1L)
        assertEquals("refresh-31", revenues.single().impressionId)
    }

    private fun relay(events: MutableList<AdEvent>, revenues: MutableList<AdRevenuePayload>): AdMobBannerEvents {
        val listener = AdEventListener(events::add)
        val slot = BannerSlot(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB,
            "home_banner", "unit", "slot", 1L, revenueListener = AdRevenueListener(revenues::add))
        val load = AdLoadSession(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
            "home_banner", "unit", "request", "request", 1L, null, 0L,
            AdLoadClock { 1L }, slotId = "slot")
        load.request()
        return AdMobBannerEvents(slot, load)
    }
}
