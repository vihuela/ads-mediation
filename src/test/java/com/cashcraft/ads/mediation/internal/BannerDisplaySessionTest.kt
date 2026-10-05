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
    fun `AdMob reports one impression at paid for either callback order and each refresh`() {
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
            val impressions = events.filter { it.name == AdEventName.IMPRESSION }
            assertEquals(listOf(1L, 2L, 3L), impressions.map { it.analyticsParameters()["refresh_index"] })
            assertEquals(listOf("slot", "slot", "slot"), impressions.map { it.analyticsParameters()["ad_session_id"] })
            assertEquals("slot", events.first().analyticsParameters()["ad_session_id"])
            assertEquals(3, revenues.map { it.sessionId }.distinct().size)
            revenues.forEach { payload ->
                assertEquals(0L, payload.valueMicros)
                assertEquals("USD", payload.currencyCode)
                val displayEvents = events.filter { it.sessionId == payload.sessionId }
                assertEquals(payload.impressionId, displayEvents.last().responseId)
                assertTrue(displayEvents.all { it.position == "home_banner" })
                assertNotEquals(displayEvents.first().slotId, payload.sessionId)
                assertEquals(AdEventName.IMPRESSION,
                    displayEvents.first { it.name !in listOf(AdEventName.BANNER_REFRESH, AdEventName.POSITION) }.name)
            }
            assertTrue(events.none { it.name == AdEventName.LOAD })
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
        assertEquals("old-load", events.first { it.name == AdEventName.IMPRESSION }.requestId)
        assertEquals("first", events.first { it.name == AdEventName.IMPRESSION }.slotId)
        assertTrue(events.none { it.name in listOf(AdEventName.CLICK, AdEventName.DISMISS, AdEventName.BANNER_REFRESH) })
        val impressions = events.filter { it.name == AdEventName.IMPRESSION }
        assertEquals(listOf("first", "second"), impressions.map { it.analyticsParameters()["ad_session_id"] })
        assertEquals(listOf(1L, 1L), impressions.map { it.analyticsParameters()["refresh_index"] })
        assertEquals(2, events.count { it.name == AdEventName.POSITION })
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
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION), events.map { it.name })
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
                if (it.name == AdEventName.IMPRESSION) {
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
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION), events.map { it.name })
        val next = checkNotNull(slot(events, mutableListOf(), "next").newDisplay("next-response"))
        next.impression()
        next.paid(2L, "USD", null, 2L)
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
        display.paid(1L, "USD", null, 1L)
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
        assertEquals(21_000L, revenues.single().valueMicros)
        assertTrue(events.none { it.name == AdEventName.IMPRESSION })
    }

    @Test
    fun `宿主重载保留页面位置和刷新序号且不伪造关闭`() {
        val events = mutableListOf<AdEvent>()
        val slot = slot(events, mutableListOf())
        slot.showFailure("no_fill", "NO_FILL")
        slot.showFailure("duplicate")
        assertEquals(events.first().sessionId, events.last().sessionId)
        slot.prepareForLoad()
        val next = checkNotNull(slot.newDisplay("next-response", "next-request"))
        next.impression()
        next.paid(1L, "USD", null, 1L)
        slot.showFailure("must not fail confirmed exposure")
        slot.end()
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL,
            AdEventName.IMPRESSION), events.map { it.name })
        assertNotEquals(events.first().sessionId, next.sessionId)
        assertEquals("slot", events.last().analyticsParameters()["ad_session_id"])
        assertEquals(1L, events.last().analyticsParameters()["refresh_index"])
    }

    @Test
    fun `ending position before response fails once with original identity and no close`() {
        val events = mutableListOf<AdEvent>()
        val slot = slot(events, mutableListOf())
        slot.end("scene_inactive")
        slot.end("cancelled")
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals("scene_inactive", events.last().reason)
        assertNull(slot.newDisplay("late"))
    }

    @Test
    fun `ending pending initial or refreshed display fails once and keeps late revenue`() {
        for (refresh in listOf(false, true)) {
            val events = mutableListOf<AdEvent>()
            val revenues = mutableListOf<AdRevenuePayload>()
            val slot = slot(events, revenues)
            var pending = checkNotNull(slot.newDisplay("initial"))
            if (refresh) {
                pending.impression()
                pending = checkNotNull(slot.newDisplay("refreshed"))
                pending.refreshSucceeded()
            }
            slot.end("cancelled")
            slot.end("scene_inactive")
            pending.impression()
            pending.close()
            pending.paid(3L, "USD", null, 1L)
            pending.paid(3L, "USD", null, 2L)
            val failed = events.single { it.name == AdEventName.SHOW_FAIL }
            assertEquals(pending.sessionId, failed.sessionId)
            assertEquals("cancelled", failed.reason)
            assertTrue(events.none { it.name == AdEventName.DISMISS })
            assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
            assertEquals(pending.sessionId, revenues.single().sessionId)
            assertEquals(pending.responseId, revenues.single().impressionId)
        }
    }

    @Test
    fun `replacing a pending response cancels old opportunity and retains old late payment`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val slot = slot(events, revenues)
        val old = checkNotNull(slot.newDisplay("old"))
        val next = checkNotNull(slot.newDisplay("next"))
        old.impression()
        next.impression()
        slot.end()
        old.paid(1L, "USD", null, 1L)
        val failed = events.single { it.name == AdEventName.SHOW_FAIL }
        assertEquals(old.sessionId, failed.sessionId)
        assertEquals("cancelled", failed.reason)
        assertEquals(old.sessionId, events.single { it.name == AdEventName.IMPRESSION }.sessionId)
        assertEquals(old.sessionId, revenues.single().sessionId)
        assertTrue(events.none { it.name == AdEventName.DISMISS })
    }

    @Test
    fun `ending exposed initial or refreshed display creates neither failure nor close`() {
        for (refresh in listOf(false, true)) {
            val events = mutableListOf<AdEvent>()
            val revenues = mutableListOf<AdRevenuePayload>()
            val slot = slot(events, revenues)
            val first = checkNotNull(slot.newDisplay("initial"))
            first.impression()
            val current = if (refresh) checkNotNull(slot.newDisplay("refreshed")) else first
            current.impression()
            slot.end()
            slot.end()
            current.paid(2L, "USD", null, 1L)
            assertTrue(events.none { it.name == AdEventName.SHOW_FAIL || it.name == AdEventName.DISMISS })
            assertEquals(current.sessionId, revenues.single().sessionId)
        }
    }

    @Test
    fun `刷新回调重入结束页面只终结当前广告且不重报位置`() {
        val events = mutableListOf<AdEvent>()
        lateinit var owned: BannerSlot
        val slot = BannerSlot(
            listener = AdEventListener { event ->
                events += event
                if (event.name == AdEventName.BANNER_REFRESH) {
                    owned.end("scene_inactive")
                }
            },
            platform = AdPlatform.ADMOB, mediationMode = AdMediationMode.ADMOB,
            position = "home_banner", adUnitId = "unit", slotId = "slot", number = 1L,
            onCreated = { owned = it },
        )
        checkNotNull(slot.newDisplay("first")).impression()
        val pending = checkNotNull(slot.newDisplay("refreshed"))
        pending.refreshSucceeded()
        pending.impression()
        assertEquals(1, events.count { it.name == AdEventName.POSITION })
        val failed = events.single { it.name == AdEventName.SHOW_FAIL }
        assertEquals(pending.sessionId, failed.sessionId)
        assertEquals("scene_inactive", failed.reason)
        assertEquals(0, events.count { it.name == AdEventName.IMPRESSION })
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
