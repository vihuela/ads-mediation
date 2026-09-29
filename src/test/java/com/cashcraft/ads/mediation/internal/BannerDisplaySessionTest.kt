package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdRevenuePayload
import com.cashcraft.ads.mediation.TopOnRevenuePayload
import org.junit.Assert.*
import org.junit.Test

class BannerDisplaySessionTest {
    @Test
    fun `paid and impression have independent dedup for both callback orders and two refreshes`() {
        for (paidFirst in listOf(true, false)) {
            val events = mutableListOf<AdEvent>()
            val revenues = mutableListOf<AdRevenuePayload>()
            val slot = slot(events, revenues)
            repeat(3) { index ->
                val display = checkNotNull(slot.newDisplay("response-$index", if (index == 0) "load-1" else null))
                if (index > 0) {
                    display.refreshSucceeded()
                    display.refreshSucceeded()
                }
                repeat(2) {
                    if (paidFirst) display.paid(0L, "usd", "ESTIMATED", 1L)
                    display.impression()
                    if (!paidFirst) display.paid(0L, "usd", "ESTIMATED", 1L)
                }
            }
            assertEquals(1, events.count { it.name == AdEventName.POSITION })
            assertEquals(2, events.count { it.name == AdEventName.BANNER_REFRESH })
            assertEquals(3, events.count { it.name == AdEventName.IMPRESSION })
            assertEquals(3, events.count { it.name == AdEventName.PAID })
            assertEquals(3, revenues.map { it.sessionId }.distinct().size)
            revenues.forEach { payload ->
                assertEquals(0L, payload.valueMicros)
                assertEquals("USD", payload.currencyCode)
                val displayEvents = events.filter { it.sessionId == payload.sessionId }
                assertEquals(payload.impressionId, displayEvents.last().responseId)
                assertEquals(if (paidFirst) AdEventName.PAID else AdEventName.IMPRESSION,
                    displayEvents.first { it.name != AdEventName.BANNER_REFRESH }.name)
            }
            assertTrue(events.none { it.name == AdEventName.LOAD_REQUEST })
        }
    }

    @Test
    fun `slot end blocks presentation but known late revenue keeps original identity across new slots`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val firstSlot = slot(events, revenues, "first")
        val old = checkNotNull(firstSlot.newDisplay("same-sdk-id", "old-load"))
        old.impression()
        firstSlot.end()
        firstSlot.end()
        assertNull(firstSlot.newDisplay("late-unidentified"))
        val secondSlot = slot(events, revenues, "second")
        val current = checkNotNull(secondSlot.newDisplay("same-sdk-id", "new-load"))
        current.impression()
        old.click()
        old.refreshSucceeded()
        old.close()
        old.paid(42L, "USD", null, 1L)
        current.paid(43L, "USD", null, 2L)
        assertEquals(2, revenues.size)
        assertEquals(old.sessionId, revenues.first().sessionId)
        assertNotEquals(revenues.first().eventId, revenues.last().eventId)
        assertEquals("old-load", events.first { it.name == AdEventName.PAID }.requestId)
        assertEquals("first", events.first { it.name == AdEventName.PAID }.slotId)
        assertTrue(events.none { it.name in listOf(AdEventName.CLICK, AdEventName.DISMISS, AdEventName.BANNER_REFRESH) })
    }

    @Test
    fun `invalid or missing amount cannot consume dedup and missing identity cannot create a session`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val slot = slot(events, revenues)
        assertNull(slot.newDisplay(null))
        assertNull(slot.newDisplay(" \n"))
        val display = checkNotNull(slot.newDisplay("response"))
        display.paid(null, "USD", null, 1L)
        display.paid(-1L, "USD", null, 1L)
        display.paid(1L, null, null, 1L)
        display.paid(1L, " ", null, 1L)
        display.paid(1L, "USD", null, 0L)
        assertTrue(revenues.isEmpty())
        assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
        display.paid(0L, "USD", null, 1L)
        assertEquals(1, revenues.size)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.PAID), events.map { it.name })
    }

    @Test
    fun `throwing and reentrant outlets cannot suppress the other revenue outlet or duplicate delivery`() {
        val events = mutableListOf<AdEvent>()
        var revenueCalls = 0
        lateinit var display: BannerDisplaySession
        lateinit var slot: BannerSlot
        slot = BannerSlot(
            listener = AdEventListener {
                events += it
                if (it.name == AdEventName.PAID) {
                    slot.end()
                    display.paid(1L, "USD", null, 1L)
                }
                error("event listener")
            },
            platform = AdPlatform.ADMOB, mediationMode = AdMediationMode.ADMOB,
            position = "home_banner", adUnitId = "unit", slotId = "slot", number = 1L,
            revenueListener = AdRevenueListener { revenueCalls++; error("revenue listener") },
        )
        display = checkNotNull(slot.newDisplay("response"))
        display.impression()
        display.paid(1L, "USD", null, 1L)
        display.paid(1L, "USD", null, 1L)
        assertEquals(1, revenueCalls)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.PAID), events.map { it.name })
        val next = checkNotNull(slot(events, mutableListOf(), "next").newDisplay("next-response"))
        next.impression()
        assertEquals(2, events.count { it.name == AdEventName.IMPRESSION })
    }

    @Test
    fun `position ownership is established before reentrant host deactivation`() {
        lateinit var owned: BannerSlot
        val slot = BannerSlot(
            listener = AdEventListener { owned.end() },
            platform = AdPlatform.ADMOB, mediationMode = AdMediationMode.ADMOB,
            position = "home_banner", adUnitId = "unit", slotId = "slot", number = 1L,
            onCreated = { owned = it },
        )
        assertTrue(slot.isEnded)
        assertNull(slot.newDisplay("response"))
    }

    @Test
    fun `refresh failure preserves old display and body close ends the whole slot once`() {
        val events = mutableListOf<AdEvent>()
        val slot = slot(events, mutableListOf())
        val display = checkNotNull(slot.newDisplay("response"))
        display.impression()
        slot.refreshFailed("no_fill", "no ad")
        display.click()
        display.showFailure("must not turn an impression into failure")
        display.close()
        display.close()
        assertTrue(slot.isEnded)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.BANNER_REFRESH,
            AdEventName.CLICK, AdEventName.DISMISS), events.map { it.name })
        assertEquals("failed", events[2].result)
        assertNull(events[2].requestId)
        assertNull(events[2].responseId)
        assertNull(slot.newDisplay("after-close"))
    }

    @Test
    fun `TopOn hosted Google revenue uses only the TopOn outlet and needs original info`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val slot = slot(events, revenues, platform = AdPlatform.TOPON)
        val display = checkNotNull(slot.newDisplay("confirmed-show", adSource = "Admob"))
        display.paid(21_000L, "USD", "publisher_defined", 1L)
        assertTrue(revenues.isEmpty())
        val info = Any()
        display.paid(21_000L, "USD", "publisher_defined", 1L, info)
        display.paid(21_000L, "USD", "publisher_defined", 1L, info)
        assertEquals(1, revenues.size)
        assertSame(info, (revenues.single() as TopOnRevenuePayload).adInfo)
        assertEquals(AdPlatform.TOPON, revenues.single().platform)
        assertEquals("Admob", revenues.single().adNetwork)
        assertEquals(0.021, events.last().value!!, 0.0)
        assertTrue(events.none { it.name == AdEventName.IMPRESSION })
    }

    private fun slot(
        events: MutableList<AdEvent>,
        revenues: MutableList<AdRevenuePayload>,
        slotId: String = "slot",
        platform: AdPlatform = AdPlatform.ADMOB,
    ) = BannerSlot(
        listener = AdEventListener(events::add), platform = platform,
        mediationMode = AdMediationMode.BIDDING, position = "home_banner", adUnitId = "unit",
        slotId = slotId, number = 1L, revenueListener = AdRevenueListener(revenues::add),
    )
}
