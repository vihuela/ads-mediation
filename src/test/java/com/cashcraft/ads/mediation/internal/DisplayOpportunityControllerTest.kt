package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRewardResult
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.AdsState
import com.cashcraft.ads.mediation.displayOpportunityFailureReason
import com.cashcraft.ads.mediation.admob.RetainedAd
import com.cashcraft.ads.mediation.internal.topon.acceptsTopOnCallback
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DisplayOpportunityControllerTest {
    private val harnesses = mutableListOf<Harness>()
    private fun harness(timeout: Long = 500, queuedFor: Long = 0): Harness =
        Harness(timeout, queuedFor).also(harnesses::add)

    @After fun cleanup() {
        harnesses.forEach { it.controller.cancel(); it.controller.attempt.complete() }
    }

    @Test fun `deadline includes main queue time and a late timer cannot show`() {
        val queued = harness(queuedFor = 500)
        queued.controller.start()
        assertEquals("wait_timeout", queued.reason)
        assertEquals(0, queued.ensures)

        val h = harness()
        h.controller.start()
        assertEquals(100, h.scheduledDelay)
        h.now = 499
        h.tick()
        assertEquals(1, h.scheduledDelay)
        h.now = 510
        h.ready = true
        h.tick()
        assertEquals("wait_timeout", h.reason)
        assertEquals(0, h.shows)
        assertEquals(1, h.results.size)
        assertNull(h.scheduled)
        assertTrue(h.events.isEmpty())
    }

    @Test fun `scene predicate cannot extend deadline or escape cleanup by throwing`() {
        val slow = harness()
        slow.scene = { slow.now = 500; true }
        slow.controller.start()
        assertEquals("wait_timeout", slow.reason)
        assertEquals(0, slow.shows)

        val broken = harness()
        broken.scene = { error("host predicate") }
        broken.controller.start()
        assertEquals("scene_validation_failed", broken.reason)
        assertEquals(1, broken.cleanups)
    }

    @Test fun `invalid parameters and permanent failures never load or create show events`() {
        for (timeout in listOf(0L, -1L)) {
            val h = harness(timeout)
            h.controller.start()
            assertEquals("invalid_timeout", h.reason)
            assertEquals(0, h.ensures)
        }
        for (reason in listOf("sdk_not_initialized", "consent_not_obtained", "sdk_initialization_failed")) {
            val h = harness()
            h.precondition = reason
            h.controller.start()
            assertEquals(reason, h.reason)
            assertEquals(0, h.ensures)
            assertTrue(h.events.isEmpty())
            assertNull(h.results.single().sessionId)
            assertFalse(h.results.single().rewardEarned)
        }
    }

    @Test fun `initialization never resets deadline and scene is checked while initializing`() {
        val h = harness()
        h.precondition = "sdk_initializing"
        h.controller.start()
        assertEquals(0, h.ensures)
        h.now = 400
        h.precondition = null
        h.tick()
        assertEquals(1, h.ensures)
        h.now = 500
        h.ready = true
        h.tick()
        assertEquals("wait_timeout", h.reason)

        val invalid = harness()
        invalid.precondition = "sdk_initializing"
        invalid.scene = { false }
        invalid.controller.start()
        assertEquals("scene_invalid", invalid.reason)
    }

    @Test fun `ready bidder bypasses aggregate initialization but not permission or terminal failures`() {
        val h = harness()
        h.precondition = displayOpportunityFailureReason("sdk_initializing", AdsState.INITIALIZING, false)
        h.controller.start()
        assertEquals(0, h.sdkCalls)
        assertEquals(0, h.ensures)

        h.now = 400
        h.precondition = displayOpportunityFailureReason("sdk_initializing", AdsState.INITIALIZING, true)
        h.ready = true
        h.tick()
        assertEquals(1, h.sdkCalls)
        assertNull(h.scheduled)
        h.finishSdk()

        for (failure in listOf("consent_not_obtained", "sdk_not_initialized", "sdk_initialization_failed")) {
            assertEquals(failure, displayOpportunityFailureReason(failure, AdsState.INITIALIZING, true))
        }
        assertEquals(
            "sdk_not_initialized",
            displayOpportunityFailureReason(null, AdsState.NOT_INITIALIZED, true),
        )
        assertEquals(
            "sdk_initialization_failed",
            displayOpportunityFailureReason(null, AdsState.FAILED, true),
        )

        val expired = harness()
        expired.precondition = displayOpportunityFailureReason("sdk_initializing", AdsState.INITIALIZING, false)
        expired.controller.start()
        expired.now = 500
        expired.precondition = displayOpportunityFailureReason("sdk_initializing", AdsState.INITIALIZING, true)
        expired.ready = true
        expired.tick()
        assertEquals("wait_timeout", expired.reason)
        assertEquals(0, expired.sdkCalls)
    }

    @Test fun `initialization failure or permission revocation ends an existing wait`() {
        val initializing = harness()
        initializing.precondition = "sdk_initializing"
        initializing.controller.start()
        initializing.precondition = "sdk_initialization_failed"
        initializing.tick()
        assertEquals("sdk_initialization_failed", initializing.reason)
        assertEquals(0, initializing.ensures)
        val revoked = harness()
        revoked.controller.start()
        revoked.precondition = "consent_not_obtained"
        revoked.ready = true
        revoked.tick()
        assertEquals("consent_not_obtained", revoked.reason)
        assertEquals(0, revoked.shows)
        assertEquals(1, revoked.ensures)
    }

    @Test fun `first resume and window can wait but a paused host cannot revive`() {
        val h = harness()
        h.hostFailure = "activity_awaiting_first_resume"
        h.ready = true
        h.controller.start()
        assertEquals(0, h.shows)
        h.hostFailure = "activity_window_not_focused"
        h.tick()
        assertEquals(0, h.shows)
        h.hostFailure = null
        h.tick()
        assertEquals(1, h.shows)
        h.finishSdk()

        for (reason in listOf("activity_not_resumed", "activity_not_available", "app_not_in_foreground")) {
            val old = harness()
            old.controller.start()
            old.controller.cancel(reason)
            old.hostFailure = null
            old.ready = true
            old.tick()
            old.controller.start()
            assertEquals(reason, old.reason)
            assertEquals(0, old.shows)
        }
    }

    @Test fun `A timeout leaves shared load alive and B joins without starting L2`() {
        var loading = false
        var cached = false
        var starts = 0
        val ensure: () -> Unit = { if (!loading && !cached) { loading = true; starts++ } }
        val a = harness()
        a.ensure = ensure
        a.controller.start()
        repeat(3) { a.now += 100; a.tick() }
        assertEquals(1, a.ensures)
        a.now = 500
        a.tick()
        assertTrue(loading)
        assertEquals(1, starts)
        val b = harness()
        b.ensure = ensure
        b.controller.start()
        assertEquals(1, starts)
        cached = true
        loading = false
        b.ready = cached
        b.tick()
        assertEquals(1, b.shows)
        assertEquals(0, a.shows)
        b.finishSdk()
        // A late load with no opportunity merely populates this shared cache.
        a.ready = true
        a.tick()
        assertEquals(0, a.shows)
    }

    @Test fun `waiting and SDK showing use same owner and stale completion cannot release B`() {
        val a = harness()
        a.controller.start()
        assertFalse(FullScreenShowGate.isAnyAdShowing)
        assertNull(FullScreenShowGate.reserve(a.controller.attempt))
        val rejected = harness()
        rejected.controller.start()
        assertEquals("request_in_progress", rejected.reason)
        assertEquals(0, rejected.ensures)
        a.controller.cancel()
        val b = harness()
        b.ready = true
        b.controller.start()
        assertTrue(FullScreenShowGate.isAnyAdShowing)
        a.controller.attempt.complete()
        assertTrue(FullScreenShowGate.isAnyAdShowing)
        val rejectedWhileShowing = harness()
        rejectedWhileShowing.controller.start()
        assertEquals("another_full_screen_ad_showing", rejectedWhileShowing.reason)
        b.finishSdk()
        assertFalse(FullScreenShowGate.isAnyAdShowing)
    }

    @Test fun `cancellation cleans resources before callback including reentrant cancellation`() {
        val a = harness()
        var resource = true
        var next: Harness? = null
        a.controller.attempt.onAborted = {
            resource = false
            a.controller.cancel("scene_invalid")
        }
        a.onResult = {
            assertFalse(resource)
            assertNull(a.scheduled)
            assertEquals(1, a.cleanups)
            next = harness().also { it.controller.start() }
            error("host callback must not break cleanup")
        }
        a.controller.start()
        a.controller.cancel()
        a.controller.cancel()
        a.ready = true
        a.tick()
        assertEquals("opportunity_cancelled", a.reason)
        assertEquals(1, a.results.size)
        assertEquals(1, next!!.ensures)
    }

    @Test fun `position and bid listener cancellation prevents SDK handoff with correlated failure`() {
        for (eventName in listOf(AdEventName.POSITION, AdEventName.BID_RESULT)) {
            val h = harness()
            h.ready = true
            h.onEvent = { if (it.name == eventName) h.controller.cancel() }
            h.controller.start()
            assertEquals(0, h.sdkCalls)
            assertEquals("opportunity_cancelled", h.reason)
            assertEquals("show-session", h.results.single().sessionId)
            assertEquals(1, h.events.count { it.name == AdEventName.POSITION })
            assertEquals(1, h.events.count { it.name == AdEventName.SHOW_FAIL })
            assertEquals(1, h.results.size)
        }
    }

    @Test fun `deadline and consent are checked again after candidate preparation`() {
        val expired = harness()
        expired.ready = true
        expired.beforeCommit = { expired.now = 500 }
        expired.controller.start()
        assertEquals("wait_timeout", expired.reason)
        assertEquals(0, expired.sdkCalls)
        val revoked = harness()
        revoked.ready = true
        revoked.beforeCommit = { revoked.precondition = "consent_not_obtained" }
        revoked.controller.start()
        assertEquals("consent_not_obtained", revoked.reason)
        assertEquals(0, revoked.sdkCalls)
    }

    @Test fun `SDK handoff ends waiting and preserves close reward and one final result`() {
        val h = harness()
        h.ready = true
        h.controller.start()
        assertEquals(1, h.sdkCalls)
        assertNull(h.scheduled)
        assertEquals(1, h.cleanups)
        h.now = 10000
        h.controller.cancel("activity_not_resumed")
        h.controller.cancel()
        h.tick()
        assertTrue(h.results.isEmpty())
        assertTrue(FullScreenShowGate.isAnyAdShowing)
        h.finishSdk(reward = true)
        h.finishSdk()
        assertEquals(listOf(AdRewardResult(true, AdShowResult.Dismissed, "show-session")), h.results)
        assertFalse(FullScreenShowGate.isAnyAdShowing)
    }

    @Test fun `retained ad keeps age and unknown validity or price stays unknown`() {
        val ad = RetainedAd("same object", 10, 100)
        assertTrue(ad.isUsable(109))
        assertFalse(ad.isUsable(110))
        assertFalse(ad.isUsable(9))
        assertNull(ad.priceUsd)
        assertFalse(RetainedAd("unknown age", null, 100).isUsable(50))
        assertFalse(RetainedAd("unknown TTL", 10, null).isUsable(50))
        // Taking/returning the same object cannot reset its original age.
        assertEquals(10L, ad.copy().loadStartedAtMillis)
    }

    @Test fun `first cancellation or timeout wins and SDK failure remains final after handoff`() {
        for (cancelFirst in listOf(true, false)) {
            val h = harness()
            h.controller.start()
            h.now = 500
            if (cancelFirst) { h.controller.cancel(); h.tick() }
            else { h.tick(); h.controller.cancel() }
            assertEquals(if (cancelFirst) "opportunity_cancelled" else "wait_timeout", h.reason)
            assertEquals(1, h.results.size)
        }
        val shown = harness()
        shown.ready = true
        shown.controller.start()
        shown.now = 1000
        shown.controller.cancel()
        assertTrue(shown.controller.attempt.complete())
        val failure = AdRewardResult(false, AdShowResult.Failed("sdk_show_failed"), "show-session")
        shown.sdkResult?.invoke(failure)
        shown.sdkResult?.invoke(AdRewardResult(true, AdShowResult.Dismissed))
        assertEquals(listOf(failure), shown.results)
        assertFalse(FullScreenShowGate.isAnyAdShowing)
    }

    @Test fun `TopOn accepts missing IDs but rejects a late queued callback and known old ID`() {
        assertTrue(acceptsTopOnCallback("B", "B", null, true))
        assertTrue(acceptsTopOnCallback("B", "B", "B", true))
        assertFalse(acceptsTopOnCallback("A", "B", "A", true))
        assertFalse(acceptsTopOnCallback("B", "B", "A", true))
        assertFalse(acceptsTopOnCallback("B", "B", null, false))
        assertFalse(acceptsTopOnCallback(null, null, null, true))
    }

    private class Harness(timeout: Long, queuedFor: Long) {
        var now = queuedFor
        var ready = false
        var precondition: String? = null
        var hostFailure: String? = null
        var scene: () -> Boolean = { true }
        var ensure: () -> Unit = {}
        var beforeCommit: () -> Unit = {}
        var onEvent: (AdEvent) -> Unit = {}
        var onResult: (AdRewardResult) -> Unit = {}
        var ensures = 0
        var shows = 0
        var sdkCalls = 0
        var cleanups = 0
        var scheduled: Runnable? = null
        var scheduledDelay = 0L
        var sdkResult: ((AdRewardResult) -> Unit)? = null
        val events = mutableListOf<AdEvent>()
        val results = mutableListOf<AdRewardResult>()
        val reason: String? get() = (results.lastOrNull()?.showResult as? AdShowResult.Failed)?.reason
        val controller = DisplayOpportunityController(
            startedAtMillis = 0,
            timeoutMillis = timeout,
            nowMillis = { now },
            schedule = { runnable, delay -> scheduled = runnable; scheduledDelay = delay },
            unschedule = { scheduled = null },
            precondition = { precondition },
            sceneValid = { scene() },
            hostFailure = { hostFailure },
            isReady = { ready },
            ensureLoaded = { ensures++; ensure() },
            show = { attempt, callback -> show(attempt, callback) },
            onCleanup = { cleanups++ },
            onResult = { results += it; onResult(it) },
        )

        private fun show(attempt: FullScreenShowAttempt, callback: (AdRewardResult) -> Unit) {
            shows++
            val session = AdShowSession(
                listener = AdEventListener { events += it; onEvent(it) },
                platform = AdPlatform.ADMOB,
                mediationMode = AdMediationMode.BIDDING,
                format = AdFormat.REWARDED,
                position = "test_rewarded",
                adUnitId = "test-ad",
                sessionId = "show-session",
                number = 1,
                attempt = attempt,
                onCreated = controller::sessionStarted,
            )
            session.bidResult(AdBidEventData(AdPlatform.ADMOB, true, false, null, null, null, "a", "t"))
            beforeCommit()
            val failure = attempt.failureReason() ?: FullScreenShowGate.commit(attempt)
            if (failure != null) {
                if (attempt.complete()) {
                    session.showFailure(failure)
                    callback(AdRewardResult(false, AdShowResult.Failed(failure), session.sessionId))
                }
            } else {
                sdkCalls++
                sdkResult = callback
            }
        }

        fun tick() { val task = scheduled; scheduled = null; task?.run() }
        fun finishSdk(reward: Boolean = false) {
            if (controller.attempt.complete()) {
                sdkResult?.invoke(AdRewardResult(reward, AdShowResult.Dismissed, "show-session"))
            }
        }
    }
}
