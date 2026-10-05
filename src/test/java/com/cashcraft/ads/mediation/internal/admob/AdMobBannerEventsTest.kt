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
        assertEquals(listOf(AdEventName.POSITION, AdEventName.LOAD, AdEventName.LOAD_FAIL,
            AdEventName.IMPRESSION), events.map { it.name })
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
        assertEquals(1, events.count { it.name == AdEventName.POSITION })
        assertEquals(listOf(1L, 2L), events.filter { it.name == AdEventName.IMPRESSION }
            .map { it.analyticsParameters()["refresh_index"] })
        assertTrue(events.none { it.name == AdEventName.CLICK })
        assertEquals("request", events.first { it.name == AdEventName.IMPRESSION }.requestId)
        assertNull(events.last().requestId)
    }

    @Test
    fun `失败及重复刷新不占序号且乱序收益保留原广告序号`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val relay = relay(events, revenues)
        val first = BannerResponse("first")
        val second = BannerResponse("second")
        val third = BannerResponse("third")
        relay.loaded(first)
        relay.loaded(first)
        relay.impression(first)
        relay.refreshFailed("NO_FILL", "empty")
        relay.refreshed(BannerResponse(null))
        relay.refreshed(second)
        relay.refreshed(second)
        relay.impression(second)
        relay.impression(second)
        relay.refreshed(third)
        relay.impression(third)
        for (response in listOf(third, first, second, second)) {
            relay.paid(response, 0L, "USD", null, 1L)
        }
        val impressions = events.filter { it.name == AdEventName.IMPRESSION }
        assertEquals(1, events.count { it.name == AdEventName.POSITION })
        assertEquals(listOf(3L, 1L, 2L), impressions.map { it.analyticsParameters()["refresh_index"] })
        assertEquals(List(3) { "slot" }, impressions.map { it.analyticsParameters()["ad_session_id"] })
        assertEquals(listOf("third", "first", "second"), revenues.map { it.impressionId })
        assertEquals(3, revenues.map { it.eventId }.distinct().size)
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
        assertTrue(events.none { it.name == AdEventName.IMPRESSION })
        relay.paid(BannerResponse("refresh-31"), 7L, "USD", null, 1L)
        assertEquals("refresh-31", revenues.single().impressionId)
    }

    @Test
    fun `cached bind emits no load and SDK result remains observable after page release`() {
        val cached = mutableListOf<AdEvent>()
        val listener = AdEventListener(cached::add)
        val slot = BannerSlot(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB,
            "home_banner", "unit", "slot", 1)
        val load = AdLoadSession(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
            "home_banner", "unit", "load", "load", 1, null, 0, AdLoadClock { 1 })
        val cachedRelay = AdMobBannerEvents(slot, load)
        cachedRelay.suppressLoadTracking()
        cachedRelay.loaded(BannerResponse("cached"))
        cachedRelay.impression(BannerResponse("cached"))
        assertEquals(listOf(AdEventName.POSITION), cached.map { it.name })
        cachedRelay.paid(BannerResponse("cached"), 0L, "USD", "UNKNOWN", 1L)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION), cached.map { it.name })
        assertEquals(cached[0].sessionId, cached[1].sessionId)
        assertNull(cached[1].requestId)

        val actual = mutableListOf<AdEvent>()
        val actualRelay = relay(actual, mutableListOf())
        actualRelay.end()
        actualRelay.loaded(BannerResponse("late"))
        assertEquals(listOf(AdEventName.POSITION, AdEventName.LOAD, AdEventName.LOADED),
            actual.map { it.name })
        assertEquals("filled", actual.last().result)
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
