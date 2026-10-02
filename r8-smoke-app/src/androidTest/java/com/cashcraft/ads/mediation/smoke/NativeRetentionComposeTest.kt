@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.cashcraft.ads.mediation.smoke

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicText
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.compose.AdsNative
import com.cashcraft.ads.mediation.smoke.NativeRetentionTestAd.presentation
import com.cashcraft.ads.mediation.smoke.NativeRetentionTestAd.read as field

/** 真实 Compose/页面生命周期，受控句柄；不请求广告，也不声明 SDK 来源支持保留。仅用于 Debug。 */
class NativeRetentionComposeTest : InstrumentationTestCase() {
    private lateinit var activity: NativeSmokeActivity

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeSmokeActivity::class.java)
                .putExtra("active", false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeSmokeActivity
        onMain { cards().forEach(AdsNativeView::destroy) }
    }

    override fun tearDown() {
        onMain { cards().forEach(AdsNativeView::destroy); activity.setContent { }; activity.finish() }
        await("Activity final destruction") { activity.isDestroyed }
        super.tearDown()
    }

    fun testPageOwnerRestoresSamePlatformViewAndLatestCallback() {
        val owner = onMain { Owner() }
        val h = mount(owner)
        val old = h.card
        val record = onMain { presentation(old) }
        val observers = onMain { owner.registry.observerCount }
        val ad = load(h)
        onMain { repeat(10) { old.update(true, true, h.callback) }; h.version.intValue++ }
        await("callback replacement") { h.composedVersion == 1 }
        onMain {
            assertSame(old, cards().single())
            assertEquals(observers, owner.registry.observerCount)
            h.show.value = false
        }
        await("composition exit") { cards().isEmpty() && old.state == NativeState.Destroyed }
        onMain {
            assertNull(ad.view.parent)
            assertEquals(1, ad.pauses)
            assertEquals(1, h.cancellations)
            assertEquals(0, ad.destroyed)
            assertNull(field(old, "entry"))
            assertNull(field(checkNotNull(field(record, "entry")), "connection"))
            // 被移除的外层不再保存业务 lambda；旧 SDK 交付仍只属于原展示记录。
            val notifications = h.states.size
            @Suppress("UNCHECKED_CAST")
            (field(old, "callback") as (NativeState) -> Unit)(NativeState.Idle)
            assertEquals(notifications, h.states.size)
            h.version.intValue++
            h.show.value = true
        }
        await("same platform view attached to new outer") {
            cards().singleOrNull()?.let { it !== old && ad.view.parent === it } == true
        }
        onMain {
            val returned = cards().single()
            assertSame(record, presentation(returned))
            assertEquals(listOf(2), h.states.filter { it.second == NativeState.Loaded }.takeLast(1).map { it.first })
            assertEquals(observers, owner.registry.observerCount)
            assertEquals(1, h.loads.size)
            assertEquals(1, ad.renders)
            assertEquals(1, ad.resumes)
            repeat(3) { old.release(); old.destroy(); returned.setVisible(true) }
            assertSame(returned, ad.view.parent)
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertEquals(NativeState.Destroyed, returned.state)
            assertEquals(1, ad.destroyed)
            assertEquals(0, owner.registry.observerCount)
            assertNull(field(record, "subscription"))
            assertNull(field(record, "platformView"))
        }
    }

    fun testActivityOwnerExitDestroysRetainableAd() {
        val h = mount(activity)
        val ad = load(h)
        onMain { h.show.value = false }
        await("Activity-only onRelease") { h.card.state == NativeState.Destroyed }
        onMain { assertEquals(1, ad.destroyed); assertNull(ad.view.parent) }
    }

    fun testVisibleTabAndOwnerPauseRestoreWithoutLoadingAgain() {
        val owner = onMain { Owner() }
        val h = mount(owner)
        val ad = load(h)
        onMain { h.visible.value = false }
        await("hidden Tab") { ad.view.parent == null }
        onMain {
            assertEquals(NativeState.Loaded, h.card.state)
            h.visible.value = true
        }
        await("Tab resumes") { ad.view.parent === h.card }
        onMain { owner.registry.currentState = Lifecycle.State.STARTED }
        await("owner pauses") { ad.view.parent == null }
        onMain { owner.registry.currentState = Lifecycle.State.RESUMED }
        await("owner resumes") { ad.view.parent === h.card }
        onMain {
            assertEquals(2, ad.pauses)
            assertEquals(2, ad.resumes)
            assertEquals(1, ad.renders)
            assertEquals(1, h.loads.size)
            h.show.value = false
        }
        await("retained without composition") { cards().isEmpty() }
        onMain { owner.registry.currentState = Lifecycle.State.DESTROYED }
        onMain { assertEquals(1, ad.destroyed); assertEquals(0, owner.registry.observerCount) }
    }

    fun testPolicyChangeDisposesOldRecordAndActivityDestroyEndsDetachedRecord() {
        val owner = onMain { Owner() }
        val h = mount(owner)
        val oldAd = load(h)
        onMain { h.policy.value = NativeRetentionPolicy.DESTROY_ON_HIDE; h.active.value = false }
        await("policy creates new configuration") { cards().singleOrNull()?.let { it !== h.card } == true }
        onMain {
            assertEquals(1, oldAd.destroyed)
            assertEquals(NativeState.Destroyed, h.card.state)
            assertEquals(NativeState.Idle, cards().single().state)
            cards().single().destroy()
            activity.setContent { }
        }
        val second = mount(owner)
        val retained = load(second)
        onMain { second.show.value = false }
        await("detached record") { cards().isEmpty() }
        val oldActivity = activity
        val monitor = instrumentation.addMonitor(NativeSmokeActivity::class.java.name, null, false)
        try {
            onMain { oldActivity.recreate() }
            activity = monitor.waitForActivityWithTimeout(8_000) as? NativeSmokeActivity
                ?: error("Activity recreation timed out")
        } finally { instrumentation.removeMonitor(monitor) }
        await("recreation destroys detached record") { oldActivity.isDestroyed && retained.destroyed == 1 }
        onMain {
            assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
            assertEquals(0, owner.registry.observerCount)
            assertSame(oldActivity, retained.view.context)
            assertNull(retained.view.parent)
            assertFalse(cards().any { it === second.card })
        }
    }

    fun testAnimatedExitAndReturnDoNotGrowRecordsOrObservers() {
        val owner = onMain { Owner() }
        val h = mount(owner, animated = true)
        val ad = load(h)
        val observers = onMain { owner.registry.observerCount }
        repeat(3) {
            onMain { h.show.value = false }
            await("animated exit completes") { cards().isEmpty() }
            onMain { h.show.value = true }
            await("animated return reattaches original child") { cards().singleOrNull() === ad.view.parent }
            onMain {
                assertEquals(observers, owner.registry.observerCount)
                assertEquals(1, h.loads.size)
                assertEquals(1, ad.renders)
                assertEquals(0, ad.destroyed)
            }
        }
        onMain { owner.registry.currentState = Lifecycle.State.DESTROYED }
        onMain { assertEquals(1, ad.destroyed); assertEquals(0, owner.registry.observerCount) }
    }

    fun testNavigationBackStackRetainsThenPopDestroysOriginalRecord() {
        val h = Host()
        lateinit var nav: NavHostController
        lateinit var entry: NavBackStackEntry
        onMain {
            activity.setContent {
                val controller = rememberNavController()
                NavHost(controller, startDestination = "home") {
                    composable("home") { page ->
                        AdsNative(
                            request = h.request, lifecycleOwner = page,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            active = h.active.value,
                            retentionPolicy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE,
                        )
                        SideEffect { entry = page }
                    }
                    composable("details") { BasicText("Details") }
                }
                SideEffect { nav = controller; h.composedVersion = 0 }
            }
        }
        await("navigation home") {
            h.composedVersion == 0 && cards().singleOrNull()?.let { it.state == NativeState.Idle && it.width > 0 } == true
        }
        onMain {
            h.card = cards().single()
            NativeRetentionTestAd.install(h.card, h.loads) { h.cancellations++ }
            h.active.value = true
        }
        await("navigation controlled load") { h.loads.size == 1 }
        val ad = load(h)
        val record = onMain { presentation(h.card) }
        val page = onMain { entry }
        onMain { nav.navigate("details") }
        await("home retained in actual back stack") { cards().isEmpty() && page.lifecycle.currentState == Lifecycle.State.CREATED }
        onMain { assertEquals(0, ad.destroyed); assertNull(ad.view.parent); nav.popBackStack() }
        await("actual page entry returns") { cards().singleOrNull()?.let { ad.view.parent === it } == true }
        onMain {
            assertSame(page, entry)
            assertSame(record, presentation(cards().single()))
            assertEquals(1, ad.renders)
            assertEquals(1, h.loads.size)
            nav.navigate("details") { popUpTo("home") { inclusive = true } }
        }
        await("pop ends retained record") { page.lifecycle.currentState == Lifecycle.State.DESTROYED && ad.destroyed == 1 }
        onMain { assertNull(field(record, "subscription")); assertNull(ad.view.parent) }
    }

    private fun mount(owner: LifecycleOwner, animated: Boolean = false): Host {
        val h = Host()
        onMain {
            activity.setContent {
                val version = h.version.intValue
                val content: @Composable () -> Unit = {
                    AdsNative(
                        request = h.request, lifecycleOwner = owner,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        active = h.active.value, visible = h.visible.value,
                        retentionPolicy = h.policy.value,
                        onStateChanged = { h.states += version to it },
                    )
                }
                if (animated) AnimatedVisibility(h.show.value) { content() }
                else if (h.show.value) content()
                SideEffect { h.composedVersion = version }
            }
        }
        await("inactive Compose container") {
            h.composedVersion == 0 && cards().singleOrNull()?.let { it.width > 0 && it.state == NativeState.Idle } == true
        }
        onMain {
            h.card = cards().single()
            // 只替换 SDK readiness/load，保留生产 render、owner、窗口资格和连接回调。
            NativeRetentionTestAd.install(h.card, h.loads) { h.cancellations++ }
            h.active.value = true
        }
        await("one controlled request") { h.loads.size == 1 }
        return h
    }

    private fun load(h: Host): NativeRetentionTestAd {
        val ad = NativeRetentionTestAd()
        onMain { ad.deliver(h.loads.single()) }
        await("controlled ad rendered") { h.card.state == NativeState.Loaded && ad.view.parent === h.card }
        return ad
    }

    private class Host {
        val request = NativeRequest("retention_compose")
        val show = mutableStateOf(true)
        val active = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val policy = mutableStateOf(NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE)
        val version = mutableIntStateOf(0)
        var composedVersion = -1
        lateinit var card: AdsNativeView
        val loads = mutableListOf<Any>()
        var cancellations = 0
        val states = mutableListOf<Pair<Int, NativeState>>()
        val callback: (NativeState) -> Unit = { states += version.intValue to it }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun cards(): List<AdsNativeView> = buildList {
        fun visit(view: View) {
            if (view is AdsNativeView) add(view)
            else if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(activity.window.decorView)
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $description")
    }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
