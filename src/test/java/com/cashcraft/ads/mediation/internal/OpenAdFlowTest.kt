package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.app.Application
import android.os.Looper
import com.cashcraft.ads.mediation.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises the shared scene auction + waiting controller with a deterministic clock. */
class OpenAdFlowTest {
    private val tasks = mutableListOf<Task>()
    private fun task(timeout: Long = 500) = Task(timeout = timeout).also(tasks::add)
    private fun interTask() = Task(listOf(AdFormat.INTERSTITIAL)).also(tasks::add)
    @After fun cleanup() { tasks.forEach { it.close() } }

    @Test fun `four callbacks trigger two platform auctions then the highest of four`() {
        val t = task()
        t.ready(0, .01) // Cached app-open must still wait for the other three.
        t.start()
        t.now = 100
        t.loaded(1, .02)
        t.loaded(2, .03)
        assertEquals(0, t.shows)
        t.loaded(3, .04)
        assertEquals(1, t.shows)
        assertEquals(AdFormat.INTERSTITIAL, t.winner!!.format)
        assertEquals(AdPlatform.TOPON, t.winner!!.bid.selection!!.winner)
        assertEquals(.04, t.winner!!.bid.selection!!.priceUsd!!, 0.0)
        assertEquals(1, t.logs.count { it.startsWith("比价候选表") })
        assertEquals(1, t.logs.count { it.startsWith("比价结果：") })
        val tableIndex = t.logs.indexOfFirst { it.startsWith("比价候选表") }
        assertEquals("比价结果：TopOn 插页胜出，报价 0.04 美元/次展示。", t.logs[tableIndex + 1])
        assertTrue(t.logs.any { it.startsWith("等待结束：候选结果已齐") })
        assertEquals(4, t.inventory.count { it.ready }) // Auction does not consume the losers.
        t.dismiss()
    }

    @Test fun `mixed success and failure settles early while any pending candidate keeps waiting`() {
        val t = task()
        t.start()
        t.now = 100
        t.failed(0)
        t.loaded(1, .02)
        t.failed(2)
        assertEquals(0, t.shows)
        t.failed(3)
        assertEquals(AdFormat.APP_OPEN, t.winner!!.format)
        assertEquals(AdPlatform.TOPON, t.winner!!.bid.selection!!.winner)
        assertEquals(1, t.shows)
        t.dismiss()
    }

    @Test fun `deadline selects returned candidates and excludes a late higher bid before timer dispatch`() {
        val t = task()
        t.start()
        t.now = 100
        t.loaded(0, .01)
        t.loaded(2, .03)
        t.now = 501 // Simulate a busy main queue receiving a result after the cutoff.
        t.loaded(3, 100.0)
        assertEquals(AdPlatform.ADMOB, t.winner!!.bid.selection!!.winner)
        assertEquals(AdFormat.INTERSTITIAL, t.winner!!.format)
        assertTrue(t.logs.any { it.startsWith("等待结束：已到等待时限") })
        assertTrue(t.logs.any { it.contains("仍在加载 2 条；后续加载结果仅供下次使用") })
        t.loaded(1, 200.0)
        t.dismiss()
        t.dismiss()
        assertEquals(1, t.shows)
        assertEquals(listOf(AdShowResult.Dismissed), t.results)
    }

    @Test fun `one initialized platform can win at deadline while aggregate initialization is pending`() {
        val t = task()
        t.precondition = displayOpportunityFailureReason(null, AdsState.INITIALIZING, false)
        t.start()
        t.now = 100
        t.precondition = displayOpportunityFailureReason(null, AdsState.INITIALIZING, true)
        t.loaded(0, .02)
        t.loaded(2, .03)
        assertEquals(0, t.shows)
        t.now = 500
        t.tick()
        assertEquals(1, t.shows)
        assertEquals(AdFormat.INTERSTITIAL, t.winner!!.format)
        assertEquals(AdPlatform.ADMOB, t.winner!!.bid.selection!!.winner)
        assertEquals(0, t.nativeRequests)
        t.dismiss()
    }

    @Test fun `pending privacy consumes the open budget without loading showing or late revival`() {
        val t = task(timeout = 15_000)
        t.precondition = "sdk_initializing"
        t.nativeReady = true
        repeat(4) { t.ready(it, .02) }
        t.start()
        t.now = 14_999
        t.tick()
        assertTrue(t.results.isEmpty())
        assertEquals(0, t.loadRequests)
        assertEquals(0, t.shows)
        t.now = 15_000
        t.tick()
        assertEquals(15_000L, t.controller.elapsedMillis())
        assertEquals(listOf(AdShowResult.Failed("wait_timeout")), t.results)
        assertEquals(0, t.loadRequests)
        assertEquals(0, t.shows)
        assertEquals(0, t.nativeRequests)
        assertNull(t.scheduled)

        t.precondition = null
        t.now = 22_000
        repeat(4) { t.loaded(it, .03) }
        t.controller.inventoryChanged()
        t.controller.resume()
        t.controller.start()
        t.tick()
        assertEquals(listOf(AdShowResult.Failed("wait_timeout")), t.results)
        assertEquals(0, t.loadRequests)
        assertEquals(0, t.shows)
        assertEquals(0, t.nativeRequests)
        assertNull(t.scheduled)
    }

    @Test fun `privacy granted within open budget leaves only the remaining auction time`() {
        val t = task(timeout = 15_000)
        t.precondition = "sdk_initializing"
        t.start()
        t.now = 7_000
        t.precondition = null
        t.loaded(0, .02)
        assertEquals(1, t.loadRequests)
        assertEquals(0, t.shows)
        t.now = 14_999
        t.tick()
        assertTrue(t.results.isEmpty())
        assertEquals(0, t.shows)
        t.now = 15_000
        t.tick()
        assertEquals(15_000L, t.controller.elapsedMillis())
        assertEquals(1, t.shows)
        assertEquals(AdFormat.APP_OPEN, t.winner!!.format)
        assertEquals(0, t.nativeRequests)
        t.dismiss()
    }

    @Test fun `pending privacy counts foreground time but preserves background pause semantics`() {
        val t = task(timeout = 15_000)
        t.precondition = "sdk_initializing"
        t.start()
        t.now = 2_000
        t.controller.pause()
        t.now = 22_000
        t.controller.inventoryChanged()
        assertNull(t.scheduled)
        assertEquals(2_000L, t.controller.elapsedMillis())
        t.controller.resume()
        t.now = 34_999
        t.tick()
        assertTrue(t.results.isEmpty())
        t.now = 35_000
        t.tick()
        assertEquals(15_000L, t.controller.elapsedMillis())
        assertEquals(listOf(AdShowResult.Failed("wait_timeout")), t.results)
        assertEquals(0, t.loadRequests)
        assertEquals(0, t.shows)
        assertEquals(0, t.nativeRequests)
    }

    @Test fun `no successes uses native immediately after all fail or at timeout`() {
        for (allFailed in listOf(true, false)) {
            val t = task()
            t.nativeReady = true
            t.start()
            if (allFailed) repeat(4) { t.failed(it) }
            else { t.now = 500; t.tick() }
            assertNull(t.winner)
            assertEquals(1, t.nativeRequests)
            assertEquals(1, t.shows)
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            assertEquals("another_full_screen_ad_showing", FullScreenShowGate.reserve(FullScreenShowAttempt()))
            t.dismiss()
        }
    }

    @Test fun `empty native cache ends once and late callbacks cannot revive the task`() {
        val t = task()
        t.start()
        t.now = 500
        t.tick()
        assertEquals(listOf(AdShowResult.Failed("no_preloaded_ad")), t.results)
        assertEquals(1, t.nativeRequests)
        repeat(4) { t.loaded(it, .01) }
        t.controller.cancel()
        assertEquals(0, t.shows)
        assertEquals(1, t.results.size)
        assertNull(FullScreenShowGate.reserve(t.controller.attempt))
    }

    @Test fun `cancellation detaches observer without cancelling inventory and next task uses late cache`() {
        val a = task()
        a.start()
        a.controller.cancel()
        repeat(4) { a.loaded(it, .02) }
        assertEquals(0, a.shows)
        assertEquals(0, a.nativeRequests)
        assertEquals(listOf(AdShowResult.Failed("opportunity_cancelled")), a.results)
        val b = task()
        repeat(4) { b.inventory[it] = a.inventory[it]; b.prices[it] = a.prices[it] }
        b.start()
        assertEquals(1, b.shows)
        a.controller.cancel()
        assertTrue(FullScreenShowGate.isAnyAdShowing)
        b.dismiss()
    }

    @Test fun `background pauses the budget and resume never bypasses pending bidders`() {
        val t = task()
        t.start()
        t.now = 200
        t.controller.pause()
        t.now = 10_200
        t.loaded(0, .01)
        assertEquals(0, t.shows)
        t.controller.resume()
        assertEquals(0, t.shows)
        t.now = 10_499
        t.tick()
        assertEquals(0, t.shows)
        t.now = 10_500
        t.tick()
        assertEquals(1, t.shows)
        t.dismiss()
    }

    @Test fun `disabled candidates settle without extending the wait`() {
        val t = task()
        repeat(3) { t.inventory[it] = FullScreenAdAuction.Inventory(false, true, false) }
        t.ready(3, .01)
        t.start()
        assertEquals(1, t.shows)
        assertEquals(AdFormat.INTERSTITIAL, t.winner!!.format)
        t.dismiss()
    }

    @Test fun `known zero beats unknown and ties deterministically prefer app open then admob`() {
        val cases = listOf(
            listOf(null, null, null, null) to 0,
            listOf(.01, .01, .01, .01) to 0,
            listOf(null, null, 0.0, null) to 2,
            listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0) to 3,
            listOf(null, .01, null, null) to 1,
        )
        for ((prices, expected) in cases) {
            val t = task()
            prices.forEachIndexed(t::ready)
            t.start()
            assertEquals(t.keys[expected].first, t.winner!!.format)
            assertEquals(t.keys[expected].second, t.winner!!.bid.selection!!.winner)
            t.dismiss()
        }
    }

    @Test fun `expired cached candidates are removed before final selection`() {
        val t = task()
        t.ready(0, .1)
        t.start()
        t.inventory[0] = FullScreenAdAuction.Inventory(false, true)
        t.now = 500
        t.tick()
        assertNull(t.winner)
        assertEquals(1, t.nativeRequests)
        assertEquals(0, t.shows)
    }

    @Test fun `scene host consent failures and concurrent requests never fall back`() {
        for (kind in listOf("scene", "host", "consent")) {
            val t = task()
            t.nativeReady = true
            t.start()
            t.now = 500
            when (kind) {
                "scene" -> t.sceneValid = false
                "host" -> t.hostFailure = "activity_not_available"
                else -> t.precondition = "consent_not_obtained"
            }
            t.tick()
            assertEquals(0, t.nativeRequests)
            assertEquals(1, t.results.size)
        }
        val a = task()
        a.start()
        val b = task()
        b.start()
        assertEquals(listOf(AdShowResult.Failed("request_in_progress")), b.results)
        assertEquals(0, b.nativeRequests)
    }

    @Test fun `decision ends the loading deadline while pending SDK preparation keeps final guards`() {
        for (cancel in listOf(false, true)) {
            val t = task()
            t.deferHandoff = true
            t.ready(0, .01)
            t.start()
            t.now = 500
            t.tick()
            assertEquals(0, t.shows)
            assertNull(t.scheduled)
            assertTrue(t.results.isEmpty())
            t.now = 5_000
            if (cancel) t.controller.cancel()
            t.commit()
            assertEquals(if (cancel) 0 else 1, t.shows)
            if (!cancel) t.dismiss()
            assertEquals(1, t.results.size)
        }
    }

    @Test fun `interstitial scene waits only for its two platforms and never selects native over a ready interstitial`() {
        val t = interTask()
        t.nativeReady = true
        t.ready(0, 0.0)
        t.start()
        assertEquals(0, t.shows)
        t.loaded(1, null)
        assertEquals(AdFormat.INTERSTITIAL, t.winner!!.format)
        assertEquals(AdPlatform.ADMOB, t.winner!!.bid.selection!!.winner)
        assertEquals(0, t.nativeRequests)
        assertEquals(1, t.shows)
        assertFalse(t.logs.any { it.contains("开屏") })
        t.dismiss()
    }

    @Test fun `interstitial deadline excludes late higher bid and a selected SDK failure never falls back`() {
        val t = interTask()
        t.nativeReady = true
        t.start()
        t.now = 100
        t.loaded(0, .01)
        t.now = 501
        t.loaded(1, .99)
        assertEquals(AdPlatform.ADMOB, t.winner!!.bid.selection!!.winner)
        t.failShow()
        t.dismiss()
        assertEquals(listOf(AdShowResult.Failed("show_failed")), t.results)
        assertEquals(0, t.nativeRequests)
        assertEquals(1, t.shows)
    }

    @Test fun `interstitial exhausted disabled and timed out inventory all use the same native reservation`() {
        for (kind in listOf("failed", "disabled", "timeout")) {
            val t = interTask()
            t.nativeReady = true
            if (kind == "disabled") repeat(2) { t.inventory[it] = FullScreenAdAuction.Inventory(false, true, false) }
            t.start()
            if (kind == "failed") repeat(2) { t.failed(it) }
            if (kind == "timeout") { t.now = 500; t.tick() }
            assertNull(t.winner)
            assertEquals(1, t.nativeRequests)
            assertEquals(1, t.shows)
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            repeat(2) { t.loaded(it, .5) }
            t.dismiss()
            t.dismiss()
            assertEquals(listOf(AdShowResult.Dismissed), t.results)
        }
    }

    @Test fun `interstitial cancellation or empty native ends once and cannot be revived`() {
        for (cancel in listOf(false, true)) {
            val t = interTask()
            t.start()
            if (cancel) t.controller.cancel() else repeat(2) { t.failed(it) }
            repeat(2) { t.loaded(it, 1.0) }
            assertEquals(listOf(AdShowResult.Failed(if (cancel) "opportunity_cancelled" else "no_preloaded_ad")), t.results)
            assertEquals(if (cancel) 0 else 1, t.nativeRequests)
            assertEquals(0, t.shows)
        }
    }

    private class Task(
        formats: List<AdFormat> = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL),
        private val timeout: Long = 500,
    ) {
        val keys = formats.flatMap { f -> AdPlatform.entries.map { f to it } }
        val inventory = MutableList(keys.size) { FullScreenAdAuction.Inventory(false, false) }
        val prices = MutableList<Double?>(keys.size) { null }
        val logs = mutableListOf<String>()
        val results = mutableListOf<AdShowResult>()
        var now = 0L
        var sceneValid = true
        var hostFailure: String? = null
        var precondition: String? = null
        var nativeReady = false
        var nativeRequests = 0
        var loadRequests = 0
        var shows = 0
        var deferHandoff = false
        var scheduled: Runnable? = null
        var winner: FullScreenAdAuction.Winner? = null
        private var callback: ((AdRewardResult) -> Unit)? = null
        val auction = FullScreenAdAuction(
            formats = formats,
            read = { f, p -> inventory[keys.indexOf(f to p)] },
            price = { f, p -> prices[keys.indexOf(f to p)] },
            trace = logs::add,
        )
        private val listener: () -> Unit = { changed() }
        val controller = DisplayOpportunityController(
            startedAtMillis = 0, timeoutMillis = timeout, nowMillis = { now },
            schedule = { r, _ -> scheduled = r }, unschedule = { scheduled = null },
            precondition = { precondition }, sceneValid = { sceneValid }, hostFailure = { hostFailure },
            loadSnapshot = { snapshot() }, ensureLoaded = { loadRequests++ },
            show = { attempt, result ->
                FullScreenLoadSignals.remove(listener)
                winner = auction.select()
                assertNull(FullScreenShowGate.reserve(attempt)) // Waiting and fallback share the same owner.
                assertEquals("request_in_progress", FullScreenShowGate.reserve(FullScreenShowAttempt()))
                if (winner == null) nativeRequests++
                callback = result
                if (winner == null && !nativeReady) result(AdRewardResult(false, AdShowResult.Failed("no_preloaded_ad")))
                else if (!deferHandoff) commit()
            },
            onCleanup = { FullScreenLoadSignals.remove(listener) },
            onResult = { results += it.showResult },
            showWhenEmpty = true, preferAvailableAfterResume = false, trace = logs::add,
        )
        private fun snapshot(): DisplayOpportunityController.LoadSnapshot = auction.snapshot(controller.elapsedMillis() < timeout)
        private fun changed() { snapshot(); controller.inventoryChanged() }
        fun start() { auction.snapshot(true); FullScreenLoadSignals.add(listener); controller.start() }
        fun ready(index: Int, price: Double?) { inventory[index] = FullScreenAdAuction.Inventory(true, true); prices[index] = price }
        fun loaded(index: Int, price: Double?) { ready(index, price); FullScreenLoadSignals.changed(); tick() }
        fun failed(index: Int) { inventory[index] = FullScreenAdAuction.Inventory(false, true); FullScreenLoadSignals.changed(); tick() }
        fun tick() { val r = scheduled; scheduled = null; r?.run() }
        fun commit() {
            val reason = controller.attempt.failureReason() ?: FullScreenShowGate.commit(controller.attempt)
            if (reason != null) { controller.attempt.complete(); callback?.invoke(AdRewardResult(false, AdShowResult.Failed(reason))) }
            else shows++
        }
        fun dismiss() { controller.attempt.complete(); callback?.invoke(AdRewardResult(false, AdShowResult.Dismissed)) }
        fun failShow() { controller.attempt.complete(); callback?.invoke(AdRewardResult(false, AdShowResult.Failed("show_failed"))) }
        fun close() { FullScreenLoadSignals.remove(listener); controller.cancel(); controller.attempt.complete() }
    }
}

/** Verifies the public entries bind different UMP timing policies to the shared controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [ShadowMMKV::class])
class OpenAdConsentBudgetTest {
    private val fields = listOf("application", "config", "umpConsentManager", "initializationStage", "policyChecker", "policyUpdatePosted")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
    private val providerStarted = ReflectionHelpers.getStaticField<AtomicBoolean>(Ads::class.java, "providerInitializationStarted")
    private val wasStarted = providerStarted.get()
    private val host = Robolectric.buildActivity(Activity::class.java)
    private val tasks = mutableListOf<AdTask>()

    @Before fun prepare() {
        resetInstallation()
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Ads::class.java, "application", app)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST), autoShowAppOpen = false,
            openTimeoutMillis = { 15_000 }, interTimeoutMillis = { 15_000 },
        ))
        ShadowMMKV.reset()
        ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", AdPolicyChecker(AdUsageStore(app, 0)))
        ReflectionHelpers.setStaticField(Ads::class.java, "policyUpdatePosted", true)
        ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
            UmpConsentManager(app, UmpConsentConfig(enabled = true), false, "OpenConsentBudgetTest"))
        stage("WAITING_FOR_UMP")
        providerStarted.set(false)
        AdLifecycleMonitor.install(app)
        host.setup().visible().windowFocusChanged(true)
    }

    @After fun restore() {
        tasks.forEach(AdTask::cancel)
        host.pause().stop().destroy()
        resetInstallation()
        fields.forEach { (key, value) -> ReflectionHelpers.setStaticField(Ads::class.java, key, value) }
        providerStarted.set(wasStarted)
    }

    @Test fun `public open expires during UMP while public inter preserves UMP exclusion and consent gate`() {
        val open = mutableListOf<AdShowResult>()
        tasks += Ads.showOpen("cold", onResult = open::add)
        advance(14_999)
        assertTrue(open.isEmpty())
        advance(1)
        assertEquals(listOf(AdShowResult.Failed("wait_timeout")), open)
        assertFalse(providerStarted.get())
        assertFalse(Ads.isFullScreenAdShowing)
        assertEquals("WAITING_FOR_UMP", ReflectionHelpers.getStaticField<Any>(Ads::class.java, "initializationStage").toString())

        val inter = mutableListOf<AdShowResult>()
        tasks += Ads.showInter("save", onResult = inter::add)
        advance(30_000)
        assertTrue(inter.isEmpty())
        assertFalse(providerStarted.get())
        assertFalse(Ads.isFullScreenAdShowing)
        // Finishing UMP without permission must reject the remaining interstitial opportunity.
        stage("COMPLETE")
        FullScreenLoadSignals.changed()
        advance(100)
        assertEquals(listOf(AdShowResult.Failed("consent_not_obtained")), inter)
        assertEquals(listOf(AdShowResult.Failed("wait_timeout")), open)
        assertFalse(providerStarted.get())
    }

    @Test fun `public open excludes only consent form time from deadline`() {
        val ump = ReflectionHelpers.getStaticField<UmpConsentManager>(Ads::class.java, "umpConsentManager")
        val open = mutableListOf<AdShowResult>()
        tasks += Ads.showOpen("cold", onResult = open::add)
        advance(5_000)
        ReflectionHelpers.setField(ump, "formShowing", true)
        advance(30_000)
        assertTrue(open.isEmpty())
        ReflectionHelpers.setField(ump, "formShowing", false)
        advance(9_999)
        assertTrue(open.isEmpty())
        advance(1)
        assertEquals(listOf(AdShowResult.Failed("wait_timeout")), open)
    }

    private fun stage(name: String) {
        val stage = fields.getValue("initializationStage")!!.javaClass.enumConstants!!
            .single { (it as Enum<*>).name == name }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage", stage)
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun resetInstallation() {
        val type = AdLifecycleMonitor::class.java
        val app = ReflectionHelpers.getStaticField<Application?>(type, "installedApplication")
        val callbacks = ReflectionHelpers.getStaticField<Application.ActivityLifecycleCallbacks>(type, "callbacks")
        app?.unregisterActivityLifecycleCallbacks(callbacks)
        ReflectionHelpers.setStaticField(type, "installedApplication", null)
    }
}
