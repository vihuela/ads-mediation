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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AdShowSessionTest {
    @Test fun `provider handoff flushes pending bid once without repeating the opportunity`() {
        for (platform in AdPlatform.entries) {
            for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
                val events = mutableListOf<AdEvent>()
                val attempt = FullScreenShowAttempt(isWaitingOpportunity = true)
                val opportunity = AdShowSession(AdEventListener(events::add), platform,
                    AdMediationMode.BIDDING, format, "position", "unit", "opportunity-id", 1,
                    attempt = attempt)
                assertTrue(opportunity.admit())
                val provider = AdShowSession(AdEventListener(events::add), platform,
                    AdMediationMode.BIDDING, format, "position", "unit", "provider-id", 2,
                    attempt = attempt)
                provider.bidResult(AdBidEventData(platform, true, true, 0.001, 0.002,
                    if (platform == AdPlatform.ADMOB) 0.001 else 0.002, "admob-unit", "topon-unit", adSource = "Pangle"))
                assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
                repeat(2) { assertTrue(provider.admit()) }
                provider.impression("source", "response", 1000, "USD", "precise")
                provider.revenue(valueMicros = 1000, currency = "USD")

                assertEquals("$format/$platform",
                    listOf(AdEventName.POSITION, AdEventName.BID_RESULT, AdEventName.IMPRESSION),
                    events.map { it.name })
                assertEquals(setOf("opportunity-id"), events.map { it.sessionId }.toSet())
                val bid = events.single { it.name == AdEventName.BID_RESULT }.analyticsParameters()
                assertEquals("opportunity-id", bid["ad_session_id"])
                assertEquals(format.analyticsValue, bid["ad_type"])
                assertEquals(platform.analyticsValue, bid["winner_platform"])
                assertEquals("Pangle", bid["ad_source"])
            }
        }
    }

    @Test fun `full screen impression follows EasyLoanCalc callback matrix in either order`() {
        for (platform in AdPlatform.entries) for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
            for (paidFirst in listOf(false, true)) {
                val events = mutableListOf<AdEvent>()
                val session = AdShowSession(AdEventListener(events::add), platform, AdMediationMode.BIDDING,
                    format, "original-position", "unit", "original-session", 1)
                session.admit()
                val onShow = platform == AdPlatform.TOPON && format == AdFormat.APP_OPEN
                var confirmed = 0
                session.onImpressionConfirmed = { confirmed++ }
                if (paidFirst) {
                    assertTrue(session.revenue(valueMicros = 2500, currency = "USD", precisionType = "PRECISE"))
                    assertFalse(session.hasImpression)
                    assertEquals(if (onShow) 0 else 1, events.count { it.name == AdEventName.IMPRESSION })
                }
                repeat(2) { session.impression("network", "response", 1250, "USD", "ESTIMATED") }
                assertTrue(session.hasImpression)
                assertEquals(1, confirmed)
                assertEquals(if (onShow || paidFirst) 1 else 0, events.count { it.name == AdEventName.IMPRESSION })
                session.emit(AdEventName.DISMISS)
                session.attempt.complete()
                if (!paidFirst) assertTrue(session.revenue(valueMicros = 2500, currency = "USD", precisionType = "PRECISE"))
                assertFalse(session.revenue(valueMicros = 2500, currency = "USD"))
                val impression = events.single { it.name == AdEventName.IMPRESSION }
                assertEquals(if (onShow) 1250L else 2500L, impression.valueMicros)
                assertEquals(if (onShow) "ESTIMATED" else "PRECISE", impression.analyticsParameters()["precision_type"])
                assertEquals("original-session", impression.sessionId)
                assertEquals("original-position", impression.position)
                assertTrue(events.none { it.name == AdEventName.SHOW_FAIL })
            }
        }
    }

    @Test fun `TopOn splash still reports exposure when revenue is unknown and never fills fake zero`() {
        val events = mutableListOf<AdEvent>()
        val session = AdShowSession(AdEventListener(events::add), AdPlatform.TOPON, AdMediationMode.TOPON,
            AdFormat.APP_OPEN, "open", "unit", "session", 1)
        session.admit()
        session.impression("network", "response")
        assertFalse(events.single { it.name == AdEventName.IMPRESSION }.analyticsParameters().containsKey("revenue_amount"))
        assertTrue(session.revenue(valueMicros = 0, currency = "USD"))
        assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `native show session announces only after qualification`() {
        val events = mutableListOf<AdEvent>()
        val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB,
            AdMediationMode.ADMOB, AdFormat.NATIVE, "native_position", "unit", "legacy-id", 1)
        assertTrue(events.isEmpty())
        session.admit()
        assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
        session.impression("source", "response")
        session.revenue(valueMicros = 1250, currency = "USD")
        session.emit(AdEventName.DISMISS)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.DISMISS),
            events.map { it.name })
        assertEquals(setOf("legacy-id"), events.map { it.sessionId }.toSet())
    }

    @Test fun `valid ILRD may precede impression and stays on the same session`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        session.revenue(valueMicros = 1250, currency = "USD")
        session.impression("source", "response")
        session.revenue(valueMicros = 1250, currency = "USD")
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION), events.map { it.name })
        assertEquals(setOf("session-1"), events.map { it.sessionId }.toSet())
    }

    @Test fun `constructing a full screen session does not publish an opportunity`() {
        for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
            val events = mutableListOf<AdEvent>()
            val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB,
                AdMediationMode.ADMOB, format, "  original position  ", "unit", "internal-id", 1)
            assertEquals(emptyList<AdEvent>(), events)
            session.showFailure("no_preloaded_ad")
            assertTrue("Failure cannot fabricate an unqualified opportunity", events.isEmpty())
            session.admit()
            session.showFailure("no_preloaded_ad")
            session.showFailure("duplicate")
            assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
            assertEquals("  original position  ", events.first().position)
            assertEquals(events.first().sessionId, events.last().sessionId)
        }
    }

    @Test fun `real paid reward and close callbacks are once per original exposed session`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        session.impression("source", "response")
        session.emit(AdEventName.DISMISS)
        session.emit(AdEventName.DISMISS)
        session.attempt.complete()
        repeat(2) {
            session.emit(AdEventName.REWARD)
            session.revenue(valueMicros = 0, currency = "USD")
        }
        session.showFailure("late_sdk_error")
        assertEquals(listOf(AdEventName.POSITION, AdEventName.DISMISS,
            AdEventName.REWARD, AdEventName.IMPRESSION), events.map { it.name })
        assertEquals(setOf("session-1"), events.map { it.sessionId }.toSet())
    }

    @Test fun `unexposed close produces failure and invalid paid does not consume deduplication`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        session.emit(AdEventName.DISMISS)
        session.emit(AdEventName.DISMISS)
        session.emit(AdEventName.REWARD)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        session.revenue(valueMicros = -1, currency = "USD")
        session.revenue(valueMicros = 1, currency = "")
        session.revenue(valueMicros = 1, currency = "USD")
        assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `load failure uses structured result and never SDK error prose`() {
        val events = mutableListOf<AdEvent>()
        val load = AdLoadSession(AdEventListener(events::add), AdPlatform.ADMOB,
            AdMediationMode.ADMOB, AdFormat.INTERSTITIAL, "preload", "unit", "load", "load", 1,
            1, 0, AdLoadClock { 10 })
        load.request()
        load.failed("no_fill", "NO_FILL", "SDK error body", null)
        load.loaded(null, null)
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map { it.name })
        assertEquals("no_fill", events.last().reason)
    }

    @Test
    fun `scene impression callback runs once only after real impression`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        var impressions = 0
        session.onImpressionConfirmed = { impressions++ }
        session.attempt.committed()
        assertEquals(0, impressions)

        session.impression("Google", "response")
        session.impression("Google", "duplicate")
        assertEquals(1, impressions)
        assertEquals(listOf(AdEventName.POSITION), events.map(AdEvent::name))
        assertEquals(null, session.onImpressionConfirmed)
    }

    @Test
    fun `failed show never confirms scene impression`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        var impressions = 0
        session.onImpressionConfirmed = { impressions++ }
        session.showFailure("sdk_show_failed")
        session.impression("Google", "late")
        assertEquals(0, impressions)
        assertEquals(null, session.onImpressionConfirmed)
    }

    @Test
    fun `scene log callback failure does not suppress impression event`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)
        session.onImpressionConfirmed = { error("logger failed") }
        session.impression("Google", "response")
        session.revenue(valueMicros = 1250, currency = "USD")
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION), events.map(AdEvent::name))
    }

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
            listOf(AdEventName.LOAD, AdEventName.LOADED),
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
    fun `physical exposure completes the position without fabricating revenue or a failure`() {
        val events = mutableListOf<AdEvent>()
        val session = session(events)

        session.impression("Google", "response")
        session.showFailure("late_failure")

        assertEquals(
            listOf(AdEventName.POSITION),
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
            listOf(AdEventName.POSITION),
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
        session.revenue(valueMicros = 1250, currency = "USD")

        assertEquals(
            listOf(AdEventName.POSITION, AdEventName.BID_RESULT, AdEventName.IMPRESSION),
            events.map(AdEvent::name),
        )
        val bid = events[1]
        assertEquals(events.first().sessionId, bid.sessionId)
        assertEquals(AdPlatform.TOPON, bid.winnerPlatform)
        assertEquals(true, bid.admobAvailable)
        assertEquals(true, bid.admobPriceAvailable)
        assertEquals(true, bid.topOnPriceAvailable)
        assertEquals(0.002, bid.winningValue)
        assertEquals(AdMediationMode.BIDDING, bid.mediationMode)
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
        assertEquals(null, events[1].winnerPlatform)
        assertEquals(false, events[1].admobPriceAvailable)
        assertEquals(false, events[1].topOnPriceAvailable)
    }

    @Test
    fun `bid result reports unknow for unavailable prices and preserves real zero`() {
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

        val bid = events.last()
        assertEquals(false, bid.admobPriceAvailable)
        assertEquals(true, bid.topOnPriceAvailable)
        assertEquals(null, bid.admobValue)
        assertEquals(0.0, bid.topOnValue)
        assertEquals(0.0, bid.winningValue)
        val properties = bid.analyticsParameters()
        assertEquals("won", properties["result"])
        assertEquals("topon", properties["winner_platform"])
        assertEquals("topon", properties["ad_platform"])
        assertEquals("topon-unit", properties["ad_unit_id"])
        assertEquals(false, properties["admob_price_available"])
        assertEquals("unknow", properties["admob_value"])
        assertEquals(true, properties["topon_price_available"])
        assertEquals(0.0, properties["topon_value"])
        assertEquals(0.0, properties["winning_value"])
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

        session(admobEvents).apply {
            impression("Google", "admob-response")
            revenue(valueMicros = 1250, currency = "USD")
        }
        session(
            events = topOnEvents,
            platform = AdPlatform.TOPON,
            mediationMode = AdMediationMode.TOPON,
        ).apply {
            impression("Mintegral", "topon-response")
            revenue(valueMicros = 1250, currency = "USD")
        }

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
    ).also { it.admit() }
}
