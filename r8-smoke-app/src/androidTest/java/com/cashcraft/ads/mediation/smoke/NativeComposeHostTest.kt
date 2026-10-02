package com.cashcraft.ads.mediation.smoke

import android.content.Intent
import android.app.Dialog
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdsNativeView
import com.cashcraft.ads.mediation.NativeLayout
import com.cashcraft.ads.mediation.NativeRequest
import com.cashcraft.ads.mediation.NativeState
import com.cashcraft.ads.mediation.compose.AdsNative
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState

/** Real AndroidView/Compose/Navigation ownership checks; inactive cards make no ad requests. */
@Suppress("DEPRECATION")
class NativeComposeHostTest : InstrumentationTestCase() {
    private lateinit var activity: NativeSmokeActivity

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeSmokeActivity::class.java)
                .putExtra("active", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeSmokeActivity
        // 测试将替换整棵宿主内容；原 View 卡片须先最终释放其业务位置。
        onMain { cards().forEach(AdsNativeView::destroy) }
    }

    override fun tearDown() {
        onMain { activity.finish() }
        super.tearDown()
    }

    fun testStableIdentityLatestCallbackAndReplacementRelease() {
        val request = mutableStateOf(NativeRequest("compose_host"))
        val layout = mutableStateOf<NativeLayout>(NativeLayout.Default)
        val owner = mutableStateOf(onMain { Owner() })
        val version = mutableIntStateOf(0)
        val show = mutableStateOf(true)
        val states = mutableListOf<Pair<Int, NativeState>>()
        var renderedVersion = -1
        onMain {
            activity.setContent {
                val callbackVersion = version.intValue
                if (show.value) AdsNative(
                    request = request.value,
                    layout = layout.value,
                    lifecycleOwner = owner.value,
                    active = false,
                    onStateChanged = { states += callbackVersion to it },
                )
                SideEffect { renderedVersion = callbackVersion }
            }
        }
        await("initial Compose card") { cards().size == 1 && renderedVersion == 0 }
        val original = onMain { cards().single() }
        onMain {
            request.value = request.value.copy()
            version.intValue++
        }
        await("equivalent request and latest callback applied") { renderedVersion == 1 }
        onMain { assertSame(original, cards().single()) }

        // A different owner must replace the view even while the old owner is still resumed.
        val oldOwner = owner.value
        onMain { owner.value = Owner() }
        await("owner replacement") { cards().singleOrNull()?.let { it !== original } == true }
        val afterOwner = onMain {
            assertEquals(Lifecycle.State.RESUMED, oldOwner.lifecycle.currentState)
            assertEquals(NativeState.Destroyed, original.state)
            assertTrue(states.contains(1 to NativeState.Destroyed))
            cards().single()
        }
        onMain { layout.value = NativeLayout.Custom { error("inactive factory must not execute") } }
        await("layout replacement") { cards().singleOrNull()?.let { it !== afterOwner } == true }
        val afterLayout = onMain {
            assertEquals(NativeState.Destroyed, afterOwner.state)
            cards().single()
        }
        onMain { request.value = request.value.copy(position = "new_page") }
        await("request replacement") { cards().singleOrNull()?.let { it !== afterLayout } == true }
        val afterRequest = onMain {
            assertEquals(NativeState.Destroyed, afterLayout.state)
            cards().single()
        }
        onMain { owner.value.registry.currentState = Lifecycle.State.DESTROYED }
        await("owner destroy") { afterRequest.state == NativeState.Destroyed }
        onMain {
            afterRequest.retry()
            afterRequest.setActive(true)
            assertEquals(NativeState.Destroyed, afterRequest.state)
            show.value = false
        }
        await("final onRelease") { cards().isEmpty() }
        onMain { assertEquals(NativeState.Destroyed, afterRequest.state) }
    }

    fun testBothCustomXmlFactoriesProvideIndependentDisclosureSlots() = onMain {
        // 调用真实示例工厂，核对 XML 与 binding 的连接，不请求广告。
        val factory = Class.forName("com.cashcraft.ads.mediation.smoke.NativeSmokeActivityKt")
            .getDeclaredMethod("createCustomNativeLayout", android.content.Context::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        for (compact in listOf(false, true)) {
            val first = factory.invoke(null, activity, compact) as com.cashcraft.ads.mediation.NativeLayoutBinding
            val second = factory.invoke(null, activity, compact) as com.cashcraft.ads.mediation.NativeLayoutBinding
            assertNotSame(first.root, second.root)
            listOf(first.adFrom, first.domain, first.warning).forEach { slot ->
                assertNotNull(slot)
                assertEquals(View.GONE, slot!!.visibility)
                assertEquals("", slot.text.toString())
                assertSame(slot, first.root.findViewById(slot.id))
            }
            assertNotSame(first.adFrom, second.adFrom)
            assertNotSame(first.domain, second.domain)
            assertNotSame(first.warning, second.warning)
        }
    }

    fun testPreviewWorksWithoutActivityAndDoesNotCreateCardOrCallFactory() {
        var composed = false
        var factoryCalls = 0
        onMain {
            activity.setContent {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalContext provides instrumentation.targetContext.applicationContext,
                ) {
                    AdsNative(
                        request = NativeRequest("preview"),
                        layout = NativeLayout.Custom { factoryCalls++; error("preview factory") },
                    )
                }
                SideEffect { composed = true }
            }
        }
        await("preview composition") { composed }
        onMain {
            assertEquals(0, factoryCalls)
            assertTrue(cards().isEmpty())
        }
    }

    fun testRealViewGatesDoNotLoadUntilEligibleOrDuplicateOnRemeasure() {
        await("AdMob initialized") { AdMobAds.state == AdMobState.READY }
        var loads = 0
        val owner = onMain { Owner() }
        val root = onMain { FrameLayout(activity) }
        val card = onMain {
            AdsNativeView(
                activity, owner,
                NativeRequest("gate_test"),
                active = false, visible = false,
                onStateChanged = { if (it == NativeState.Loading) loads++ },
            ).also {
                it.update(true, true) { state -> if (state == NativeState.Loading) loads++ }
                assertEquals(NativeState.Idle, it.state) // Not attached.
                owner.registry.currentState = Lifecycle.State.STARTED
                root.addView(it, FrameLayout.LayoutParams(-1, 400))
                activity.setContentView(root)
            }
        }
        await("attached card with paused owner") { card.width > 0 && root.hasWindowFocus() }
        onMain {
            assertEquals(0, loads)
            card.layoutParams = FrameLayout.LayoutParams(0, 400)
        }
        await("zero-width card") { card.width == 0 }
        onMain {
            owner.registry.currentState = Lifecycle.State.RESUMED
            repeat(5) { card.update(true, true) { state -> if (state == NativeState.Loading) loads++ } }
            assertEquals(0, card.width)
            assertEquals(0, loads)
        }
        val dialog = onMain {
            Dialog(activity).apply { setContentView(TextView(activity).apply { text = "Focus gate" }); show() }
        }
        try {
            await("dialog takes focus") { !card.hasWindowFocus() }
            onMain { card.layoutParams = FrameLayout.LayoutParams(-1, 400) }
            await("nonzero width while unfocused") { card.width > 0 }
            onMain {
                assertEquals(0, loads)
                card.setActive(false)
                dialog.dismiss()
            }
            await("focus restored while inactive") { card.hasWindowFocus() }
            onMain {
                assertEquals(0, loads)
                card.update(true, false) { state -> if (state == NativeState.Loading) loads++ }
                assertEquals(0, loads)
                card.setVisible(true)
            }
            await("one eligible request") { loads == 1 }
            onMain {
                repeat(10) { card.requestLayout(); card.setVisible(true); card.setActive(true) }
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(1, loads)
                assertTrue(card.state == NativeState.Loading || card.state == NativeState.Loaded || card.state is NativeState.Failed)
                card.destroy()
            }
        } finally {
            onMain { dialog.dismiss(); card.destroy() }
        }
    }

    fun testNavigationEntryDialogReturnAndSamePlacementCards() {
        lateinit var nav: NavHostController
        val options = NativeSmokeOptions(
            mode = SmokeMode.NAV, platform = AdPlatform.ADMOB,
            customLayout = false, compactLayout = false, twoCards = true,
            ratio = null, initialActive = false,
        )
        onMain {
            activity.setContent {
                val controller = rememberNavController()
                NativeSmokeNavigation(options, controller)
                SideEffect { nav = controller }
            }
        }
        await("two independent cards") { cards().size == 2 }
        val original = onMain { cards() }
        val entry = onMain { nav.currentBackStackEntry!! }
        onMain {
            assertNotSame(original[0], original[1])
            assertFalse(original[0].request.position == original[1].request.position)
            nav.navigate(NativeDialog)
        }
        await("dialog pauses the actual home entry") { entry.lifecycle.currentState == Lifecycle.State.STARTED }
        onMain {
            assertEquals(original, cards())
            original.forEach { assertEquals(NativeState.Idle, it.state) }
            nav.popBackStack()
        }
        await("dialog return") { entry.lifecycle.currentState == Lifecycle.State.RESUMED }
        onMain {
            assertEquals(original, cards())
            nav.navigate(NativeDetails)
        }
        await("leaving home releases both views") {
            cards().isEmpty() && original.all { it.state == NativeState.Destroyed }
        }
        onMain {
            assertEquals(Lifecycle.State.CREATED, entry.lifecycle.currentState)
            nav.popBackStack()
        }
        await("return creates new cards for retained entry") { cards().size == 2 }
        val returned = onMain {
            assertSame(entry, nav.currentBackStackEntry)
            cards().also { newCards ->
                newCards.forEach { assertFalse(original.contains(it)) }
                activity.setContent { }
            }
        }
        await("final disposal") { returned.all { it.state == NativeState.Destroyed } }
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

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
