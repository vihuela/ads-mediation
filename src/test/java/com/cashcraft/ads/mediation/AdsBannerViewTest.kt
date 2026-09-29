package com.cashcraft.ads.mediation

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdsBannerViewTest {
    @Test
    fun `initially inactive hidden view reserves legal standard space and owner destruction is final`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = PageOwner()
        val states = mutableListOf<BannerState>()
        val banner = AdsBannerView(activity, owner, request(), active = false, onState = states::add)
        banner.visibility = View.INVISIBLE
        val host = FrameLayout(activity)
        val other = View(activity)
        host.addView(other)
        host.addView(banner)
        activity.setContentView(host)
        banner.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        assertTrue(banner.measuredHeight > 0)
        assertEquals(listOf(BannerState.Inactive), states)
        assertEquals(0, banner.childCount)
        banner.setActive(true)
        assertEquals(BannerState.Waiting, states.last())
        owner.registry.currentState = Lifecycle.State.DESTROYED
        banner.destroy()
        banner.setActive(false)
        banner.setActive(true)
        assertEquals(1, states.count { it == BannerState.Destroyed })
        assertEquals(0, owner.registry.observerCount)
        assertSame(host, other.parent)
        assertEquals(0, banner.childCount)
    }

    @Test
    fun `unsupported TopOn reports a failure without creating any SDK children`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = PageOwner()
        val states = mutableListOf<BannerState>()
        val view = AdsBannerView(activity, owner, request().copy(platform = AdPlatform.TOPON), onState = states::add)
        activity.setContentView(view)
        assertEquals(BannerState.Failed("topon_banner_not_supported"), states.last())
        assertEquals(0, view.childCount)
        view.destroy()
    }

    @Test
    fun `callback destroying owner during construction cannot leave an observer or revive the view`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = PageOwner()
        val states = mutableListOf<BannerState>()
        val view = AdsBannerView(activity, owner, request()) {
            states += it
            if (it == BannerState.Waiting) owner.registry.currentState = Lifecycle.State.DESTROYED
            error("host callback")
        }
        assertEquals(BannerState.Destroyed, states.last())
        assertEquals(0, owner.registry.observerCount)
        view.setActive(true)
        view.destroy()
        assertEquals(1, states.count { it == BannerState.Destroyed })
    }

    @Test
    fun `same ad unit instances keep separate page owners and final state`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val oldOwner = PageOwner()
        val newOwner = PageOwner()
        val oldStates = mutableListOf<BannerState>()
        val newStates = mutableListOf<BannerState>()
        val oldView = AdsBannerView(activity, oldOwner, request(), onState = oldStates::add)
        val newView = AdsBannerView(activity, newOwner, request(), onState = newStates::add)
        oldOwner.registry.currentState = Lifecycle.State.DESTROYED
        assertEquals(BannerState.Destroyed, oldStates.last())
        assertEquals(BannerState.Waiting, newStates.last())
        assertEquals(1, newOwner.registry.observerCount)
        newView.destroy()
        oldView.destroy()
    }

    @Test
    fun `unchanged outer measurement still lays out asynchronously resized native content`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        // Model a host that requests measurement itself but skips placement for unchanged bounds.
        val host = object : FrameLayout(activity) { override fun requestLayout() = Unit }
        val banner = AdsBannerView(activity, PageOwner(), request(), active = false)
        val content = FrameLayout(activity).apply { minimumWidth = 20; minimumHeight = 20 }
        banner.addView(content, FrameLayout.LayoutParams(-2, -2))
        host.addView(banner)
        activity.setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST)
        banner.measure(width, height)
        banner.layout(0, 0, banner.measuredWidth, banner.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(banner.isAttachedToWindow)
        assertEquals(20, content.width)

        content.minimumWidth = 80
        banner.measure(width, height)
        assertEquals(20, content.width) // The host intentionally did not call layout again.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(80, content.width)
        assertFalse(banner.isLayoutRequested)
        banner.destroy()
    }

    @Test
    @Config(qualifiers = "420dpi")
    fun `placeholder never rounds below the requested dp height at fractional density`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val banner = AdsBannerView(activity, PageOwner(), request(), active = false)
        banner.visibility = View.INVISIBLE
        banner.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        assertTrue(banner.measuredHeight / activity.resources.displayMetrics.density >= 50f)
        banner.destroy()
    }

    private fun request() = BannerRequest(AdPlatform.ADMOB, "test-unit", "page", BannerSize.Standard320x50)

    private class PageOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
