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
    fun `同一页面只报一次位置且每条广告携带独立序号`() {
        val events = mutableListOf<AdEvent>()
        val slot = BannerSlot(
            listener = AdEventListener(events::add),
            platform = AdPlatform.TOPON,
            mediationMode = AdMediationMode.TOPON,
            position = "BA_Home_bottom".normalizedAdPosition(),
            adUnitId = "banner-unit",
            slotId = "slot-1",
            number = 4L,
        )

        val first = checkNotNull(slot.newDisplay("response-1", requestId = "request-1"))
        first.impression()
        first.impression()
        val second = checkNotNull(slot.newDisplay("response-2"))
        second.refreshSucceeded()
        second.impression()

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.BANNER_REFRESH, AdEventName.IMPRESSION),
            events.map(AdEvent::name),
        )
        assertEquals(listOf(first.sessionId, first.sessionId, second.sessionId, second.sessionId), events.map(AdEvent::sessionId))
        assertEquals(List(4) { "slot-1" }, events.map(AdEvent::slotId))
        assertEquals(List(4) { 4L }, events.map(AdEvent::number))
        val impressions = events.filter { it.name == AdEventName.IMPRESSION }
        assertEquals(listOf(1L, 2L), impressions.map { it.analyticsParameters()["refresh_index"] })
        assertEquals(listOf("slot-1", "slot-1"), impressions.map { it.analyticsParameters()["ad_session_id"] })
        assertEquals("BA_Home_bottom", events.last().position)
        assertEquals("request-1", events[1].requestId)
        assertEquals(null, events.last().requestId)
    }

    @Test
    fun `并行 Banner 与宿主重载的页面身份及广告序号独立`() {
        val events = mutableListOf<AdEvent>()
        fun slot(id: String) = BannerSlot(
            AdEventListener(events::add), AdPlatform.TOPON, AdMediationMode.TOPON,
            "same-position", "same-unit", id, 1L,
        )
        val first = slot("page-a")
        val second = slot("page-b")
        checkNotNull(first.newDisplay("response-a")).impression()
        checkNotNull(second.newDisplay("response-b")).impression()
        first.prepareForLoad()
        checkNotNull(first.newDisplay("response-a-next")).impression()
        val positions = events.filter { it.name == AdEventName.POSITION }
        assertEquals(listOf("page-a", "page-b"), positions.map { it.analyticsParameters()["ad_session_id"] })
        val impressions = events.filter { it.name == AdEventName.IMPRESSION }
        assertEquals(listOf("page-a", "page-b", "page-a"), impressions.map { it.analyticsParameters()["ad_session_id"] })
        assertEquals(listOf(1L, 1L, 2L), impressions.map { it.analyticsParameters()["refresh_index"] })
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

        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map(AdEvent::name))
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
