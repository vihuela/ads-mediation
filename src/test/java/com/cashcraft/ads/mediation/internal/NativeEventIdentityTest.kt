package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.view.View
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.nativeads.*
import org.junit.Assert.*
import org.junit.Test

class NativeEventIdentityTest {
    @Test fun `cached bidding opportunity follows actual material and has no fabricated load identity`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val slot = NativeSlot(ResolvedNativeRequest(position = "home", admobAdUnitId = "a", topOnPlacementId = "t"),
            1, { 1 }, AdEventListener(events::add), AdRevenueListener(revenues::add))
        slot.position()
        val attempt = slot.attempt({ 0 }, recordLoadEvents = false)
        attempt.start()
        attempt.loaded(Ad(AdPlatform.TOPON))
        val revenue = NativeRevenue(20, "USD", "real-source", "response", "exact", topOnAdInfo = Any())
        attempt.paid(revenue) // An SDK payment may precede its impression.
        attempt.paid(revenue.copy(responseId = null)) // Missing response metadata must not defeat display dedup.
        attempt.impression("real-source", "response", revenue)
        attempt.impression("real-source", "response", revenue)
        attempt.click("real-source", "response")
        attempt.close()
        attempt.close()
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION,
            AdEventName.CLICK, AdEventName.DISMISS), events.map { it.name })
        assertEquals(1, events.map { it.sessionId }.distinct().size)
        assertNotEquals("home", attempt.id)
        assertFalse(events.first().platformKnown)
        assertTrue(events.drop(1).all { it.platformKnown && it.platform == AdPlatform.TOPON && it.adUnitId == "t" })
        assertTrue(events.all { it.requestId == null && it.position == "home" })
        assertEquals(attempt.id, revenues.single().sessionId)
    }

    @Test fun `retry shares one business opportunity while old late payment keeps its material identity`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val slot = NativeSlot(ResolvedNativeRequest(AdPlatform.ADMOB, "unit", "home"), 1, { 1 },
            AdEventListener(events::add), AdRevenueListener(revenues::add))
        slot.position()
        val old = slot.attempt({ 0 })
        old.start()
        old.loaded(Ad(AdPlatform.ADMOB))
        old.cancel("native_inactive")
        val next = slot.attempt({ 0 }, recordLoadEvents = false)
        next.start()
        next.loaded(Ad(AdPlatform.ADMOB))
        next.impression("source", "response")
        repeat(2) { next.paid(NativeRevenue(5, "USD", "source", "response", "exact")) }
        repeat(2) { old.paid(NativeRevenue(0, "USD", "source", "response", "exact")) }
        slot.position()
        val positions = events.filter { it.name == AdEventName.POSITION }
        assertEquals(1, positions.size)
        assertEquals(old.id, positions.single().sessionId)
        assertNotEquals(old.id, next.id)
        assertTrue(events.all { it.slotId == slot.id })
        assertTrue(events.filter { !it.isLoadEvent }.all { it.analyticsParameters()["ad_session_id"] == slot.id })
        assertEquals(listOf(next.id, old.id), revenues.map { it.sessionId })
        assertEquals(listOf(revenueEventId(AdPlatform.ADMOB, null, next.id),
            revenueEventId(AdPlatform.ADMOB, null, old.id)), revenues.map { it.eventId })
        assertEquals(2, revenues.map { it.eventId }.distinct().size)
        val load = events.single { it.name == AdEventName.LOAD }
        assertNotEquals(old.id, load.requestId)
        assertEquals(load.requestId, events.single { it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) }.requestId)
        assertEquals(old.id, events.single { it.name == AdEventName.SHOW_FAIL }.sessionId)
        assertEquals(old.id, events.single { it.name == AdEventName.IMPRESSION && it.sessionId == old.id }.sessionId)
        assertEquals(next.id, events.single { it.name == AdEventName.IMPRESSION && it.sessionId == next.id }.sessionId)
        assertTrue(events.none { it.name == AdEventName.DISMISS || it.name == AdEventName.REWARD })
    }

    @Test fun `real close before impression fails once and accepts original late revenue`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val slot = NativeSlot(ResolvedNativeRequest(AdPlatform.ADMOB, "unit", "home"), 1, { 1 },
            AdEventListener(events::add), AdRevenueListener(revenues::add))
        val attempt = slot.attempt({ 0 }, recordLoadEvents = false)
        attempt.loaded(Ad(AdPlatform.ADMOB))
        attempt.close()
        attempt.close()
        attempt.cancel("scene_inactive")
        attempt.impression("source", "response")
        attempt.paid(NativeRevenue(10, "USD", "source", "response", "exact"))
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL, AdEventName.IMPRESSION),
            events.map { it.name })
        assertEquals("cancelled", events[1].reason)
        assertTrue(events.all { it.sessionId == attempt.id })
        assertEquals(attempt.id, revenues.single().sessionId)
    }

    @Test fun `close cannot add dismiss or another failure after a failed opportunity`() {
        val events = mutableListOf<AdEvent>()
        val slot = NativeSlot(ResolvedNativeRequest(AdPlatform.ADMOB, "unit", "home"), 1, { 1 },
            AdEventListener(events::add), AdRevenueListener.NONE)
        val attempt = slot.attempt({ 0 }, recordLoadEvents = false)
        attempt.fail("no_fill")
        repeat(2) { attempt.close() }
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals("no_fill", events.last().reason)
    }

    @Test fun `AdMob business impression follows paid in either callback order without inventing exposure`() {
        for (paidFirst in listOf(true, false)) {
            val events = mutableListOf<AdEvent>()
            val revenues = mutableListOf<AdRevenuePayload>()
            var exposures = 0
            val attempt = NativeSlot(ResolvedNativeRequest(AdPlatform.ADMOB, "unit", "home"), 1, { 1 },
                AdEventListener(events::add), AdRevenueListener(revenues::add))
                .attempt({ 0 }, recordLoadEvents = false)
            val revenue = NativeRevenue(23, "USD", "paid-source", "paid-response", "exact")
            if (paidFirst) {
                attempt.paid(revenue)
                assertEquals(0, exposures)
            } else {
                attempt.impression("exposure-source", "exposure-response", onActualImpression = { exposures++ })
                assertTrue(events.none { it.name == AdEventName.IMPRESSION })
            }
            attempt.paid(revenue)
            repeat(2) {
                attempt.impression("exposure-source", "exposure-response", onActualImpression = { exposures++ })
            }
            attempt.close()
            attempt.paid(revenue.copy(responseId = null))
            val impression = events.single { it.name == AdEventName.IMPRESSION }
            assertEquals(23L, impression.valueMicros)
            assertEquals("USD", impression.currency)
            assertEquals("exact", impression.precisionType)
            assertEquals("paid-response", impression.responseId)
            assertEquals(1, exposures)
            assertEquals(1, revenues.size)
            assertEquals(1, events.count { it.name == AdEventName.DISMISS })
            assertTrue(events.none { it.name == AdEventName.SHOW_FAIL })
        }
    }

    @Test fun `TopOn impression owns its callback revenue and paid never adds or overwrites business event`() {
        for (paidFirst in listOf(true, false)) {
            val events = mutableListOf<AdEvent>()
            val revenues = mutableListOf<AdRevenuePayload>()
            val attempt = NativeSlot(ResolvedNativeRequest(AdPlatform.TOPON, "unit", "home"), 1, { 1 },
                AdEventListener(events::add), AdRevenueListener(revenues::add))
                .attempt({ 0 }, recordLoadEvents = false)
            val impressed = NativeRevenue(0, "USD", "impressed-source", "impressed-response", "estimated", topOnAdInfo = Any())
            val paid = impressed.copy(valueMicros = 45, responseId = "paid-response", precisionType = "exact")
            if (paidFirst) {
                attempt.paid(paid)
                assertTrue(events.none { it.name == AdEventName.IMPRESSION })
            }
            repeat(2) { attempt.impression(impressed.adSource, impressed.responseId, impressed) }
            attempt.close()
            repeat(2) { attempt.paid(paid) }
            val impression = events.single { it.name == AdEventName.IMPRESSION }
            assertEquals(0L, impression.valueMicros)
            assertEquals("estimated", impression.precisionType)
            assertEquals("impressed-response", impression.responseId)
            assertEquals(45L, revenues.single().valueMicros)
        }
    }

    @Test fun `unknown TopOn impression money stays absent even after valid paid`() {
        val events = mutableListOf<AdEvent>()
        val attempt = NativeSlot(ResolvedNativeRequest(AdPlatform.TOPON, "unit", "home"), 1, { 1 },
            AdEventListener(events::add), AdRevenueListener.NONE).attempt({ 0 }, recordLoadEvents = false)
        attempt.impression("network", "response")
        attempt.paid(NativeRevenue(1, "USD", "network", "response", "exact", topOnAdInfo = Any()))
        val impression = events.single { it.name == AdEventName.IMPRESSION }
        assertNull(impression.value)
        assertNull(impression.valueMicros)
        assertNull(impression.currency)
        assertNull(impression.precisionType)
    }

    private class Ad(override val platform: AdPlatform) : NativeAdHandle {
        override val adSource = "real-source"
        override val responseId = "response"
        override val isTemplate = false
        override val expiresAtMillis: Long? = null
        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("unused")
        override fun destroy() = Unit
    }
}
