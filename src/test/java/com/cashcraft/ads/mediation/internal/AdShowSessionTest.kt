package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.admob.showFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdShowSessionTest {
    @Test
    fun `load session reports one correlated terminal result with latency`() {
        val events = mutableListOf<AdEvent>()
        val loadSession = AdLoadSession(
            listener = AdEventListener(events::add),
            platform = AdPlatform.ADMOB,
            mediationMode = AdMediationMode.ADMOB,
            format = AdFormat.REWARDED,
            position = "preload_rewarded",
            adUnitId = "test-unit",
            sessionId = "load-session-1",
            requestId = "load-request-1",
            number = 3L,
            bufferSize = 2,
            startedAtMillis = 100L,
            clock = AdLoadClock { 175L },
        )

        loadSession.request()
        loadSession.loaded("Google", "response-1")
        loadSession.failed("error", "LATE", "late failure", null)

        assertEquals(
            listOf(AdEventName.LOAD_REQUEST, AdEventName.LOAD_RESULT),
            events.map(AdEvent::name),
        )
        assertEquals("load-request-1", events.last().requestId)
        assertEquals("filled", events.last().result)
        assertEquals(75L, events.last().latencyMillis)
        assertEquals(2, events.last().bufferSize)
        assertEquals("Google", events.last().adSource)
        assertEquals("admob", events.last().analyticsParameters()["mediation_mode"])
        assertFalse(events.last().analyticsParameters().containsKey("consent_status"))
        assertFalse(events.last().analyticsParameters().containsKey("can_request_ads"))
        assertFalse(events.last().analyticsParameters().containsKey("privacy_options_required"))
    }

    @Test
    fun `impression is the only terminal event for an exposed position`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)

        session.impression("Google", "response")
        session.showFailure("late_failure")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.IMPRESSION),
            events.map(AdEvent::name),
        )
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals("game_tool_refresh", events.last().position)
    }

    @Test
    fun `show error terminates a position when no impression occurred`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)

        session.showFailure("no_preloaded_ad", "NO_FILL")
        session.showFailure("duplicate")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL),
            events.map(AdEvent::name),
        )
        assertEquals("no_preloaded_ad", events.last().reason)
        assertEquals("NO_FILL", events.last().errorCode)
    }

    @Test
    fun `dismiss before impression is normalized to one show failure`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)

        val result = session.dismissedResult()

        assertEquals(AdShowResult.Failed("dismissed_before_impression"), result)
        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL),
            events.map(AdEvent::name),
        )
        assertEquals("dismissed_before_impression", events.last().reason)
    }

    @Test
    fun `dismiss after impression keeps successful result without another terminal event`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        session.impression("Google", "response")

        val result = session.dismissedResult()

        assertEquals(AdShowResult.Dismissed, result)
        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.IMPRESSION),
            events.map(AdEvent::name),
        )
    }

    @Test
    fun `app open container error reports one show failure with stable details`() {
        val events = mutableListOf<AdEvent>()
        val session = session(
            events = events,
            platform = AdPlatform.TOPON,
            mediationMode = AdMediationMode.BIDDING,
            format = AdFormat.APP_OPEN,
            position = "launcher_minus_one",
        )

        session.showFailure("app_open_container_unavailable", "container_not_found")
        session.showFailure("duplicate")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL),
            events.map(AdEvent::name),
        )
        assertEquals("launcher_minus_one", events.last().position)
        assertEquals("app_open_container_unavailable", events.last().reason)
        assertEquals("container_not_found", events.last().errorCode)
    }

    @Test
    fun `bid result follows position and shares its session`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events, mediationMode = AdMediationMode.BIDDING)

        session.bidResult(
            AdBidEventData(
                winnerPlatform = AdPlatform.TOPON,
                admobAvailable = true,
                topOnAvailable = true,
                admobValue = 0.001,
                topOnValue = 0.002,
                winningValue = 0.002,
                admobAdUnitId = "admob-unit",
                topOnAdUnitId = "topon-unit",
            ),
        )
        session.impression("Mintegral", "response")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.BID_RESULT, AdEventName.IMPRESSION),
            events.map(AdEvent::name),
        )
        val bid = events[1]
        assertEquals(events.first().sessionId, bid.sessionId)
        assertEquals("topon", bid.analyticsParameters()["winner_platform"])
        assertEquals(true, bid.analyticsParameters()["admob_available"])
        assertEquals(true, bid.analyticsParameters()["admob_price_available"])
        assertEquals(true, bid.analyticsParameters()["topon_price_available"])
        assertEquals(0.002, bid.analyticsParameters()["winning_value"])
        assertEquals("bidding", bid.analyticsParameters()["mediation_mode"])
    }

    @Test
    fun `no candidate bid result still precedes show failure`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events, mediationMode = AdMediationMode.BIDDING)

        session.bidResult(
            AdBidEventData(
                winnerPlatform = null,
                admobAvailable = false,
                topOnAvailable = false,
                admobValue = null,
                topOnValue = null,
                winningValue = null,
                admobAdUnitId = "admob-unit",
                topOnAdUnitId = "topon-unit",
            ),
        )
        session.showFailure("no_preloaded_ad")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.BID_RESULT, AdEventName.SHOW_FAIL),
            events.map(AdEvent::name),
        )
        assertEquals("no_candidate", events[1].result)
        assertEquals("none", events[1].analyticsParameters()["winner_platform"])
        assertEquals(false, events[1].analyticsParameters()["admob_price_available"])
        assertEquals(false, events[1].analyticsParameters()["topon_price_available"])
    }

    @Test
    fun `bid result omits unavailable prices instead of reporting zero`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events, mediationMode = AdMediationMode.BIDDING)

        session.bidResult(
            AdBidEventData(
                winnerPlatform = AdPlatform.TOPON,
                admobAvailable = true,
                topOnAvailable = true,
                admobValue = null,
                topOnValue = 0.0,
                winningValue = 0.0,
                admobAdUnitId = "admob-unit",
                topOnAdUnitId = "topon-unit",
            ),
        )

        val parameters = events.last().analyticsParameters()
        assertEquals(false, parameters["admob_price_available"])
        assertEquals(true, parameters["topon_price_available"])
        assertFalse(parameters.containsKey("admob_value"))
        assertEquals(0.0, parameters["topon_value"])
        assertEquals(0.0, parameters["winning_value"])
    }

    @Test
    fun `winning auto app open bid still closes with failure when its window expires`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events, mediationMode = AdMediationMode.BIDDING)

        session.bidResult(
            AdBidEventData(
                winnerPlatform = AdPlatform.ADMOB,
                admobAvailable = true,
                topOnAvailable = false,
                admobValue = 0.001,
                topOnValue = 0.0,
                winningValue = 0.001,
                admobAdUnitId = "admob-unit",
                topOnAdUnitId = "topon-unit",
            ),
        )
        session.showFailure("app_open_window_expired")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.BID_RESULT, AdEventName.SHOW_FAIL),
            events.map(AdEvent::name),
        )
        assertEquals("won", events[1].result)
        assertEquals("app_open_window_expired", events.last().reason)
        assertEquals(1, events.map(AdEvent::sessionId).distinct().size)
    }

    @Test
    fun `initialization failure follows position with show failure`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)

        session.showFailure(AdMobState.FAILED.showFailureReason())

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL),
            events.map(AdEvent::name),
        )
        assertEquals("sdk_initialization_failed", events.last().reason)
    }

    @Test
    fun `standalone providers keep the same event contract with their own mode`() {
        val admobEvents = mutableListOf<AdEvent>()
        val topOnEvents = mutableListOf<AdEvent>()

        session(admobEvents).impression("Google", "admob-response")
        session(
            events = topOnEvents,
            platform = AdPlatform.TOPON,
            mediationMode = AdMediationMode.TOPON,
        ).impression("Mintegral", "topon-response")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.IMPRESSION),
            admobEvents.map(AdEvent::name),
        )
        assertEquals(admobEvents.map(AdEvent::name), topOnEvents.map(AdEvent::name))
        assertEquals("admob", admobEvents.last().analyticsParameters()["mediation_mode"])
        assertEquals("topon", topOnEvents.last().analyticsParameters()["mediation_mode"])
        assertEquals("admob", admobEvents.last().analyticsParameters()["ad_platform"])
        assertEquals("topon", topOnEvents.last().analyticsParameters()["ad_platform"])
    }

    private fun session(
        events: MutableList<AdEvent>,
        platform: AdPlatform = AdPlatform.ADMOB,
        mediationMode: AdMediationMode = AdMediationMode.ADMOB,
        format: AdFormat = AdFormat.REWARDED,
        position: String = "game_tool_refresh",
    ) = AdShowSession(
        listener = AdEventListener(events::add),
        platform = platform,
        mediationMode = mediationMode,
        format = format,
        position = position.normalizedAdPosition(),
        adUnitId = "test-unit",
        sessionId = "session-1",
        number = 7L,
    )
}
