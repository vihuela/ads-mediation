package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BannerEventTest {
    @Test
    fun `slot enters once while refresh and displays retain separate identities`() {
        val events = mutableListOf<AdEvent>()
        val slot = BannerSlot(
            listener = AdEventListener(events::add),
            platform = AdPlatform.TOPON,
            mediationMode = AdMediationMode.TOPON,
            position = "home".withAdType(AdFormat.BANNER),
            adUnitId = "banner-unit",
            slotId = "slot-1",
            number = 4L,
        )

        val first = checkNotNull(slot.newDisplay("response-1", requestId = "request-1"))
        val second = checkNotNull(slot.newDisplay("response-2"))
        first.impression()
        first.impression()
        second.refreshSucceeded()
        second.impression()

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.BANNER_REFRESH, AdEventName.IMPRESSION),
            events.map(AdEvent::name),
        )
        assertEquals(listOf("slot-1", first.sessionId, second.sessionId, second.sessionId), events.map(AdEvent::sessionId))
        assertEquals(listOf("slot-1", "slot-1", "slot-1", "slot-1"), events.map(AdEvent::slotId))
        assertEquals(listOf(4L, 4L, 4L, 4L), events.map(AdEvent::number))
        assertEquals("home_banner", events.last().position)
        assertEquals("request-1", events[1].requestId)
        assertEquals(null, events.last().requestId)
    }

    @Test
    fun `banner load has one result with real position and no buffer size`() {
        val events = mutableListOf<AdEvent>()
        val load = AdLoadSession(
            listener = AdEventListener(events::add),
            platform = AdPlatform.ADMOB,
            mediationMode = AdMediationMode.ADMOB,
            format = AdFormat.BANNER,
            position = "home_banner",
            adUnitId = "banner-unit",
            sessionId = "request-1",
            requestId = "request-1",
            number = 2L,
            bufferSize = null,
            startedAtMillis = 100L,
            clock = AdLoadClock { 125L },
            slotId = "slot-1",
        )

        load.request()
        load.failed("no_fill", "NO_FILL", "empty", null)
        load.loaded("late", "response-1")

        assertEquals(listOf(AdEventName.LOAD_REQUEST, AdEventName.LOAD_RESULT), events.map(AdEvent::name))
        assertEquals("no_fill", events.last().result)
        assertEquals(25L, events.last().latencyMillis)
        events.forEach { event ->
            assertEquals("home_banner", event.position)
            assertEquals("slot-1", event.slotId)
            assertEquals("request-1", event.requestId)
            assertFalse(event.analyticsParameters().containsKey("buffer_size"))
        }
    }
}
