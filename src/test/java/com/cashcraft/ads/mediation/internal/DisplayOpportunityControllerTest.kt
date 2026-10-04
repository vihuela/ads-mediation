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

    @Test fun `deadline includes main queue time and final check uses available cache`() {
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
        assertEquals(1, h.sdkCalls)
        assertTrue(h.results.isEmpty())
        assertNull(h.scheduled)
        h.finishSdk()
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
        assertEquals(1, h.sdkCalls)
        h.finishSdk()

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
        assertEquals(1, expired.sdkCalls)
        expired.finishSdk()
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

    @Test fun `first resume and window can wait but a cancelled host cannot revive`() {
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

    @Test fun `pause unschedules waiting without cleanup callback or aborting shared load`() {
        val h = harness(timeout = 15_000)
        var loading = false
        var aborted = false
        h.ensure = { loading = true }
        h.controller.attempt.onAborted = { aborted = true }
        h.controller.start()
        val queuedTick = h.scheduled!!
        h.now = 3_000
        h.controller.pause()
        assertNull(h.scheduled)
        assertEquals(0, h.cleanups)
        assertTrue(h.results.isEmpty())
        assertTrue(loading)
        assertFalse(aborted)
        h.now = 103_000
        h.ready = true
        queuedTick.run()
        assertEquals(0, h.shows)
        assertNull(h.scheduled)
        assertEquals(1, h.ensures)
        assertEquals(0, h.cleanups)
        assertTrue(h.results.isEmpty())
        assertFalse(aborted)

        val other = harness()
        other.controller.start()
        assertEquals("request_in_progress", other.reason)
        assertEquals(0, other.ensures)
    }

    @Test fun `fifteen second budget retains twelve seconds after long background pause`() {
        val h = harness(timeout = 15_000)
        h.controller.start()
        h.now = 3_000
        h.controller.pause()
        h.now = 103_000
        h.controller.resume()
        assertTrue(h.results.isEmpty())
        assertNotNull(h.scheduled)
        assertEquals(1, h.ensures)
        h.now = 114_999
        h.tick()
        assertTrue(h.results.isEmpty())
        assertEquals(1L, h.scheduledDelay)
        h.now = 115_000
        h.tick()
        assertEquals("wait_timeout", h.reason)
        assertEquals(1, h.cleanups)
        assertEquals(1, h.results.size)
    }

    @Test fun `resume immediately shows background ready cache while another bidder is pending`() {
        val h = harness(timeout = 15_000)
        h.settled = false
        h.controller.start()
        h.now = 3_000
        h.controller.pause()
        h.now = 103_000
        h.ready = true
        assertEquals(0, h.sdkCalls)
        h.controller.resume()
        assertEquals(1, h.sdkCalls)
        assertEquals(1, h.ensures)
        assertNull(h.scheduled)
        assertTrue(h.results.isEmpty())
        h.finishSdk()
    }

    @Test fun `repeated pause and resume preserve cumulative foreground budget`() {
        val h = harness(timeout = 15_000)
        h.controller.start()
        h.now = 3_000
        h.controller.pause()
        h.now = 4_000
        h.controller.pause()
        h.now = 103_000
        h.controller.resume()
        h.now = 104_000
        h.controller.resume()
        h.now = 107_000
        h.controller.pause()
        h.now = 108_000
        h.controller.pause()
        h.now = 207_000
        h.controller.resume()
        h.now = 208_000
        h.controller.resume()
        h.now = 214_999
        h.tick()
        assertTrue(h.results.isEmpty())
        assertEquals(1L, h.scheduledDelay)
        assertEquals(1, h.ensures)
        h.now = 215_000
        h.tick()
        assertEquals("wait_timeout", h.reason)
        assertEquals(1, h.results.size)
    }

    @Test fun `resume without pause cannot bypass pending bidder or reset deadline`() {
        val h = harness()
        h.ready = true
        h.settled = false
        h.controller.resume()
        assertEquals(0, h.ensures)
        assertEquals(0, h.sdkCalls)
        h.controller.start()
        h.now = 400
        h.controller.resume()
        assertEquals(0, h.sdkCalls)
        h.now = 500
        h.tick()
        assertEquals(1, h.sdkCalls)
        assertEquals(1, h.ensures)
        h.finishSdk()
    }

    @Test fun `cancel including host destruction while paused cleans up and cannot resume`() {
        for (reason in listOf("opportunity_cancelled", "activity_not_available")) {
            val h = harness(timeout = 15_000)
            var aborts = 0
            h.controller.attempt.onAborted = { aborts++ }
            h.controller.start()
            h.now = 3_000
            h.controller.pause()
            h.controller.cancel(reason)
            assertEquals(reason, h.reason)
            assertEquals(1, h.cleanups)
            assertEquals(1, aborts)
            h.now = 103_000
            h.ready = true
            h.controller.resume()
            h.controller.pause()
            h.controller.resume()
            h.controller.start()
            h.controller.cancel()
            h.tick()
            assertEquals(0, h.sdkCalls)
            assertEquals(1, h.ensures)
            assertEquals(1, h.results.size)
            assertEquals(1, h.cleanups)
            assertEquals(1, aborts)
            assertNull(h.scheduled)
        }
        val next = harness()
        next.controller.start()
        assertEquals(1, next.ensures)
    }

    @Test fun `pause before start still validates and reserves but waits for resume to check`() {
        for (timeout in listOf(0L, -1L)) {
            val invalid = harness(timeout)
            invalid.controller.pause()
            invalid.controller.start()
            assertEquals("invalid_timeout", invalid.reason)
            assertEquals(0, invalid.ensures)
            invalid.controller.resume()
            assertEquals(1, invalid.results.size)
        }
        val h = harness(timeout = 15_000)
        var snapshots = 0
        h.readReady = { snapshots++; h.ready }
        h.controller.pause()
        h.now = 100_000
        h.controller.start()
        assertEquals(0, snapshots)
        assertEquals(0, h.ensures)
        assertEquals(0, h.cleanups)
        assertNull(h.scheduled)
        assertTrue(h.results.isEmpty())
        val other = harness()
        other.controller.start()
        assertEquals("request_in_progress", other.reason)
        assertEquals(0, other.ensures)
        h.now = 200_000
        h.controller.resume()
        assertTrue(snapshots > 0)
        assertEquals(1, h.ensures)
        assertNotNull(h.scheduled)
        h.now = 214_999
        h.tick()
        assertTrue(h.results.isEmpty())
        assertEquals(1L, h.scheduledDelay)
        h.now = 215_000
        h.tick()
        assertEquals("wait_timeout", h.reason)
    }

    @Test fun `expired background cache keeps waiting with remaining foreground budget`() {
        val h = harness(timeout = 15_000)
        h.ready = true
        h.settled = false
        h.controller.start()
        assertEquals(0, h.sdkCalls)
        h.now = 3_000
        h.controller.pause()
        h.now = 103_000
        h.ready = false // The retained cache expired while the host was in the background.
        h.controller.resume()
        assertEquals(0, h.sdkCalls)
        assertTrue(h.results.isEmpty())
        assertNotNull(h.scheduled)
        assertEquals(1, h.ensures)
        h.now = 114_999
        h.tick()
        assertTrue(h.results.isEmpty())
        assertEquals(1L, h.scheduledDelay)
        h.now = 115_000
        h.tick()
        assertEquals("wait_timeout", h.reason)
        assertEquals(0, h.sdkCalls)
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

    @Test fun `candidate selected before or at deadline can finish handoff after deadline`() {
        for (selectedAt in listOf(499L, 500L, 501L)) {
            val h = harness()
            h.controller.start()
            h.now = selectedAt
            h.ready = true
            h.beforeCommit = { h.now = 502 }
            h.tick()
            assertEquals(1, h.sdkCalls)
            assertTrue(h.results.isEmpty())
            assertNull(h.scheduled)
            h.finishSdk()
            assertEquals(1, h.results.size)
        }
    }

    @Test fun `crossing deadline during preparation does not bypass final guards`() {
        for (failure in listOf("opportunity_cancelled", "scene_invalid", "activity_not_resumed", "consent_not_obtained")) {
            val h = harness()
            h.controller.start()
            h.ready = true
            h.now = 499
            h.beforeCommit = {
                h.now = 502
                when (failure) {
                    "opportunity_cancelled" -> h.controller.cancel()
                    "scene_invalid" -> h.scene = { false }
                    "activity_not_resumed" -> h.hostFailure = failure
                    "consent_not_obtained" -> h.precondition = failure
                }
            }
            h.tick()
            assertEquals(0, h.sdkCalls)
            assertEquals(failure, h.reason)
            assertEquals(1, h.results.size)
        }
    }

    @Test fun `SDK handoff ends waiting and preserves close reward and one final result`() {
        val h = harness()
        h.ready = true
        h.controller.start()
        assertEquals(1, h.sdkCalls)
        assertNull(h.scheduled)
        assertEquals(1, h.cleanups)
        h.now = 10000
        h.controller.pause()
        h.controller.resume()
        h.controller.pause()
        h.controller.resume()
        h.controller.cancel("activity_not_resumed")
        h.controller.cancel()
        h.tick()
        assertTrue(h.results.isEmpty())
        assertTrue(FullScreenShowGate.isAnyAdShowing)
        assertEquals(1, h.sdkCalls)
        assertEquals(1, h.ensures)
        assertEquals(1, h.cleanups)
        assertNull(h.scheduled)
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

    @Test fun `one cached bidder waits until the other settles or the deadline`() {
        for (otherFinishes in listOf(true, false)) {
            val h = harness()
            h.ready = true
            h.settled = false
            h.controller.start()
            h.now = 300
            h.tick()
            assertEquals(0, h.sdkCalls)
            if (otherFinishes) h.settled = true else h.now = 500
            h.tick()
            assertEquals(1, h.sdkCalls)
            assertTrue(h.results.isEmpty())
            h.finishSdk()
            assertEquals(1, h.results.size)
        }
    }

    @Test fun `later higher priced bidder participates before the deadline`() {
        val h = harness()
        var topOnReady = false
        var winner: AdPlatform? = null
        h.ready = true // AdMob is cached first.
        h.settled = false
        h.beforeCommit = {
            winner = BidCandidateSelector.select(true, 0.01, topOnReady, 0.05)?.winner
        }
        h.controller.start()
        assertEquals(0, h.sdkCalls)
        topOnReady = true
        h.settled = true
        h.now = 200
        h.tick()
        assertEquals(AdPlatform.TOPON, winner)
        assertEquals(1, h.sdkCalls)
        h.finishSdk()
    }

    @Test fun `cache becoming ready during a check does not report load failure`() {
        val h = harness(timeout = 5_000)
        h.controller.start()
        h.now = 100
        h.readReady = { h.ready.also { h.ready = true } }
        h.tick()
        assertTrue("A successful load must not end the opportunity: ${h.reason}", h.results.isEmpty())
        h.tick()
        assertEquals(1, h.sdkCalls)
        h.finishSdk()
        assertEquals(AdShowResult.Dismissed, h.results.single().showResult)
    }

    @Test fun `all failures finish early but one failure with a pending bidder keeps waiting`() {
        val h = harness()
        h.settled = false
        h.controller.start()
        h.now = 100
        h.tick()
        assertTrue(h.results.isEmpty())
        h.settled = true
        h.now = 200
        h.tick()
        assertEquals("ad_load_failed", h.reason)
        assertEquals(0, h.sdkCalls)
        assertNull(h.scheduled)
        h.ready = true
        h.tick()
        assertEquals(0, h.sdkCalls)
    }

    @Test fun `deadline fallback still respects cancellation scene host and permission`() {
        for (failure in listOf("cancel", "scene", "host", "permission")) {
            val h = harness()
            h.ready = true
            h.settled = false
            h.controller.start()
            h.now = 500
            when (failure) {
                "cancel" -> h.controller.cancel()
                "scene" -> h.scene = { false }
                "host" -> h.hostFailure = "activity_not_resumed"
                "permission" -> h.precondition = "consent_not_obtained"
            }
            h.tick()
            assertEquals(0, h.sdkCalls)
            assertEquals(1, h.results.size)
        }
    }

    @Test fun `loading ends before SDK handoff while the request still owns the gate`() {
        val h = harness()
        h.controller.start()
        h.tick()
        assertEquals(listOf(true), h.loading)
        var showsAtHide = -1
        var competingReason: String? = null
        var competingLoading: List<Boolean>? = null
        h.onLoading = { loading ->
            if (!loading) {
                showsAtHide = h.shows
                val competing = harness()
                competing.controller.start()
                competingReason = competing.reason
                competingLoading = competing.loading.toList()
            }
        }
        h.ready = true
        h.tick()
        assertEquals(listOf(true, false), h.loading)
        assertEquals(0, showsAtHide)
        assertEquals("request_in_progress", competingReason)
        assertEquals(emptyList<Boolean>(), competingLoading)
        assertEquals(1, h.sdkCalls)
        assertTrue(h.results.isEmpty())
        h.finishSdk()
        assertEquals(listOf(true, false), h.loading)
    }

    @Test fun `loading callbacks tolerate exceptions and reentrant cancellation without leaking a wait`() {
        for (cancelOn in listOf(true, false)) {
            val h = harness()
            h.onLoading = { if (it == cancelOn) h.controller.cancel() }
            h.controller.start()
            h.ready = true
            h.tick()
            assertEquals(listOf(true, false), h.loading)
            assertEquals("opportunity_cancelled", h.reason)
            assertEquals(0, h.shows)
            assertEquals(1, h.results.size)
            assertNull(h.scheduled)
        }
        val throwing = harness()
        throwing.onLoading = { error("host UI error") }
        throwing.controller.start()
        throwing.now = 500
        throwing.tick()
        assertEquals(listOf(true, false), throwing.loading)
        assertEquals("wait_timeout", throwing.reason)
        assertEquals(1, throwing.results.size)
        assertNull(throwing.scheduled)
    }

    @Test fun `loading never starts for rejected hosts and clears before a failed result`() {
        val rejected = harness()
        rejected.hostFailure = "activity_not_available"
        rejected.controller.start()
        assertTrue(rejected.loading.isEmpty())
        val h = harness()
        var loadingAtResult: List<Boolean>? = null
        h.onResult = { loadingAtResult = h.loading.toList() }
        h.controller.start()
        h.precondition = "consent_not_obtained"
        h.tick()
        assertEquals("consent_not_obtained", h.reason)
        assertEquals(listOf(true, false), h.loading)
        assertEquals(listOf(true, false), loadingAtResult)
    }

    private class Harness(timeout: Long, queuedFor: Long) {
        var now = queuedFor
        var ready = false
        var readReady: () -> Boolean = { ready }
        var settled: Boolean? = null
        var precondition: String? = null
        var hostFailure: String? = null
        var scene: () -> Boolean = { true }
        var ensure: () -> Unit = {}
        var beforeCommit: () -> Unit = {}
        var onEvent: (AdEvent) -> Unit = {}
        var onResult: (AdRewardResult) -> Unit = {}
        var onLoading: (Boolean) -> Unit = {}
        val loading = mutableListOf<Boolean>()
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
            loadSnapshot = {
                val ready = readReady()
                DisplayOpportunityController.LoadSnapshot(ready, settled ?: ready)
            },
            ensureLoaded = { ensures++; ensure() },
            show = { attempt, callback -> show(attempt, callback) },
            onCleanup = { cleanups++ },
            onResult = { results += it; onResult(it) },
            onLoadingChanged = { loading += it; onLoading(it) },
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
