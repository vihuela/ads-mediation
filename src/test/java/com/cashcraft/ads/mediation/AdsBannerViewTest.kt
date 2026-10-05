package com.cashcraft.ads.mediation

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.cashcraft.ads.mediation.internal.AdLoadClock
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.BannerSlot
import com.cashcraft.ads.mediation.internal.admob.AdMobBannerEvents
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import kotlin.math.ceil

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
    fun `unsupported TopOn reports the shared show failure without creating any SDK children`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = PageOwner()
        val states = mutableListOf<BannerState>()
        val failures = mutableListOf<AdShowResult>()
        val view = AdsBannerView(activity, owner, request().copy(platform = AdPlatform.TOPON)) { state ->
            states += state
            if (state is AdShowResult.Failed) failures += state
        }
        activity.setContentView(view)
        assertEquals(AdShowResult.Failed("topon_banner_not_supported"), states.last())
        assertSame(states.last(), failures.single())
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

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    @Suppress("DEPRECATION")
    fun `standard adaptive reserves compact full width space without clipping at fractional density`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val compact = AdsBannerView(activity, PageOwner(), request().copy(size = BannerSize.StandardAnchoredAdaptive), active = false)
        val large = AdsBannerView(activity, PageOwner(), request().copy(size = BannerSize.AnchoredAdaptive), active = false)
        try {
            val widthPx = 1080
            for (banner in listOf(compact, large)) {
                banner.visibility = View.INVISIBLE
                banner.setPadding(21, 3, 21, 7)
                banner.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                assertEquals(widthPx, banner.measuredWidth)
            }
            val density = activity.resources.displayMetrics.density
            val contentWidthDp = ((widthPx - 42) / density).toInt()
            val expected = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, contentWidthDp)
            assertEquals(contentWidthDp, expected.width)
            assertEquals(ceil(expected.height * density.toDouble()).toInt() + 10, compact.measuredHeight)
            assertTrue("Standard adaptive must stay shorter than Large", compact.measuredHeight < large.measuredHeight)
        } finally {
            compact.destroy()
            large.destroy()
        }
    }

    @Test
    fun `refresh requests layout for the visible ad but ignores an obsolete generation`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val banner = AdsBannerView(activity, PageOwner(), request())
        val child = AdView(activity)
        fun field(name: String) = AdsBannerView::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("adView").set(banner, child)
        val generation = field("generation").getLong(banner)
        val observed = mutableListOf<AdEvent>()
        val listener = AdEventListener(observed::add)
        val slot = BannerSlot(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB,
            "page", "test-unit", "slot", 1L, revenueListener = AdRevenueListener {})
        val load = AdLoadSession(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
            "page", "test-unit", "request", "request", 1L, null, 0L, AdLoadClock { 1L }, slotId = "slot")
        val relay = AdMobBannerEvents(slot, load)
        var refresh: BannerAdRefreshCallback? = null
        var eventCallback: BannerAdEventCallback? = null
        val ad = Proxy.newProxyInstance(BannerAd::class.java.classLoader, arrayOf(BannerAd::class.java)) { _, method, args ->
            if (method.name == "setAdEventCallback") eventCallback = args?.get(0) as BannerAdEventCallback
            if (method.name == "setBannerAdRefreshCallback") refresh = args?.get(0) as BannerAdRefreshCallback
            null
        } as BannerAd
        val companion = checkNotNull(field("Companion").get(null))
        companion.javaClass.getDeclaredMethod("installCallbacks", BannerAd::class.java,
            AdMobBannerEvents::class.java, WeakReference::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(companion, ad, relay, WeakReference(banner), generation)
        try {
            checkNotNull(eventCallback).onAdDismissedFullScreenContent()
            assertTrue(observed.none { it.name == AdEventName.DISMISS })
            shadowOf(Looper.getMainLooper()).idle()
            child.layout(0, 0, 320, 50)
            assertEquals(View.VISIBLE, child.visibility)
            assertFalse(child.isLayoutRequested)
            checkNotNull(refresh).onAdRefreshed()
            assertFalse(child.isLayoutRequested) // SDK callbacks must dispatch layout to main.
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A refresh must lay out replacement content even when already visible", child.isLayoutRequested)

            child.layout(0, 0, 320, 50)
            checkNotNull(refresh).onAdRefreshed()
            field("generation").setLong(banner, generation + 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("A queued old refresh must not touch a replacement ad", child.isLayoutRequested)
        } finally {
            banner.destroy()
            assertTrue(observed.none { it.name == AdEventName.DISMISS })
        }
    }

    @Test
    fun `page destruction and explicit refresh terminate only unexposed banner opportunities`() {
        for (destroy in listOf(false, true)) for (exposed in listOf(false, true)) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val view = AdsBannerView(activity, PageOwner(), request(), active = false)
            val observed = mutableListOf<AdEvent>()
            val slot = BannerSlot(AdEventListener(observed::add), AdPlatform.ADMOB, AdMediationMode.ADMOB,
                "page", "unit", "slot", 1)
            val display = checkNotNull(slot.newDisplay("response"))
            AdsBannerView::class.java.getDeclaredField("slot").apply { isAccessible = true }.set(view, slot)
            if (exposed) display.impression()
            if (destroy) view.destroy() else view.refresh()
            assertEquals(destroy, slot.isEnded)
            view.destroy()
            val failures = observed.filter { it.name == AdEventName.SHOW_FAIL }
            assertEquals(if (exposed) 0 else 1, failures.size)
            if (!exposed) {
                assertEquals(if (destroy) "scene_inactive" else "cancelled", failures.single().reason)
                assertEquals(display.sessionId, failures.single().sessionId)
            }
            display.paid(1L, "USD", null, 1L)
            assertEquals(display.sessionId, observed.single { it.name == AdEventName.IMPRESSION }.sessionId)
            assertTrue(observed.none { it.name == AdEventName.DISMISS })
        }
    }

    @Test
    fun `临时隐藏暂停及显式刷新保留页面周期而停用会结束周期`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val owner = PageOwner()
        val view = AdsBannerView(controller.get(), owner, request())
        val observed = mutableListOf<AdEvent>()
        val slot = BannerSlot(AdEventListener(observed::add), AdPlatform.ADMOB, AdMediationMode.ADMOB,
            "page", "unit", "page-cycle", 1)
        AdsBannerView::class.java.getDeclaredField("slot").apply { isAccessible = true }.set(view, slot)
        try {
            checkNotNull(slot.newDisplay("first")).apply {
                impression()
                paid(1L, "USD", null, 1L)
            }
            view.visibility = View.INVISIBLE
            owner.registry.currentState = Lifecycle.State.STARTED
            controller.pause()
            controller.resume()
            owner.registry.currentState = Lifecycle.State.RESUMED
            view.visibility = View.VISIBLE
            view.refresh()
            assertFalse(slot.isEnded)
            slot.prepareForLoad()
            checkNotNull(slot.newDisplay("second")).apply {
                impression()
                paid(2L, "USD", null, 2L)
            }
            assertEquals(1, observed.count { it.name == AdEventName.POSITION })
            val impressions = observed.filter { it.name == AdEventName.IMPRESSION }
            assertEquals(listOf(1L, 2L), impressions.map { it.analyticsParameters()["refresh_index"] })
            assertEquals(listOf("page-cycle", "page-cycle"), impressions.map { it.analyticsParameters()["ad_session_id"] })
            view.setActive(false)
            assertTrue(slot.isEnded)
            view.setActive(true)
            assertNull(slot.newDisplay("old-cycle-late-response"))
        } finally {
            view.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `SDK load failures preserve classifications and codes across load and display events`() {
        val cases = mapOf(
            LoadAdError.ErrorCode.NO_FILL to "no_fill",
            LoadAdError.ErrorCode.TIMEOUT to "timeout",
            LoadAdError.ErrorCode.CANCELLED to "cancelled",
            LoadAdError.ErrorCode.INTERNAL_ERROR to "ad_error",
        )
        for ((code, reason) in cases) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val states = mutableListOf<BannerState>()
            val view = AdsBannerView(activity, PageOwner(), request(), onState = states::add)
            fun field(name: String) = AdsBannerView::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("adView").set(view, AdView(activity))
            val generation = field("generation").getLong(view)
            val observed = mutableListOf<AdEvent>()
            val listener = AdEventListener(observed::add)
            val slot = BannerSlot(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, "page", "test-unit", "slot", 1)
            val load = AdLoadSession(listener, AdPlatform.ADMOB, AdMediationMode.ADMOB, AdFormat.BANNER,
                "page", "test-unit", "request", "request", 1, null, 0, AdLoadClock { 1 })
            val relay = AdMobBannerEvents(slot, load)
            field("slot").set(view, slot)
            field("events").set(view, relay)
            val companion = checkNotNull(field("Companion").get(null))
            @Suppress("UNCHECKED_CAST")
            val callback = companion.javaClass.getDeclaredMethod("loadCallback", WeakReference::class.java,
                java.lang.Long.TYPE, AdMobBannerEvents::class.java, Function1::class.java)
                .apply { isAccessible = true }.invoke(companion, WeakReference(view), generation, relay, null) as AdLoadCallback<BannerAd>
            try {
                load.request()
                callback.onAdFailedToLoad(LoadAdError(code, "SDK diagnostic message", null))
                callback.onAdFailedToLoad(LoadAdError(code, "duplicate", null))
                shadowOf(Looper.getMainLooper()).idle()
                val failedLoad = observed.single { it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) }
                val failedDisplay = observed.single { it.name == AdEventName.SHOW_FAIL }
                assertEquals(reason, failedLoad.reason)
                assertEquals(reason, failedDisplay.reason)
                assertEquals(code.name, failedLoad.errorCode)
                assertEquals(code.name, failedDisplay.errorCode)
                assertEquals("request", failedLoad.requestId)
                assertEquals(observed.single { it.name == AdEventName.POSITION }.sessionId, failedDisplay.sessionId)
                assertEquals(AdShowResult.Failed("SDK diagnostic message"), states.last())
            } finally { view.destroy() }
        }
    }

    private fun request() = BannerRequest(AdPlatform.ADMOB, "test-unit", "page", BannerSize.Standard320x50)

    private class PageOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
