package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AdPolicyChecker
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.AdUsageStore
import com.cashcraft.ads.mediation.internal.ShadowMMKV
import com.cashcraft.ads.mediation.internal.BannerProviderReadiness
import com.cashcraft.ads.mediation.internal.BannerReadiness
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import java.lang.reflect.Proxy
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [33], manifest = Config.NONE,
    shadows = [ShadowBackgroundBannerAdView::class, ShadowMMKV::class],
    instrumentedPackages = ["com.google.android.libraries.ads.mobile.sdk.banner.AdView"])
class BannerBackgroundLoadTest {
    private val savedAds = listOf("application", "config", "umpConsentManager", "policyChecker")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
    private val providers = ReflectionHelpers.getStaticField<BannerProviderReadiness>(Ads::class.java, "bannerProviders")
    private val readiness = ReflectionHelpers.getField<MutableMap<AdPlatform, BannerReadiness>>(providers, "states")
    private val savedReadiness = readiness.toMap()
    private val events = mutableListOf<AdEvent>()
    private val fixtures = mutableListOf<Fixture>()
    private val app = RuntimeEnvironment.getApplication()
    private val monitorFields = listOf("installedApplication", "resumedActivity", "isAppInForeground")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(AdLifecycleMonitor::class.java, it) }
    private val lifecycleCallbacks = ReflectionHelpers.getStaticField<Application.ActivityLifecycleCallbacks>(
        AdLifecycleMonitor::class.java, "callbacks")

    @Before fun prepare() {
        ShadowMMKV.reset()
        ShadowBackgroundBannerAdView.loads.clear()
        ReflectionHelpers.setStaticField(Ads::class.java, "application", app)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false,
            eventListener = AdEventListener(events::add)))
        ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
            UmpConsentManager(app, UmpConsentConfig(enabled = false), false, "test"))
        ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", null)
        readiness.clear()
        providers.configure(AdMobProviderConfig(AdMobIds.TEST))
        (monitorFields["installedApplication"] as? Application)?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "installedApplication", null)
        AdLifecycleMonitor.install(app)
    }

    @After fun restore() {
        fixtures.forEach { it.banner.destroy(); it.controller.pause().stop().destroy() }
        drain()
        app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        monitorFields.forEach { (name, value) -> ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, name, value) }
        (monitorFields["installedApplication"] as? Application)?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        readiness.clear()
        readiness.putAll(savedReadiness)
        savedAds.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
        ShadowBackgroundBannerAdView.loads.clear()
    }

    @Test fun `background before child layout loads once and success waits for foreground without reload`() {
        for (gate in Gate.entries) {
            val fixture = fixture()
            fixture.banner.setActive(true)
            val child = pendingChild(fixture)
            gate.hide(fixture)
            assertFalse("$gate must leave child layout pending", child.isLaidOut)
            finishLayout(fixture, child)
            assertEquals(1, ShadowBackgroundBannerAdView.loads.count { it.request.adUnitId == fixture.unit })
            val load = ShadowBackgroundBannerAdView.loads.last()
            assertFalse(events.any { it.adUnitId == fixture.unit && it.reason == "cancelled" })
            val ad = TestAd()
            load.succeed(ad.value)
            drain()
            assertEquals(BannerState.Ready, fixture.states.last())
            assertEquals(View.INVISIBLE, child.visibility)
            gate.show(fixture)
            drain()
            assertEquals("$gate: ${displayState(fixture)}", View.VISIBLE, child.visibility)
            assertEquals(1, ShadowBackgroundBannerAdView.loads.count { it.request.adUnitId == fixture.unit })
            assertEquals(0, ad.destroyed)
            fixture.banner.destroy()
        }
    }

    @Test fun `background readiness completion and initial ready request can load but cannot show`() {
        for (readyFirst in listOf(false, true)) {
            readiness[AdPlatform.ADMOB] = BannerReadiness.INITIALIZING
            if (readyFirst) providers.completed(AdPlatform.ADMOB, true)
            val fixture = fixture(completeReadiness = false)
            Gate.BACKGROUND.hide(fixture)
            fixture.banner.setActive(true)
            if (!readyFirst) {
                assertEquals(BannerState.Waiting, fixture.states.last())
                assertEquals(0, fixture.banner.childCount)
                providers.completed(AdPlatform.ADMOB, true)
            }
            val child = pendingChild(fixture)
            finishLayout(fixture, child)
            assertEquals(1, ShadowBackgroundBannerAdView.loads.count { it.request.adUnitId == fixture.unit })
            ShadowBackgroundBannerAdView.loads.last().succeed(TestAd().value)
            drain()
            assertEquals(BannerState.Ready, fixture.states.last())
            assertEquals(View.INVISIBLE, child.visibility)
            Gate.BACKGROUND.show(fixture)
            drain()
            assertEquals(displayState(fixture), View.VISIBLE, child.visibility)
            assertEquals(1, ShadowBackgroundBannerAdView.loads.count { it.request.adUnitId == fixture.unit })
            fixture.banner.destroy()
        }
    }

    @Test fun `destroy and inactive before layout cancel without reaching SDK`() {
        for (destroy in listOf(false, true)) {
            val fixture = fixture()
            fixture.banner.setActive(true)
            val child = pendingChild(fixture)
            terminate(fixture, destroy)
            finishLayout(fixture, child)
            assertTrue(ShadowBackgroundBannerAdView.loads.none { it.request.adUnitId == fixture.unit })
            assertEquals(if (destroy) BannerState.Destroyed else BannerState.Inactive, fixture.states.last())
            assertEquals(0, fixture.banner.childCount)
        }
    }

    @Test fun `late success after destroy or inactive cannot revive old generation`() {
        for (destroy in listOf(false, true)) {
            val fixture = fixture()
            fixture.banner.setActive(true)
            finishLayout(fixture, pendingChild(fixture))
            val load = ShadowBackgroundBannerAdView.loads.last()
            terminate(fixture, destroy)
            val previousStates = fixture.states.toList()
            val ad = TestAd()
            load.succeed(ad.value)
            drain()
            assertEquals(previousStates, fixture.states)
            assertEquals(0, fixture.banner.childCount)
            assertEquals(1, ad.destroyed)
            assertEquals(1, ShadowBackgroundBannerAdView.loads.count { it.request.adUnitId == fixture.unit })
        }
    }

    @Test fun `consent required prevents background SDK request`() {
        ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
            UmpConsentManager(app, UmpConsentConfig(enabled = true), false, "test"))
        val fixture = fixture()
        Gate.BACKGROUND.hide(fixture)
        fixture.banner.setActive(true)
        drain()
        assertEquals(BannerReadiness.CONSENT_REQUIRED, Ads.bannerReadiness(AdPlatform.ADMOB))
        assertEquals(BannerState.Waiting, fixture.states.last())
        assertEquals(0, fixture.banner.childCount)
        assertTrue(ShadowBackgroundBannerAdView.loads.isEmpty())
    }

    @Test fun `hidden loading and registration leave last quota available until display`() {
        for (typed in listOf(false, true)) for (gate in Gate.entries) {
            val checker = installQuota(typed)
            val other = AdPolicyRequest("other-page", sceneType = AdSceneType.BANNER)
            val fixture = fixture()
            gate.hide(fixture)
            fixture.banner.setActive(true)
            val child = pendingChild(fixture)
            finishLayout(fixture, child)
            assertEquals("$typed $gate: loading must not reserve", AdPolicyCheckResult.Passed, checker.check(other))
            ShadowBackgroundBannerAdView.loads.last().succeed(TestAd().value)
            drain()
            assertEquals(BannerState.Ready, fixture.states.last())
            assertEquals(View.INVISIBLE, child.visibility)
            assertEquals("$typed $gate: registration must not reserve", AdPolicyCheckResult.Passed, checker.check(other))

            gate.show(fixture)
            drain()
            assertEquals(displayState(fixture), View.VISIBLE, child.visibility)
            assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT), checker.check(other))
            assertEquals(1, ShadowBackgroundBannerAdView.loads.count { it.request.adUnitId == fixture.unit })
            fixture.banner.destroy()
            drain()
            assertEquals("An unexposed display releases its reservation", AdPolicyCheckResult.Passed, checker.check(other))
        }
    }

    @Test fun `foreground page can use last quota while another banner loads hidden`() {
        val checker = installQuota(typed = true)
        val hidden = fixture()
        Gate.BACKGROUND.hide(hidden)
        hidden.banner.setActive(true)
        finishLayout(hidden, pendingChild(hidden))
        val visible = fixture()
        visible.banner.setActive(true)
        val child = pendingChild(visible)
        finishLayout(visible, child)
        val other = AdPolicyRequest("third-page", sceneType = AdSceneType.BANNER)
        assertEquals("Even visible in-flight loading must not reserve", AdPolicyCheckResult.Passed, checker.check(other))
        ShadowBackgroundBannerAdView.loads.last().succeed(TestAd().value)
        drain()
        assertEquals(BannerState.Ready, visible.states.last())
        assertEquals(View.VISIBLE, child.visibility)
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT), checker.check(other))
    }

    @Test fun `quota consumed after hidden registration is rechecked before making banner visible`() {
        val checker = installQuota(typed = true)
        val fixture = fixture()
        Gate.FOCUS.hide(fixture)
        fixture.banner.setActive(true)
        val child = pendingChild(fixture)
        finishLayout(fixture, child)
        val ad = TestAd()
        ShadowBackgroundBannerAdView.loads.last().succeed(ad.value)
        drain()
        assertEquals(BannerState.Ready, fixture.states.last())
        checker.impression("other-page", AdSceneType.BANNER)
        Gate.FOCUS.show(fixture)
        drain()
        assertEquals(AdShowResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT), fixture.states.last())
        assertEquals(View.INVISIBLE, child.visibility)
        assertEquals(0, fixture.banner.childCount)
        assertEquals(1, ad.destroyed)
        assertEquals(1, events.count { it.adUnitId == fixture.unit && it.name == AdEventName.SHOW_FAIL })
    }

    private fun installQuota(typed: Boolean): AdPolicyChecker {
        ShadowMMKV.reset()
        return AdPolicyChecker(AdUsageStore(app, 0L)).also {
            it.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 1,
                sceneQuotas = if (typed) mapOf(AdSceneType.BANNER to AdSceneQuota(true, 1, 10)) else null))
            ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", it)
        }
    }

    private fun fixture(completeReadiness: Boolean = true): Fixture {
        if (completeReadiness) providers.completed(AdPlatform.ADMOB, true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val activity = controller.get()
        val owner = PageOwner()
        val states = mutableListOf<BannerState>()
        val unit = "background-unit-${fixtures.size}"
        val banner = AdsBannerView(activity, owner,
            BannerRequest(AdPlatform.ADMOB, unit, "home", BannerSize.Standard320x50),
            active = false, onState = states::add)
        // Hold child traversal until the test explicitly completes it.
        val host = object : FrameLayout(activity) { override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit }
        host.addView(banner)
        activity.setContentView(host)
        drain()
        banner.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        banner.layout(0, 0, 320, banner.measuredHeight)
        assertTrue(banner.isAttachedToWindow)
        assertTrue(banner.hasWindowFocus())
        return Fixture(controller, owner, banner, states, unit).also {
            fixtures += it
            Gate.WINDOW.show(it)
        }
    }

    private fun pendingChild(fixture: Fixture): View {
        assertEquals(BannerState.Loading, fixture.states.last())
        assertEquals(1, fixture.banner.childCount)
        return fixture.banner.getChildAt(0).also {
            assertFalse(it.isLaidOut)
            assertEquals(View.INVISIBLE, it.visibility)
            assertTrue(ShadowBackgroundBannerAdView.loads.none { load -> load.request.adUnitId == fixture.unit })
        }
    }

    private fun finishLayout(fixture: Fixture, child: View) {
        val width = fixture.banner.width
        val height = fixture.banner.measuredHeight
        child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        child.layout(0, 0, width, height)
        drain()
    }

    private fun terminate(fixture: Fixture, destroy: Boolean) {
        if (destroy) fixture.banner.destroy() else fixture.banner.setActive(false)
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun displayState(f: Fixture) = "owner=${f.owner.lifecycle.currentState}, " +
        "current=${AdLifecycleMonitor.currentActivity === f.controller.get()}, " +
        "focus=${f.banner.hasWindowFocus()}, shown=${f.banner.isShown}, " +
        "window=${f.banner.windowVisibility}, attached=${f.banner.isAttachedToWindow}"

    private class PageOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private data class Fixture(val controller: ActivityController<Activity>, val owner: PageOwner,
        val banner: AdsBannerView, val states: MutableList<BannerState>, val unit: String)

    private enum class Gate {
        BACKGROUND, PAGE, FOCUS, WINDOW;
        fun hide(f: Fixture) {
            when (this) {
                BACKGROUND -> { f.controller.pause(); f.owner.registry.currentState = Lifecycle.State.CREATED }
                PAGE -> f.owner.registry.currentState = Lifecycle.State.CREATED
                FOCUS -> f.controller.windowFocusChanged(false)
                WINDOW -> windowVisibility(f, View.INVISIBLE)
            }
        }
        fun show(f: Fixture) {
            when (this) {
                BACKGROUND -> {
                    f.controller.resume().visible().windowFocusChanged(true)
                    f.owner.registry.currentState = Lifecycle.State.RESUMED
                    windowVisibility(f, View.VISIBLE)
                }
                PAGE -> f.owner.registry.currentState = Lifecycle.State.RESUMED
                FOCUS -> f.controller.windowFocusChanged(true)
                WINDOW -> windowVisibility(f, View.VISIBLE)
            }
        }
        private fun windowVisibility(f: Fixture, visibility: Int) {
            // WindowManager owns AttachInfo; dispatch its real View callback after changing the window flag.
            val info = ReflectionHelpers.getField<Any>(f.banner, "mAttachInfo")
            ReflectionHelpers.setField(info, "mWindowVisibility", visibility)
            f.controller.get().window.decorView.dispatchWindowVisibilityChanged(visibility)
            assertEquals(visibility, f.banner.windowVisibility)
        }
    }

    private class TestAd {
        var destroyed = 0
        val value = Proxy.newProxyInstance(BannerAd::class.java.classLoader, arrayOf(BannerAd::class.java)) {
            _, method, _ -> if (method.name == "destroy") { destroyed++; null } else null
        } as BannerAd
    }
}

/** Only SDK operations are shadowed; production attachment, measurement and lifecycle stay real. */
@Implements(AdView::class, isInAndroidSdk = false)
class ShadowBackgroundBannerAdView : ShadowViewGroup() {
    private var registered: BannerAd? = null

    @Implementation fun loadAd(request: BannerAdRequest, callback: AdLoadCallback<BannerAd>) {
        loads += Load(request, callback, this)
    }
    @Implementation fun registerBannerAd(ad: BannerAd, activity: Activity) { registered = ad }
    @Implementation fun unregisterBannerAd(): BannerAd? = registered.also { registered = null }
    @Implementation fun destroy() { registered?.destroy(); registered = null }

    data class Load(val request: BannerAdRequest, val callback: AdLoadCallback<BannerAd>,
        val loader: ShadowBackgroundBannerAdView) {
        fun succeed(ad: BannerAd) { loader.registered = ad; callback.onAdLoaded(ad) }
    }
    companion object { val loads = mutableListOf<Load>() }
}
