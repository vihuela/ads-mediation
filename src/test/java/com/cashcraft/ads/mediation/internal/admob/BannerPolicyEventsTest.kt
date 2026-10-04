package com.cashcraft.ads.mediation.internal.admob

import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.*
import org.junit.Assert.*
import org.junit.Test

class BannerPolicyEventsTest {
    @Test fun `initial exposure consumes quota once and unknown responses do not consume quota`() {
        val h = Host()
        val response = BannerResponse("first")
        h.relay.prepareLoaded(response)
        h.relay.loaded(response)
        assertEquals(0, h.impressions)
        h.relay.impression(BannerResponse("unknown"))
        assertEquals(0, h.impressions)
        h.relay.impression(response)
        h.relay.impression(response)
        assertEquals(1, h.impressions)
        h.relay.end()
        h.relay.impression(response)
        assertEquals(1, h.impressions)
        assertEquals(1, h.events.count { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `automatic refresh keeps reporting identity without consuming more daily quota`() {
        val h = Host()
        h.relay.loaded(BannerResponse("first"))
        h.relay.impression(BannerResponse("first"))
        repeat(40) {
            val response = BannerResponse("sdk-refresh-$it")
            h.relay.refreshed(response)
            h.relay.impression(response)
            h.relay.impression(response)
        }
        // Even after the first response leaves the bounded metadata cache it cannot count twice.
        h.relay.impression(BannerResponse("first"))
        assertEquals(1, h.impressions)
        assertEquals(40, h.events.count { it.name == AdEventName.BANNER_REFRESH })
        assertEquals(41, h.events.count { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `refresh callback arriving before initial impression cannot consume its quota`() {
        val h = Host()
        h.relay.prepareLoaded(BannerResponse("first"))
        h.relay.loaded(BannerResponse("first"))
        h.relay.refreshed(BannerResponse("sdk-refresh"))
        h.relay.impression(BannerResponse("sdk-refresh"))
        assertEquals(0, h.impressions)
        h.relay.impression(BannerResponse("first"))
        assertEquals(1, h.impressions)
    }

    @Test fun `a new host show cycle gets its own initial impression quota`() {
        val previous = Host()
        previous.relay.loaded(BannerResponse("first"))
        previous.relay.impression(BannerResponse("first"))
        previous.relay.end()
        val next = Host()
        next.relay.loaded(BannerResponse("second"))
        next.relay.impression(BannerResponse("second"))
        previous.relay.impression(BannerResponse("first"))
        assertEquals(2, previous.impressions + next.impressions)
    }

    @Test fun `every real click reaches its opportunity and presentation event once`() {
        val h = Host()
        val response = BannerResponse("first")
        h.relay.loaded(response)
        h.relay.click(response)
        h.relay.click(response)
        assertEquals(2, h.events.count { it.name == AdEventName.CLICK })
        assertEquals(2, h.policyClicks)
        assertEquals(0, h.impressions)
    }

    @Test fun `actual SDK callback queued before teardown is counted after release`() {
        val h = Host()
        h.relay.loaded(BannerResponse("initial"))
        h.relay.end()
        h.relay.impression(BannerResponse("initial"))
        h.relay.impression(BannerResponse("initial"))
        h.relay.impression(BannerResponse("refreshed"))
        h.relay.impression(BannerResponse("refreshed"))
        assertEquals(1, h.impressions)
        assertTrue(h.events.none { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `late click consumes usage without reviving ended Banner events`() {
        val h = Host()
        val response = BannerResponse("first")
        h.relay.loaded(response)
        h.relay.click(response)
        assertEquals(1, h.events.count { it.name == AdEventName.CLICK })
        assertEquals(1, h.policyClicks)
        h.relay.end()
        h.relay.click(response)
        assertEquals(2, h.policyClicks)
        assertEquals(1, h.events.count { it.name == AdEventName.CLICK })
    }

    @Test fun `ended slot and unknown refresh click are counted outside presentation events`() {
        val h = Host()
        h.relay.loaded(BannerResponse("first"))
        h.relay.click(BannerResponse("sdk-refresh"))
        h.endSlot()
        h.relay.click(BannerResponse("first"))
        assertEquals(2, h.policyClicks)
        assertTrue(h.events.none { it.name == AdEventName.CLICK })
    }

    private class Host {
        val events = mutableListOf<AdEvent>()
        var impressions = 0
        var policyClicks = 0
        fun endSlot() = slot.end()
        private val listener = AdEventListener(events::add)
        private val slot = BannerSlot(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB,
            "home_banner", "unit", "slot", 1)
        private val load = AdLoadSession(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
            "home_banner", "unit", "request", "request", 1, null, 0,
            AdLoadClock { 0 }, slotId = "slot")
        val relay = AdMobBannerEvents(slot, load, policyImpression = { impressions++ }, policyClick = { policyClicks++ })
    }
}
