@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation

import android.app.Activity
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.admob.RetainedAd
import com.cashcraft.ads.mediation.internal.AdBiddingCoordinator
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdShowSession
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.AdPolicyChecker
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.AdUsageStore
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.cashcraft.ads.mediation.internal.admob.AdMobConfig
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnFullScreenLoadControl
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdSourceResponseInfo
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import com.cashcraft.ads.mediation.internal.ShadowMMKV
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], manifest = Config.NONE,
    shadows = [
        ShadowMMKV::class,
        PolicyAppOpenPreloaderShadow::class, PolicyInterstitialPreloaderShadow::class,
        PolicyRewardedPreloaderShadow::class, ShadowBannerPreloader::class, ShadowBannerLoader::class,
    ],
    instrumentedPackages = [
        "com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader\$Companion",
        "com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader\$Companion",
        "com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader\$Companion",
        "com.google.android.libraries.ads.mobile.sdk.banner.BannerAdPreloader\$Companion",
        "com.google.android.libraries.ads.mobile.sdk.banner.BannerAd\$Companion",
    ],
)
class ProviderPreloadPolicyTest {
    private val savedFields = mutableMapOf<String, Any?>()
    private val savedMaps = mutableMapOf<String, Map<Any, Any>>()
    private val events = mutableListOf<AdEvent>()
    private val banner = BannerRequest(AdPlatform.ADMOB, "banner-unit", "home", BannerSize.Standard320x50)
    private lateinit var checker: AdPolicyChecker

    @Before fun prepare() {
        listOf("config", "events", "state", "autoAppOpenController").forEach {
            savedFields[it] = ReflectionHelpers.getStaticField<Any?>(AdMobAds::class.java, it)
        }
        savedFields["policyChecker"] = Ads.policyChecker
        savedFields["topOnState"] = TopOnAds.state
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.NOT_INITIALIZED)
        listOf(
            "preloadDescriptors", "preloadLoadSessions", "preloadStartedAt", "bannerPreloadDescriptors",
            "responseLoadBounds", "pendingAds", "takenAds", "loadFailures",
        ).forEach { name ->
            val map = providerMap(name)
            savedMaps[name] = map.toMap()
            map.clear()
        }
        PolicyPreloaderCalls.clear()
        ShadowBannerPreloader.calls.clear()
        ShadowBannerPreloader.configurations.clear()
        ShadowBannerPreloader.nextAd = null
        ShadowBannerLoader.loads.clear()
        ShadowBannerLoader.duringLoad = null
        val application = RuntimeEnvironment.getApplication()
        ShadowMMKV.reset()
        checker = AdPolicyChecker(AdUsageStore(application, 0L))
        installChecker(checker)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "config", AdMobConfig(AdMobIds.TEST))
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "autoAppOpenController",
            AutoAppOpenController<AdShowSession>(isEnabled = { false }, isProviderReady = { true },
                providerFailureReason = { null }, isAdAvailable = { false },
                beginOpportunity = { error("Auto app-open disabled in load tests") },
                show = { _, _ -> }, fail = { _, _ -> }, noAdFailureReason = "no_fill"))
        ReflectionHelpers.setStaticField(
            AdMobAds::class.java, "events",
            AdEventDispatcher(application, AdPlatform.ADMOB, AdMediationMode.ADMOB,
                AdEventListener { events += it }, false, "test"),
        )
    }

    @After fun restore() {
        shadowOf(Looper.getMainLooper()).idle()
        val controller = ReflectionHelpers.getStaticField<AutoAppOpenController<*>>(AdMobAds::class.java, "autoAppOpenController")
        AdLifecycleMonitor.removeListener(ReflectionHelpers.getField(controller, "lifecycleListener"))
        savedMaps.forEach { (name, values) -> providerMap(name).apply { clear(); putAll(values) } }
        savedFields.filterKeys { it != "policyChecker" && it != "topOnState" }.forEach { (name, value) ->
            ReflectionHelpers.setStaticField(AdMobAds::class.java, name, value)
        }
        installChecker(savedFields["policyChecker"] as AdPolicyChecker?)
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", savedFields["topOnState"])
        PolicyPreloaderCalls.clear()
        ShadowBannerPreloader.calls.clear()
        ShadowBannerPreloader.configurations.clear()
        ShadowBannerPreloader.nextAd = null
        ShadowBannerLoader.loads.clear()
        ShadowBannerLoader.duringLoad = null
    }

    @Test fun `TopOn background notification preserves retries and allows new loads while display stays blocked`() {
        val foreground = AdLifecycleMonitor.isAppInForeground
        val consent = ReflectionHelpers.getStaticField<Any?>(Ads::class.java, "umpConsentManager")
        val control = ReflectionHelpers.getStaticField<TopOnFullScreenLoadControl>(TopOnAds::class.java, "fullScreenLoadControl")
        val listener = ReflectionHelpers.getStaticField<AdLifecycleMonitor.Listener>(TopOnAds::class.java, "lifecycleListener")
        val id = "background-regression"
        var requests = 0
        fun request() = control.request(id, isReady = { false }, isLoading = { false }, load = { requests++; true })
        try {
            ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
                UmpConsentManager(RuntimeEnvironment.getApplication(), UmpConsentConfig(enabled = false), false, "test"))
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.READY)
            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", true)
            request()
            assertEquals(1, requests)
            assertTrue(control.failed(id))

            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", false)
            listener.onAppEnteredBackground()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_100))
            assertEquals(2, requests)
            assertTrue(control.loaded(id))
            request()
            assertEquals(3, requests)
            assertEquals("app_not_in_foreground", AdLifecycleMonitor.activityShowFailureReason(android.app.Activity()))

            assertTrue(control.loaded(id))
            checker.policy = AdPolicy(enabled = false)
            request()
            assertEquals(3, requests)
        } finally {
            control.loaded(id)
            ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager", consent)
            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", foreground)
        }
    }

    @Test fun `no installed policy retains standalone provider preloading`() {
        installChecker(null)
        startFullScreenPools()
        assertEquals(3, PolicyPreloaderCalls.active.size)
        assertEquals(3, PolicyPreloaderCalls.starts)
    }

    @Test fun `disabled at startup starts no pools and enable disable enable recreates all owned pools once`() {
        checker.policy = AdPolicy(platforms = mapOf(AdPlatform.ADMOB to false))
        startFullScreenPools()
        AdMobAds.preloadBanner(banner, AdSize.BANNER, 2)
        assertEquals(0, PolicyPreloaderCalls.starts)
        assertTrue(ShadowBannerPreloader.calls.isEmpty())

        checker.policy = AdPolicy()
        AdMobAds.onPolicyChanged()
        AdMobAds.onPolicyChanged()
        assertEquals(3, PolicyPreloaderCalls.starts)
        assertEquals(1, ShadowBannerPreloader.calls.count { it.startsWith("start:") })

        checker.policy = AdPolicy(platforms = mapOf(AdPlatform.ADMOB to false))
        AdMobAds.onPolicyChanged()
        AdMobAds.onPolicyChanged()
        assertEquals(3, PolicyPreloaderCalls.destroys)
        assertTrue(PolicyPreloaderCalls.active.isEmpty())
        assertEquals(1, ShadowBannerPreloader.calls.count { it.startsWith("destroy:") })
        assertNull(AdMobAds.pollBanner(banner, AdSize.BANNER))

        checker.policy = AdPolicy()
        AdMobAds.onPolicyChanged()
        assertEquals(6, PolicyPreloaderCalls.starts)
        assertEquals(2, ShadowBannerPreloader.calls.count { it.startsWith("start:") })
    }

    @Test fun `global disable and pending daily quota both stop automatic loading and can resume`() {
        startFullScreenPools()
        checker.policy = AdPolicy(enabled = false)
        AdMobAds.onPolicyChanged()
        assertTrue(PolicyPreloaderCalls.active.isEmpty())
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 1))
        AdMobAds.onPolicyChanged()
        assertEquals(3, PolicyPreloaderCalls.active.size)
        assertEquals(AdPolicyCheckResult.Passed, checker.reserve("reserved-show", AdPolicyRequest()))
        assertFalse(Ads.canLoadAds(AdPlatform.ADMOB))
        AdMobAds.onPolicyChanged()
        assertTrue(PolicyPreloaderCalls.active.isEmpty())
        checker.release("reserved-show")
        AdMobAds.onPolicyChanged()
        assertEquals(3, PolicyPreloaderCalls.active.size)
    }

    @Test fun `fullscreen callbacks after first result report fresh automatic load pairs without latency`() {
        startFullScreenPools()
        PolicyPreloaderCalls.active.toMap().forEach { (id, callback) ->
            val format = when {
                id.contains("app_open") -> AdFormat.APP_OPEN
                id.contains("interstitial") -> AdFormat.INTERSTITIAL
                else -> AdFormat.REWARDED
            }
            val response = ResponseInfo(null, "response", Bundle(), null, emptyList())
            callback.onAdPreloaded(id, response)
            shadowOf(Looper.getMainLooper()).idle()
            callback.onAdPreloaded(id, response)
            shadowOf(Looper.getMainLooper()).idle()
            callback.onAdFailedToPreload(id, LoadAdError(LoadAdError.ErrorCode.NO_FILL, "no fill", null))
            shadowOf(Looper.getMainLooper()).idle()
            val loads = events.filter { it.format == format }
            assertEquals(listOf(AdEventName.LOAD, AdEventName.LOADED, AdEventName.LOAD,
                AdEventName.LOADED, AdEventName.LOAD, AdEventName.LOAD_FAIL), loads.map { it.name })
            assertEquals(3, loads.map { it.requestId }.distinct().size)
            loads.chunked(2).forEachIndexed { index, pair ->
                assertEquals(pair.first().requestId, pair.last().requestId)
                assertEquals(index > 0, pair.last().sdkAutoLoad)
                if (index == 0) {
                    assertTrue(pair.last().analyticsParameters().containsKey("latency_ms"))
                } else pair.forEach { event ->
                    assertEquals("sdk_auto", event.analyticsParameters()["load_trigger"])
                    assertFalse(event.analyticsParameters().containsKey("latency_ms"))
                }
            }
            val count = events.size
            callback.onAdsExhausted(id)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(count, events.size)
        }
    }

    @Test fun `initial full screen failure does not swallow automatic retries or recovery`() {
        startFullScreenPools()
        PolicyPreloaderCalls.active.toMap().forEach { (id, callback) ->
            repeat(2) { callback.onAdFailedToPreload(id, LoadAdError(LoadAdError.ErrorCode.NO_FILL, "no fill", null)) }
            callback.onAdPreloaded(id, ResponseInfo(null, "recovered", Bundle(), null, emptyList()))
        }
        shadowOf(Looper.getMainLooper()).idle()
        for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
            val loads = events.filter { it.format == format }
            assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL, AdEventName.LOAD,
                AdEventName.LOAD_FAIL, AdEventName.LOAD, AdEventName.LOADED), loads.map { it.name })
            assertEquals(3, loads.map { it.requestId }.distinct().size)
            assertTrue(loads.drop(2).all { it.sdkAutoLoad && it.analyticsParameters()["load_trigger"] == "sdk_auto" &&
                !it.analyticsParameters().containsKey("latency_ms") })
        }
    }

    @Test fun `late fullscreen callbacks from a destroyed pool cannot report failure into its replacement`() {
        startFullScreenPools()
        val oldCallbacks = PolicyPreloaderCalls.active.toMap()
        checker.policy = AdPolicy(enabled = false)
        AdMobAds.onPolicyChanged()
        checker.policy = AdPolicy()
        AdMobAds.onPolicyChanged()
        val count = events.size
        oldCallbacks.forEach { (id, callback) ->
            callback.onAdsExhausted(id)
            callback.onAdPreloaded(id, ResponseInfo(null, "obsolete", Bundle(), null, emptyList()))
            callback.onAdFailedToPreload(id, LoadAdError(LoadAdError.ErrorCode.NO_FILL, "late", null))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, events.size)
        assertTrue(providerMap("loadFailures").isEmpty())
        assertEquals(6, PolicyPreloaderCalls.starts)
    }

    @Test fun `a late one shot banner is destroyed and cannot settle the replacement load`() {
        AdMobAds.preloadBanner(banner, AdSize.BANNER, 1, autoRefill = false)
        val old = ShadowBannerLoader.loads.single()
        val oldRequest = events.single { it.format == AdFormat.BANNER }
        checker.policy = AdPolicy(enabled = false)
        AdMobAds.onPolicyChanged()
        checker.policy = AdPolicy()
        AdMobAds.onPolicyChanged()
        assertEquals(2, ShadowBannerLoader.loads.size)
        val currentRequest = events.last { it.format == AdFormat.BANNER }
        assertNotEquals(oldRequest.requestId, currentRequest.requestId)
        var destroyed = 0
        val obsolete = fakeAd(BannerAd::class.java) { destroyed++ }
        old.onAdLoaded(obsolete)
        assertEquals(1, destroyed)
        val oldResult = events.last { it.format == AdFormat.BANNER }
        assertEquals(AdEventName.LOADED, oldResult.name)
        assertEquals(oldRequest.requestId, oldResult.requestId)
        assertEquals("filled", oldResult.result)
        assertNull(AdMobAds.pollBanner(banner, AdSize.BANNER))
        val current = fakeAd(BannerAd::class.java)
        ShadowBannerLoader.loads.last().onAdLoaded(current)
        assertSame(current, AdMobAds.pollBanner(banner, AdSize.BANNER))
        assertEquals(2, ShadowBannerLoader.loads.size)
        val currentResult = events.last { it.format == AdFormat.BANNER }
        assertEquals(currentRequest.requestId, currentResult.requestId)
        assertEquals("filled", currentResult.result)
        assertEquals(4, events.count { it.format == AdFormat.BANNER })
    }

    @Test fun `late one shot banner failure reports its request without settling the new generation`() {
        AdMobAds.preloadBanner(banner, AdSize.BANNER, 1, autoRefill = false)
        val old = ShadowBannerLoader.loads.single()
        val oldRequest = events.single { it.format == AdFormat.BANNER }
        checker.policy = AdPolicy(enabled = false)
        AdMobAds.onPolicyChanged()
        checker.policy = AdPolicy()
        AdMobAds.onPolicyChanged()
        val currentRequest = events.last { it.format == AdFormat.BANNER }
        old.onAdFailedToLoad(LoadAdError(LoadAdError.ErrorCode.NO_FILL, "late", null))
        val oldResult = events.last { it.format == AdFormat.BANNER }
        assertEquals(AdEventName.LOAD_FAIL, oldResult.name)
        assertEquals(oldRequest.requestId, oldResult.requestId)
        assertEquals("no_fill", oldResult.result)
        assertEquals("NO_FILL", oldResult.errorCode)
        val current = fakeAd(BannerAd::class.java)
        ShadowBannerLoader.loads.last().onAdLoaded(current)
        assertSame(current, AdMobAds.pollBanner(banner, AdSize.BANNER))
        assertEquals(currentRequest.requestId, events.last { it.format == AdFormat.BANNER }.requestId)
        assertNotEquals(oldRequest.requestId, currentRequest.requestId)
        assertEquals(4, events.count { it.format == AdFormat.BANNER })
    }

    @Test fun `blocked one shot banner emits no load until an actual SDK call starts`() {
        checker.policy = AdPolicy(enabled = false)
        AdMobAds.preloadBanner(banner, AdSize.BANNER, 1, autoRefill = false)
        assertTrue(ShadowBannerLoader.loads.isEmpty())
        assertTrue(events.none { it.format == AdFormat.BANNER })
        checker.policy = AdPolicy()
        AdMobAds.onPolicyChanged()
        assertEquals(1, ShadowBannerLoader.loads.size)
        assertEquals(AdEventName.LOAD, events.single { it.format == AdFormat.BANNER }.name)
    }

    @Test fun `pending quota does not reject retained selection while platform disable does`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 1))
        checker.reserve("this-show", AdPolicyRequest())
        val ad = fakeAd(AppOpenAd::class.java)
        providerMap("pendingAds")[AdFormat.APP_OPEN] = RetainedAd<Ad>(
            ad, SystemClock.elapsedRealtime(), 60_000L, priceUsd = 1.0,
        )
        assertFalse(Ads.canLoadAds(AdPlatform.ADMOB))
        assertTrue(AdMobAds.isReady(AdFormat.APP_OPEN))
        assertEquals(AdPlatform.ADMOB, AdBiddingCoordinator.selectAvailable(AdFormat.APP_OPEN).selection?.winner)

        checker.policy = checker.policy.copy(platforms = mapOf(AdPlatform.ADMOB to false))
        assertFalse(AdMobAds.isReady(AdFormat.APP_OPEN))
        assertNull(AdMobAds.bidPrice(AdFormat.APP_OPEN))
        assertFalse(AdBiddingCoordinator.selectAvailable(AdFormat.APP_OPEN).admobAvailable)
        assertNull(takeAd())

        checker.policy = checker.policy.copy(platforms = emptyMap())
        assertSame(ad, takeAd()) // No canLoadAds check when taking this already-reserved show.
    }

    private fun startFullScreenPools() = ReflectionHelpers.callInstanceMethod<Any?>(AdMobAds, "startPreloading")

    @Test fun `all AdMob full screen show exceptions carry the consumed ads source`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        controller.windowFocusChanged(true)
        val activity = controller.get()
        val previousActivity = ReflectionHelpers.getStaticField<Any?>(AdLifecycleMonitor::class.java, "resumedActivity")
        val foreground = AdLifecycleMonitor.isAppInForeground
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "resumedActivity", WeakReference(activity))
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", true)
        try {
            for ((format, type, methodName) in listOf(
                Triple(AdFormat.APP_OPEN, AppOpenAd::class.java, "showAppOpenOnMain"),
                Triple(AdFormat.INTERSTITIAL, InterstitialAd::class.java, "showInterstitialOnMain"),
                Triple(AdFormat.REWARDED, RewardedAd::class.java, "showRewardedOnMain"),
            )) for (source in listOf("network-${format.analyticsValue}", " ", null)) {
                val sourceInfo = source?.let { AdSourceResponseInfo("adapter", 0L, null, Bundle(), it, "id", "", "") }
                val response = ResponseInfo("adapter", "response", Bundle(), sourceInfo, listOfNotNull(sourceInfo))
                var shows = 0
                val ad = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                    when (method.name) {
                        "getResponseInfo" -> response
                        "show" -> { shows++; error("test show exception") }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> null
                    }
                } as Ad
                providerMap("pendingAds")[format] = RetainedAd(ad, SystemClock.elapsedRealtime(), 60_000L)
                events.clear()
                val results = mutableListOf<Any>()
                val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB,
                    AdMediationMode.ADMOB, format, "show-source", "unit", "session", 1)
                val callback: (Any) -> Unit = { results += it }
                val method = AdMobAds::class.java.getDeclaredMethod(methodName,
                    Activity::class.java, String::class.java, Function1::class.java, AdShowSession::class.java)
                    .apply { isAccessible = true }
                try {
                    method.invoke(AdMobAds, activity, "show-source", callback, session)
                    assertEquals("$format: SDK show must be reached", 1, shows)
                    val failure = events.single { it.name == AdEventName.SHOW_FAIL }
                    assertEquals(source?.takeIf { it.isNotBlank() } ?: "unknow", failure.analyticsParameters()["ad_source"])
                    assertEquals("exception", failure.reason)
                    assertEquals(1, results.size)
                } finally { session.attempt.complete() }
            }
        } finally {
            controller.pause().stop().destroy()
            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "resumedActivity", previousActivity)
            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", foreground)
        }
    }

    @Test fun `inter quota exhaustion leaves shared pools available for open fallback`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true,
            sceneQuotas = AdSceneType.entries.associateWith {
                if (it == AdSceneType.INTER) AdSceneQuota(true, 0, 3) else AdSceneQuota()
            }))
        startFullScreenPools()
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT),
            checker.check(AdPolicyRequest("save", sceneType = AdSceneType.INTER)))
        assertEquals(AdPolicyCheckResult.Passed,
            checker.check(AdPolicyRequest("open", sceneType = AdSceneType.OPEN)))
        assertTrue(Ads.canLoadAds(AdPlatform.ADMOB))
        AdMobAds.onPolicyChanged()
        assertEquals(3, PolicyPreloaderCalls.active.size)
        assertEquals(0, PolicyPreloaderCalls.destroys)
    }
    private fun takeAd(): Ad? = ReflectionHelpers.callInstanceMethod(
        AdMobAds, "takeAd", ReflectionHelpers.ClassParameter.from(AdFormat::class.java, AdFormat.APP_OPEN),
    )
    private fun installChecker(value: AdPolicyChecker?) =
        ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", value)
    private fun providerMap(name: String): MutableMap<Any, Any> =
        ReflectionHelpers.getStaticField(AdMobAds::class.java, name)
    private fun <T> fakeAd(type: Class<T>, destroy: () -> Unit = {}): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "destroy" -> { destroy(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        },
    )
}

/** Intercepts only public SDK boundaries; no real SDK initialization or network requests. */
object PolicyPreloaderCalls {
    val active = mutableMapOf<String, PreloadCallback>()
    var starts = 0
    var destroys = 0
    fun start(id: String, callback: PreloadCallback): Boolean {
        starts++
        return active.putIfAbsent(id, callback) == null
    }
    fun destroy(id: String): Boolean {
        destroys++
        return active.remove(id) != null
    }
    fun clear() { active.clear(); starts = 0; destroys = 0 }
}

@Implements(className = "com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader\$Companion", isInAndroidSdk = false)
class PolicyAppOpenPreloaderShadow {
    @Implementation
    fun start(id: String, configuration: PreloadConfiguration, callback: PreloadCallback): Boolean =
        PolicyPreloaderCalls.start(id, callback)
    @Implementation
    fun destroy(id: String): Boolean = PolicyPreloaderCalls.destroy(id)
    @Implementation
    fun isAdAvailable(id: String): Boolean = false
}

@Implements(className = "com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader\$Companion", isInAndroidSdk = false)
class PolicyInterstitialPreloaderShadow {
    @Implementation
    fun start(id: String, configuration: PreloadConfiguration, callback: PreloadCallback): Boolean =
        PolicyPreloaderCalls.start(id, callback)
    @Implementation
    fun destroy(id: String): Boolean = PolicyPreloaderCalls.destroy(id)
    @Implementation
    fun isAdAvailable(id: String): Boolean = false
}

@Implements(className = "com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader\$Companion", isInAndroidSdk = false)
class PolicyRewardedPreloaderShadow {
    @Implementation
    fun start(id: String, configuration: PreloadConfiguration, callback: PreloadCallback): Boolean =
        PolicyPreloaderCalls.start(id, callback)
    @Implementation
    fun destroy(id: String): Boolean = PolicyPreloaderCalls.destroy(id)
    @Implementation
    fun isAdAvailable(id: String): Boolean = false
}
