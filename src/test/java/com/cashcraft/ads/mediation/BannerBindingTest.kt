package com.cashcraft.ads.mediation

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import com.cashcraft.ads.mediation.compose.AdsBanner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BannerBindingTest {
    private val activityController = Robolectric.buildActivity(FragmentActivity::class.java)

    @Before
    fun setup() {
        // Supply immutable config while leaving SDK readiness pending; no network or ad SDK starts.
        ReflectionHelpers.setStaticField(Ads::class.java, "application", RuntimeEnvironment.getApplication())
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false,
        ))
        activityController.setup()
    }

    @After
    fun cleanup() {
        activityController.pause().stop().destroy()
        ReflectionHelpers.setStaticField(Ads::class.java, "application", null)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", null)
    }

    @Test
    fun `Compose position entry resolves config and releases when composition is disposed`() {
        val activity = activityController.get()
        val states = mutableListOf<BannerState>()
        val compose = ComposeView(activity).apply {
            setContent { AdsBanner(position = "compose_footer", active = false, onState = states::add) }
        }
        activity.setContentView(compose)
        activityController.visible()
        compose.createComposition()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(BannerState.Inactive), states)
        compose.disposeComposition()
        assertEquals(listOf(BannerState.Inactive, BannerState.Destroyed), states)
    }

    @Test
    fun `Compose preview does not require Ads initialization or emit ad states`() {
        ReflectionHelpers.setStaticField(Ads::class.java, "config", null)
        val states = mutableListOf<BannerState>()
        val compose = ComposeView(activityController.get()).apply {
            setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AdsBanner(position = "preview_footer", onState = states::add)
                }
            }
        }
        activityController.get().setContentView(compose)
        activityController.visible()
        compose.createComposition()
        shadowOf(Looper.getMainLooper()).idle()
        compose.disposeComposition()
        assertTrue(states.isEmpty())
    }

    @Test
    fun `equal binding reuses view and updates callback while changed position replaces only its own view`() {
        val fragment = fragment()
        val container = fragment.requireView() as FrameLayout
        val sibling = View(container.context)
        container.addView(sibling)
        val oldStates = mutableListOf<BannerState>()
        val newStates = mutableListOf<BannerState>()
        val first = fragment.bindBanner(container, "footer", onState = oldStates::add)
        assertEquals(AdMobIds.TEST.bannerId, first.request.adUnitId)
        assertEquals(listOf(BannerState.Waiting), oldStates)
        assertSame(first, fragment.bindBanner(container, "footer", active = false, onState = newStates::add))
        assertEquals(listOf(BannerState.Inactive), newStates)
        assertEquals(listOf(BannerState.Waiting), oldStates)
        val replacement = fragment.bindBanner(container, "other")
        assertNotSame(first, replacement)
        assertNull(first.parent)
        assertSame(container, sibling.parent)
        assertSame(container, replacement.parent)
        assertEquals(2, container.childCount)
        first.setActive(true)
        assertEquals(listOf(BannerState.Inactive), newStates)
    }

    @Test
    fun `fragment view destruction releases and unbinds while recreated view gets a fresh banner`() {
        val fragment = fragment()
        val container = fragment.requireView() as FrameLayout
        val owner = fragment.viewLifecycleOwner
        val states = mutableListOf<BannerState>()
        val first = fragment.bindBanner(container, "footer", onState = states::add)
        val manager = activityController.get().supportFragmentManager
        manager.beginTransaction().detach(fragment).commitNow()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        assertEquals(1, states.count { it == BannerState.Destroyed })
        assertNull(first.parent)
        assertNull(container.getTag(R.id.ads_banner_binding))
        manager.beginTransaction().attach(fragment).commitNow()
        val next = fragment.bindBanner(fragment.requireView() as ViewGroup, "footer")
        assertNotSame(first, next)
        first.setActive(true)
        assertEquals(BannerState.Destroyed, states.last())
    }

    @Test
    fun `binding from an initial state callback cannot leave the replaced view attached`() {
        val fragment = fragment()
        val container = fragment.requireView() as FrameLayout
        var replacement: AdsBannerView? = null
        val first = fragment.bindBanner(container, "first") { state ->
            if (state == BannerState.Waiting) replacement = fragment.bindBanner(container, "replacement")
        }
        assertNotNull(replacement)
        assertEquals(1, container.childCount)
        assertSame(replacement, container.getChildAt(0))
        assertNull(first.parent)
        first.setActive(false)
        first.setActive(true)
        assertSame(replacement, container.getChildAt(0))
    }

    @Test
    fun `explicit destruction after replacing callback permits a fresh binding and preserves siblings`() {
        val fragment = fragment()
        val container = fragment.requireView() as FrameLayout
        val sibling = View(container.context)
        container.addView(sibling)
        val first = fragment.bindBanner(container, "footer")
        val states = mutableListOf<BannerState>()
        first.onState = states::add
        first.destroy()
        assertEquals(listOf(BannerState.Destroyed), states)
        assertNull(container.getTag(R.id.ads_banner_binding))
        assertNull(first.parent)
        assertSame(container, sibling.parent)
        assertNotSame(first, fragment.bindBanner(container, "footer"))
        assertEquals(2, container.childCount)
    }

    @Test
    fun `replacement callback can rebind during destruction and throw without losing the new binding`() {
        val fragment = fragment()
        val container = fragment.requireView() as ViewGroup
        val first = fragment.bindBanner(container, "footer")
        var replacement: AdsBannerView? = null
        var callbacks = 0
        first.onState = { state ->
            if (state == BannerState.Destroyed) {
                callbacks++
                replacement = fragment.bindBanner(container, "footer")
                error("host callback")
            }
        }
        first.destroy()
        first.destroy()
        assertEquals(1, callbacks)
        assertNotNull(replacement)
        assertNotSame(first, replacement)
        assertNull(first.parent)
        assertEquals(1, container.childCount)
        assertSame(replacement, container.getChildAt(0))
        assertSame(replacement, fragment.bindBanner(container, "footer"))
    }

    private fun fragment(): TestFragment = TestFragment().also {
        val activity = activityController.get()
        val root = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(root)
        activity.supportFragmentManager.beginTransaction().add(root.id, it).commitNow()
    }

    class TestFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(requireContext())
    }
}
