package com.cashcraft.ads.mediation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.analyticsErrorDetail
import com.cashcraft.ads.mediation.internal.nativeads.NativeInventoryControl
import com.cashcraft.ads.mediation.internal.nativeads.NativeInventoryKey
import com.cashcraft.ads.mediation.internal.nativeads.NativeLoad
import com.cashcraft.ads.mediation.internal.topon.TopOnFullScreenLoadControl
import com.cashcraft.ads.mediation.internal.topon.TopOnLoadCooldown
import com.thinkup.core.api.ErrorCode
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device contracts for our controllers/dispatcher; no SDK initialization or ad requests. */
@Suppress("DEPRECATION")
class TopOnDeviceContractTest : InstrumentationTestCase() {
    fun testRealHandlerSharesPlacementCooldownAcrossFullscreenAndNativeSizes() {
        verifyRealHandlerSharesPlacementCooldownAcrossFullscreenAndNativeSizes()
    }

    private fun verifyRealHandlerSharesPlacementCooldownAcrossFullscreenAndNativeSizes() {
        val handler = Handler(Looper.getMainLooper())
        val completed = CountDownLatch(3)
        val asynchronousError = AtomicReference<Throwable>()
        val pending = mutableMapOf<Runnable, Runnable>()
        val placement = "device-contract-${UUID.randomUUID()}"
        val fullCalls = mutableListOf<Long>()
        val nativeCalls = mutableListOf<Pair<NativeInventoryKey, Long>>()
        var failedAt = 0L
        var full: TopOnFullScreenLoadControl? = null
        var native: NativeInventoryControl? = null
        val leases = mutableListOf<AutoCloseable>()
        val schedule: (Runnable, Long) -> Unit = { task, delay ->
            val wrapped = Runnable {
                pending.remove(task)
                try {
                    task.run()
                } catch (error: Throwable) {
                    asynchronousError.compareAndSet(null, error)
                    while (completed.count > 0) completed.countDown()
                }
            }
            pending[task] = wrapped
            handler.postDelayed(wrapped, delay)
        }
        val cancel: (Runnable) -> Unit = { task ->
            pending.remove(task)?.let { handler.removeCallbacks(it) }
        }
        try {
            onMain {
                val cooldown = TopOnLoadCooldown.shared
                assertEquals(2_100L, TopOnLoadCooldown.COOLDOWN_MILLIS)
                val controller = TopOnFullScreenLoadControl(
                    SystemClock::elapsedRealtime, cooldown, schedule, cancel, { true },
                )
                full = controller
                controller.request(placement, isReady = { false }, isLoading = { false }, load = {
                    fullCalls += SystemClock.elapsedRealtime()
                    if (fullCalls.size == 1) {
                        failedAt = SystemClock.elapsedRealtime()
                        check(controller.failed(placement))
                    } else {
                        check(controller.loaded(placement))
                        completed.countDown()
                    }
                    true
                })
                val inventory = NativeInventoryControl(
                    clock = SystemClock::elapsedRealtime,
                    schedule = schedule,
                    unschedule = cancel,
                    prepare = { key, _, complete ->
                        nativeCalls += key to SystemClock.elapsedRealtime()
                        complete(true)
                        if (key.id == placement) completed.countDown()
                        NativeLoad {}
                    },
                    closeSession = {},
                    reportError = { _, _, error -> throw error },
                    loadDelayMillis = { cooldown.remainingMillis(it.id) },
                )
                native = inventory
                val key = NativeInventoryKey(AdPlatform.TOPON, placement, 320, 2f)
                leases += inventory.acquire(key)
                leases += inventory.acquire(key)
                leases += inventory.acquire(key.copy(widthPx = 640))
                leases += inventory.acquire(key.copy(id = "$placement-other"))
                assertEquals(1, fullCalls.size)
                assertEquals(listOf("$placement-other"), nativeCalls.map { it.first.id })
            }
            // Wait on the instrumentation thread so the real main Looper can run its timers.
            assertTrue("Shared cooldown did not complete within 10 seconds", completed.await(10, TimeUnit.SECONDS))
            asynchronousError.get()?.let { throw it }
            onMain {
                assertEquals(2, fullCalls.size)
                assertTrue("Fullscreen retry bypassed 2100 ms cooldown", fullCalls.last() - failedAt >= 2_100L)
                val shared = nativeCalls.filter { it.first.id == placement }
                assertEquals(listOf(320, 640), shared.map { it.first.widthPx }.sortedBy { it })
                assertTrue("Native demand bypassed shared placement cooldown", shared.all { it.second - failedAt >= 2_100L })
            }
        } finally {
            onMain {
                full?.onPolicyChanged(false)
                native?.let {
                    leases.forEach { it.close() }
                    it.clear()
                }
                handler.removeCallbacksAndMessages(null)
                pending.clear()
            }
        }
    }

    fun testThreeFullscreenFormatsHaveFourAttemptsAndPreserveBudgetAcrossPolicyWithdrawalAndDemand() {
        withDispatcher { dispatcher, events ->
            for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
                val h = FullscreenFixture(format, dispatcher)
                h.acceptLoad = false
                h.request()
                assertTrue(h.calls.isEmpty())
                assertTrue(h.timers.tasks.isEmpty())
                assertTrue(events.none { it.format == format })
                h.acceptLoad = true
                h.ready = true
                h.request()
                assertTrue(h.calls.isEmpty())
                h.ready = false
                h.request()
                val pending = h.timers.tasks.keys.single()
                repeat(5) { h.request(newOpportunity = true) }
                assertSame(pending, h.timers.tasks.keys.single())
                assertEquals(2_100L, h.timers.tasks.values.single())
                h.timers.advanceTo(1_000L)
                h.control.onPolicyChanged(false)
                assertTrue(h.timers.tasks.isEmpty())
                h.timers.advanceTo(1_500L)
                h.control.onPolicyChanged(true)
                assertEquals(2_100L, h.timers.tasks.values.single())
                for ((before, deadline) in listOf(2_099L to 2_100L, 6_099L to 6_100L, 14_099L to 14_100L)) {
                    val count = h.calls.size
                    h.timers.advanceTo(before)
                    assertEquals(count, h.calls.size)
                    h.timers.advanceTo(deadline)
                    assertEquals(count + 1, h.calls.size)
                }
                assertEquals(listOf(0L, 2_100L, 6_100L, 14_100L), h.calls)
                assertTrue(h.timers.tasks.isEmpty())
                repeat(3) {
                    h.request()
                    h.control.onPolicyChanged(true)
                }
                assertEquals(4, h.calls.size)
                assertTrue(h.timers.tasks.isEmpty())
                val fourAttempts = events.filter { it.format == format }
                assertEquals(List(4) { listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL) }.flatten(), fourAttempts.map { it.name })
                assertEquals(4, fourAttempts.filter { it.name == AdEventName.LOAD }.map { it.requestId }.distinct().size)
                assertTrue(fourAttempts.filter { it.name == AdEventName.LOAD_FAIL }.all {
                    it.errorCode == "2008" && it.reason == "ad_error" && it.analyticsParameters()["error_code"] == "2008"
                })
                h.request(newOpportunity = true)
                assertEquals(16_200L, h.timers.tasks.values.single())
                h.timers.advanceTo(16_199L)
                assertEquals(4, h.calls.size)
                h.timers.advanceTo(16_200L)
                assertEquals(5, h.calls.size)
                assertEquals(18_300L, h.timers.tasks.values.single())
                assertEquals(5, events.count { it.format == format && it.name == AdEventName.LOAD })
                h.control.onPolicyChanged(false)
                assertTrue(h.timers.tasks.isEmpty())
            }
        }
    }

    fun testTopOn2008DetailAndDispatcherOnlyPublishInvokedRequests() {
        withDispatcher { dispatcher, events ->
            val error = ErrorCode.getErrorCode("2008", "network-42", "device-contract-network-message")
            assertEquals("2008", error.code)
            val detail = error.analyticsErrorDetail()
            assertEquals(error.fullErrorInfo, detail)
            assertTrue(detail.contains("2008"))
            assertTrue(detail.contains("network-42"))
            assertTrue(detail.contains("device-contract-network-message"))
            dispatcher.createLoad(AdFormat.APP_OPEN, "never-invoked", 1)
            assertTrue(events.isEmpty())
            val session = dispatcher.createLoad(AdFormat.APP_OPEN, "invoked", 1)
            var calls = 0
            session.invokeLoad {
                calls++
                session.failed("error", error.code, error.desc, null, detail)
                assertTrue(events.isEmpty()) // Synchronous terminal waits for the actual LOAD event.
            }
            session.request()
            session.failed("error", "duplicate", "duplicate", null, "duplicate")
            assertEquals(calls, events.count { it.name == AdEventName.LOAD })
            assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map { it.name })
            val failure = events.last()
            assertEquals(session.requestId, failure.requestId)
            assertEquals("ad_error", failure.reason)
            assertEquals(detail, failure.errorDetail)
            assertTrue(checkNotNull(failure.latencyMillis) >= 0L)
            val properties = failure.analyticsParameters()
            assertEquals("2008", properties["error_code"])
            assertEquals(detail.trim().take(8_192), properties["error_detail"])
            assertEquals("ad_error", properties["reason"])
            val longDetail = failure.copy(errorDetail = "x".repeat(8_193)).analyticsParameters()
            assertEquals(8_192, (longDetail["error_detail"] as String).length)
            assertEquals(true, longDetail["error_detail_truncated"])
        }
    }

    /** Synthetic lifecycle/inventory signals exercise our controller, not production SDK wiring. */
    fun testAutoOpenRequestsInventoryOncePerOpportunityAndIgnoresLateSuccess() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeFragmentTestActivity
        val handler = Handler(Looper.getMainLooper())
        val activityField = field(AdLifecycleMonitor, "resumedActivity")
        val foregroundField = field(AdLifecycleMonitor, "isAppInForeground")
        var savedActivity: Any? = null
        var savedForeground = false
        var saved = false
        var now = 0L
        var available = false
        var inventoryRequests = 0
        var opportunities = 0
        var shows = 0
        var polls = CountDownLatch(5)
        val expired = CountDownLatch(1)
        val failures = mutableListOf<Pair<Int, String>>()
        var controller: AutoAppOpenController<Int>? = null
        var lifecycle: AdLifecycleMonitor.Listener? = null
        try {
            val focusDeadline = SystemClock.elapsedRealtime() + 3_000L
            var focused = false
            while (!focused && SystemClock.elapsedRealtime() < focusDeadline) {
                onMain { focused = activity.hasWindowFocus() && activity.window.decorView.isAttachedToWindow }
                if (!focused) SystemClock.sleep(20L)
            }
            assertTrue("Test Activity did not acquire a focused window", focused)
            onMain {
                savedActivity = activityField.get(AdLifecycleMonitor)
                savedForeground = foregroundField.getBoolean(AdLifecycleMonitor)
                saved = true
                activityField.set(AdLifecycleMonitor, WeakReference(activity))
                foregroundField.setBoolean(AdLifecycleMonitor, true)
                assertNull(AdLifecycleMonitor.activityShowFailureReason(activity))
                val automatic = AutoAppOpenController(
                    isEnabled = { true },
                    isProviderReady = { true },
                    providerFailureReason = { null },
                    isAdAvailable = { polls.countDown(); available },
                    beginOpportunity = { ++opportunities },
                    show = { _, _ -> shows++ },
                    fail = { opportunity, reason ->
                        failures += opportunity to reason
                        expired.countDown()
                    },
                    noAdFailureReason = "no_preloaded_ad",
                    handler = handler,
                    clock = { now },
                    checkIntervalMillis = 10L,
                    ensureLoaded = { inventoryRequests++ },
                )
                controller = automatic
                lifecycle = field(automatic, "lifecycleListener").get(automatic) as AdLifecycleMonitor.Listener
                automatic.onProviderInitialized()
            }
            assertTrue("First opportunity was not polled", polls.await(3, TimeUnit.SECONDS))
            onMain {
                assertEquals(1, opportunities)
                assertEquals(1, inventoryRequests)
                assertEquals(0, shows)
                polls = CountDownLatch(5)
                repeat(3) { controller!!.onAdAvailable() }
            }
            assertTrue("Ordinary schedule did not resume polling", polls.await(3, TimeUnit.SECONDS))
            onMain {
                assertEquals(1, inventoryRequests)
                now = 7_001L
                controller!!.onAdAvailable()
            }
            assertTrue("Expired opportunity did not terminate", expired.await(3, TimeUnit.SECONDS))
            val lateSignalDrained = CountDownLatch(1)
            onMain {
                assertEquals(listOf(1 to "no_preloaded_ad"), failures)
                available = true
                controller!!.onAdAvailable()
                handler.postDelayed({ lateSignalDrained.countDown() }, 50L)
            }
            assertTrue(lateSignalDrained.await(3, TimeUnit.SECONDS))
            onMain {
                assertEquals(0, shows)
                assertEquals(1, inventoryRequests)
                assertEquals(1, failures.size)
                foregroundField.setBoolean(AdLifecycleMonitor, false)
                lifecycle!!.onAppEnteredBackground()
                foregroundField.setBoolean(AdLifecycleMonitor, true)
                now = 8_000L
                available = false
                polls = CountDownLatch(5)
                lifecycle!!.onAppEnteredForeground(activity)
            }
            assertTrue("New foreground opportunity was not polled", polls.await(3, TimeUnit.SECONDS))
            onMain {
                assertEquals(2, opportunities)
                assertEquals(2, inventoryRequests)
                assertEquals(0, shows)
            }
        } finally {
            onMain {
                controller?.finishOpportunity("test_cleanup")
                lifecycle?.let(AdLifecycleMonitor::removeListener)
                handler.removeCallbacksAndMessages(null)
                if (saved) {
                    activityField.set(AdLifecycleMonitor, savedActivity)
                    foregroundField.setBoolean(AdLifecycleMonitor, savedForeground)
                }
                activity.finish()
            }
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun withDispatcher(block: (AdEventDispatcher, MutableList<AdEvent>) -> Unit) = onMain {
        val preferenceName = "topon_device_contract_${UUID.randomUUID()}"
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        }
        val events = mutableListOf<AdEvent>()
        try {
            block(AdEventDispatcher(context, AdPlatform.TOPON, AdMediationMode.TOPON,
                AdEventListener { events += it }, false, "TopOnDeviceContractTest"), events)
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }

    /** Virtual deadlines keep the 4/8-second budget checks exact; real timers are tested above. */
    private class Timers {
        var now = 0L
        val tasks = linkedMapOf<Runnable, Long>()

        fun advanceTo(target: Long) {
            require(target >= now)
            while (true) {
                val next = tasks.entries.filter { it.value <= target }.minByOrNull { it.value } ?: break
                now = next.value
                tasks.remove(next.key)
                next.key.run()
            }
            now = target
        }
    }

    private class FullscreenFixture(val format: AdFormat, dispatcher: AdEventDispatcher) {
        val timers = Timers()
        val calls = mutableListOf<Long>()
        var acceptLoad = true
        var ready = false
        private val placement = "device-contract-${format.analyticsValue}"
        val control = TopOnFullScreenLoadControl(
            clock = { timers.now },
            cooldown = TopOnLoadCooldown { timers.now },
            postDelayed = { task, delay -> timers.tasks[task] = timers.now + delay },
            cancel = { timers.tasks.remove(it) },
            canLoad = { true },
        )
        private val load: (Boolean) -> Boolean = {
            if (!acceptLoad) false else {
                val session = dispatcher.createLoad(format, placement, 1)
                session.invokeLoad {
                    calls += timers.now
                    check(control.failed(placement))
                    session.failed("error", "2008", "fixture failure", null)
                }
                true
            }
        }

        fun request(newOpportunity: Boolean = false) = control.request(
            placement, newOpportunity = newOpportunity,
            isReady = { ready }, isLoading = { false }, load = load,
        )
    }
}
