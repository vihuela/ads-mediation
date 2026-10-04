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
@Config(sdk = [33], manifest = Config.NONE)
class InterAdSceneTest {
    private val fields = listOf("config", "umpConsentManager", "initializationStage", "nativeLogger")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
    private val providerStarted = ReflectionHelpers.getStaticField<AtomicBoolean>(Ads::class.java, "providerInitializationStarted")
    private val wasStarted = providerStarted.get()
    private val initialAdMobState = AdMobAds.state
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private val tasks = mutableListOf<AdTask>()
    private val results = mutableListOf<AdShowResult>()
    private val loading = mutableListOf<Boolean>()
    private var config = AdsConfig(
        AdMobProviderConfig(AdMobIds.TEST),
        loggingEnabled = true, logTag = "InterSceneTest", autoShowAppOpen = false,
        nativeFullScreenLayout = NativeLayout.Custom { error("not rendered by this test") },
    )

    @Before fun prepare() {
        resetInstallation()
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config)
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
