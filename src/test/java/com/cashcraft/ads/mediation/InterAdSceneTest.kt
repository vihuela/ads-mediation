@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.*
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/** Calls the public scene entry with local inventory; no advertising SDK is initialized. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [ShadowMMKV::class])
class InterAdSceneTest {
    private val fields = listOf("application", "config", "umpConsentManager", "initializationStage", "nativeLogger", "policyChecker", "policyUpdatePosted")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
    private val providerStarted = ReflectionHelpers.getStaticField<AtomicBoolean>(Ads::class.java, "providerInitializationStarted")
    private val wasStarted = providerStarted.get()
    private val initialAdMobState = AdMobAds.state
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private val tasks = mutableListOf<AdTask>()
    private val results = mutableListOf<AdShowResult>()
    private val loading = mutableListOf<Boolean>()
    private val events = mutableListOf<AdEvent>()
    private var config = AdsConfig(
        AdMobProviderConfig(AdMobIds.TEST),
        loggingEnabled = true, logTag = "InterSceneTest", autoShowAppOpen = false,
        eventListener = { events += it },
        nativeFullScreenLayout = NativeLayout.Custom { error("not rendered by this test") },
    )

    @Before fun prepare() {
        resetInstallation()
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Ads::class.java, "application", app)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        ShadowMMKV.reset()
        ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", AdPolicyChecker(AdUsageStore(app, 0)))
        ReflectionHelpers.setStaticField(Ads::class.java, "policyUpdatePosted", true)
        AdLifecycleMonitor.install(app)
        controller.setup().visible().windowFocusChanged(true)
        ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
            UmpConsentManager(app, UmpConsentConfig(enabled = false), false, "test"))
        val stage = fields.getValue("initializationStage")!!.javaClass.enumConstants!!
            .single { (it as Enum<*>).name == "COMPLETE" }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage", stage)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        ReflectionHelpers.setStaticField(Ads::class.java, "nativeLogger", AdsModuleLogger(true, "InterSceneTest"))
        providerStarted.set(true)
        ShadowLog.clear()
    }

    @After fun restore() {
        tasks.forEach(AdTask::cancel)
        NativeFullScreenSession.current?.complete()
        NativeAdCache.clear()
        controller.pause().stop().destroy()
        resetInstallation()
        fields.forEach { (key, value) -> ReflectionHelpers.setStaticField(Ads::class.java, key, value) }
        providerStarted.set(wasStarted)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", initialAdMobState)
    }

    @Test fun `three second default reaches cached native with original position and one final result`() {
        cacheNative()
        val task = start()
        assertEquals(listOf(true), loading)
        advance(2_999)
        assertNull(NativeFullScreenSession.current)
        advance(1)
        assertEquals(listOf(true, false), loading)
        val session = checkNotNull(NativeFullScreenSession.current)
        assertEquals("save_record", session.request.position)
        assertTrue(Ads.isFullScreenAdShowing)
        controller.pause()
        assertSame(session, NativeFullScreenSession.current) // The ad's own pause must not cancel Native.
        controller.resume().windowFocusChanged(true)
        assertTrue(results.isEmpty())
        val conflict = mutableListOf<AdShowResult>()
        val rejectedLoading = mutableListOf<Boolean>()
        tasks += Ads.showInter("other", onLoadingChanged = rejectedLoading::add, onResult = conflict::add)
        assertEquals(listOf(AdShowResult.Failed("another_full_screen_ad_showing")), conflict)
        assertTrue(rejectedLoading.isEmpty())
        session.onImpression()
        session.complete()
        session.complete()
        task.cancel()
        assertEquals(listOf(AdShowResult.Dismissed), results)
        assertEquals(listOf(true, false), loading)
        assertFalse(Ads.isFullScreenAdShowing)
        val logs = ShadowLog.getLogsForTag("InterSceneTest").filter { it.msg.contains("[任务=${task.id}]") }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.all { it.msg.contains("[插页任务]") && it.msg.contains("[位置=save_record]") })
        assertTrue(logs.none { it.msg.contains("_NativeFallback") })
    }

    @Test fun `disabled interstitial goes directly to native and cancellation closes the native session`() {
        config = config.copy(provider = AdMobProviderConfig(AdMobIds.TEST.copy(interstitialId = "")))
        cacheNative()
        val task = start()
        assertEquals(listOf(true, false), loading) // Synchronous fallback must cancel a delayed overlay.
        assertNotNull(NativeFullScreenSession.current)
        task.cancel()
        assertNull(NativeFullScreenSession.current)
        assertEquals(listOf(AdShowResult.Failed("opportunity_cancelled")), results)
        assertFalse(Ads.isFullScreenAdShowing)
    }

    @Test fun `native fallback synchronous failure terminates the original qualified opportunity`() {
        config = config.copy(provider = AdMobProviderConfig(AdMobIds.TEST.copy(interstitialId = "")))
        start()
        assertEquals(listOf(AdShowResult.Failed("no_preloaded_ad")), results)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertTrue(events.all { it.format == AdFormat.INTERSTITIAL && it.position == "save_record" })
        assertTrue(events.first().sessionId.isNotBlank())
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals("no_preloaded_ad", events.last().reason)
    }

    @Test fun `native fallback cancelled before position terminates the original opportunity`() {
        config = config.copy(provider = AdMobProviderConfig(AdMobIds.TEST.copy(interstitialId = "")))
        cacheNative()
        val task = start()
        assertNotNull(NativeFullScreenSession.current)
        assertTrue(events.isEmpty())
        task.cancel()
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals("opportunity_cancelled", events.last().reason)
    }

    @Test fun `native position owns failure even when the event listener cancels synchronously`() {
        config = config.copy(provider = AdMobProviderConfig(AdMobIds.TEST.copy(interstitialId = "")))
        cacheNative()
        val task = start()
        val session = checkNotNull(NativeFullScreenSession.current)
        config = config.copy(eventListener = {
            events += it
            if (it.name == AdEventName.POSITION) task.cancel()
        })
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        val slot = checkNotNull(Ads.newNativeSlot(session.request, session.onPosition))
        slot.position()
        slot.end("native_cancelled")
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertTrue(events.all { it.format == AdFormat.NATIVE && it.position == "save_record" })
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals(listOf(AdShowResult.Failed("opportunity_cancelled")), results)
    }

    @Test fun `native impression completes without an extra facade opportunity`() {
        config = config.copy(provider = AdMobProviderConfig(AdMobIds.TEST.copy(interstitialId = "")))
        cacheNative()
        start()
        val session = checkNotNull(NativeFullScreenSession.current)
        val slot = checkNotNull(Ads.newNativeSlot(session.request, session.onPosition))
        val native = slot.attempt({ 0L }, recordLoadEvents = false)
        native.impression("test", "native-response", onActualImpression = session::onImpression)
        assertTrue(session.impression.get())
        assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
        session.complete()
        assertEquals(listOf(AdShowResult.Dismissed), results)
        native.paid(com.cashcraft.ads.mediation.internal.nativeads.NativeRevenue(
            0, "USD", "test", "native-response", "exact"))
        assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION), events.map { it.name })
        assertTrue(events.all { it.format == AdFormat.NATIVE })
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertEquals(listOf(AdShowResult.Dismissed), results)
    }

    @Test fun `native handoff timeout before position retains the failed original opportunity`() {
        config = config.copy(provider = AdMobProviderConfig(AdMobIds.TEST.copy(interstitialId = "")))
        cacheNative()
        start()
        advance(3_000)
        assertEquals(listOf(AdShowResult.Failed("native_handoff_timeout")), results)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals(events.first().sessionId, events.last().sessionId)
    }

    @Test fun `empty cache ends once and foregrounding never revives a cancelled interstitial`() {
        start()
        controller.pause().stop()
        assertEquals(listOf(true, false), loading)
        assertEquals(listOf(AdShowResult.Failed("activity_not_resumed")), results)
        cacheNative()
        controller.start().resume().visible().windowFocusChanged(true)
        advance(3_001)
        assertNull(NativeFullScreenSession.current)
        assertEquals(1, results.size)
        NativeAdCache.clear()
        results.clear()
        start()
        advance(3_000)
        assertEquals(listOf(true, false, true, false), loading)
        assertEquals(listOf(AdShowResult.Failed("no_preloaded_ad")), results)
    }

    @Test fun `consent invalid timeout and competing wait never trigger native fallback`() {
        cacheNative()
        config = config.copy(interTimeoutMillis = { -1 })
        start()
        assertEquals(listOf(AdShowResult.Failed("invalid_timeout")), results)
        assertTrue(loading.isEmpty())
        config = config.copy(interTimeoutMillis = { 500 })
        results.clear()
        val task = start()
        val conflict = mutableListOf<AdShowResult>()
        val rejectedLoading = mutableListOf<Boolean>()
        tasks += Ads.showInter("other", onLoadingChanged = rejectedLoading::add, onResult = conflict::add)
        assertEquals(listOf(AdShowResult.Failed("request_in_progress")), conflict)
        assertTrue(rejectedLoading.isEmpty())
        assertEquals(listOf(true), loading)
        task.cancel()
        results.clear()
        ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager", null)
        start()
        assertEquals(listOf(AdShowResult.Failed("consent_not_obtained")), results)
        assertEquals(listOf(true, false), loading)
        assertNull(NativeFullScreenSession.current)
    }

    @Test fun `business condition changing during wait prevents native fallback`() {
        cacheNative()
        var valid = true
        tasks += Ads.showInter("save_record", isSceneValid = { valid },
            onLoadingChanged = loading::add, onResult = results::add)
        assertEquals(listOf(true), loading)
        valid = false
        advance(3_000)
        assertEquals(listOf(AdShowResult.Failed("scene_invalid")), results)
        assertEquals(listOf(true, false), loading)
        assertNull(NativeFullScreenSession.current)
        assertFalse(Ads.isFullScreenAdShowing)
    }

    @Test fun `real inter and open entries retain a qualified empty opportunity with unknown bidding platform`() {
        config = config.copy(provider = BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST),
            TopOnProviderConfig(TopOnIds("app", "key", appOpenPlacementId = "open", interstitialPlacementId = "inter"))),
            nativeFullScreenLayout = null, interTimeoutMillis = { 0 }, openTimeoutMillis = { 0 })
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        for (format in listOf(AdFormat.INTERSTITIAL, AdFormat.APP_OPEN)) {
            events.clear()
            results.clear()
            val position = "  original-${format.analyticsValue}  "
            tasks += if (format == AdFormat.APP_OPEN) Ads.showOpen(position, onResult = results::add)
                else Ads.showInter(position, onResult = results::add)
            assertEquals(1, results.size)
            assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
            assertEquals(position, events.first().position)
            assertTrue(events.first().sessionId.isNotEmpty())
            assertEquals(events.first().sessionId, events.last().sessionId)
            assertTrue(events.all { !it.platformKnown && it.analyticsParameters()["ad_platform"] == "unknown" })
        }
    }

    @Test fun `rewarded wait terminal publishes one qualified opportunity without a fake loading terminal`() {
        config = config.copy(provider = BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST),
            TopOnProviderConfig(TopOnIds("app", "key", rewardedPlacementId = "reward"))))
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        val rewardResults = mutableListOf<AdRewardResult>()
        Ads.showRewardedWhenReady(controller.get(), "reward", timeoutMillis = 0, onResult = rewardResults::add)
        assertEquals(1, rewardResults.size)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertTrue(events.all { !it.platformKnown })
        assertEquals("wait_timeout", events.last().reason)
    }

    @Test fun `real scene policy and platform skips do not create opportunities or alter final results`() {
        config = config.copy(nativeFullScreenLayout = null, interTimeoutMillis = { 0 })
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        Ads.updatePolicy(AdPolicy(enabled = false))
        start().cancel()
        assertEquals(listOf(AdEventName.SCENE_SKIP), events.map { it.name })
        assertEquals("global_disabled", events.single().reason)
        assertEquals(listOf(AdShowResult.Blocked(AdBlockReason.GLOBAL_DISABLED)), results)
        events.clear()
        results.clear()
        Ads.updatePolicy(AdPolicy(platforms = mapOf(AdPlatform.ADMOB to false)))
        start().cancel()
        assertEquals(listOf(AdEventName.SCENE_SKIP), events.map { it.name })
        assertEquals("platform_disabled", events.single().reason)
        assertTrue(results.single() is AdShowResult.Failed) // Same business result as before telemetry repair.
        assertFalse(events.single().analyticsParameters().containsKey("session_id"))
    }

    @Test fun `scene invalidated during wait still terminates its eligible request once`() {
        config = config.copy(nativeFullScreenLayout = null, interTimeoutMillis = { 500 })
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        var valid = true
        tasks += Ads.showInter("save", isSceneValid = { valid }, onResult = results::add)
        valid = false
        advance(100)
        tasks.last().cancel()
        assertEquals(listOf(AdShowResult.Failed("scene_invalid")), results)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals("scene_invalid", events.last().reason)
    }

    private fun start(): AdTask {
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
        return Ads.showInter("save_record", onLoadingChanged = {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            // Loading must be cleared BEFORE starting the Native activity, not on its final result.
            assertNull(NativeFullScreenSession.current)
            loading += it
        }, onResult = {
            assertFalse(loading.lastOrNull() == true)
            results += it
        }).also(tasks::add)
    }

    private fun resetInstallation() {
        val type = AdLifecycleMonitor::class.java
        val app = ReflectionHelpers.getStaticField<Application?>(type, "installedApplication")
        val callbacks = ReflectionHelpers.getStaticField<Application.ActivityLifecycleCallbacks>(type, "callbacks")
        app?.unregisterActivityLifecycleCallbacks(callbacks)
        ReflectionHelpers.setStaticField(type, "installedApplication", null)
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun cacheNative() {
        val cached = object : NativeAdHandle {
            override val platform = AdPlatform.ADMOB
            override val adSource = "test"
            override val responseId = "inter-native"
            override val isTemplate = false
            override val canCache = true
            override val expiresAtMillis = SystemClock.elapsedRealtime() + 60_000
            override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("not rendered")
            override fun destroy() = Unit
        }
        NativeAdCache.retain(ResolvedNativeRequest(AdPlatform.ADMOB, checkNotNull(AdMobIds.TEST.nativeId), "save_record"),
            0, cached, NativeAdCache.generation)
    }
}
