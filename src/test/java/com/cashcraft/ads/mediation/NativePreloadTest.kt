@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import com.cashcraft.ads.mediation.internal.nativeads.*
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [ShadowNativePreloader::class],
    instrumentedPackages = ["com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader\$Companion"])
class NativePreloadTest {
    private val fields = listOf("application", "config", "umpConsentManager", "initializationStage", "initializationCallback")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
    private val originalState = AdMobAds.state
    private val originalTopOnState = TopOnAds.state
    private val foreground = AdLifecycleMonitor.isAppInForeground
    private val pending = ReflectionHelpers.getStaticField<MutableMap<AdPlatform, ResolvedNativeRequest>>(
        Ads::class.java, "pendingNativePreloads")
    private val request = ResolvedNativeRequest(AdPlatform.ADMOB, "native-unit", "page")

    @Before fun prepare() {
        pending.clear()
        ShadowNativePreloader.calls.clear()
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Ads::class.java, "application", app)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST.copy(nativeId = request.adUnitId)), loggingEnabled = false))
        ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
            UmpConsentManager(app, UmpConsentConfig(enabled = false), false, "test"))
        stage("PROVIDER_INITIALIZING")
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", true)
    }

    @After fun restore() {
        NativeAdCache.clear()
        pending.clear()
        fields.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", originalState)
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", originalTopOnState)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", foreground)
    }

    @Test fun `warmup waits for consent foreground and provider without replacing initialization observer`() {
        val config = ReflectionHelpers.getStaticField<AdsConfig>(Ads::class.java, "config")
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config.copy(provider = BiddingProviderConfig(
            config.provider as AdMobProviderConfig,
            TopOnProviderConfig(TopOnIds("app", "key", rewardedPlacementId = "reward", nativePlacementId = "topon-native")))))
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.INITIALIZING)
        val observer: (Boolean) -> Unit = { error("warmup must not complete initialization") }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationCallback", observer)
        stage("WAITING_FOR_UMP")
        Ads.preloadNative()
        Ads.notifyNativeReadiness()
        assertTrue(ShadowNativePreloader.calls.isEmpty())
        stage("PROVIDER_INITIALIZING")
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", false)
        Ads.notifyNativeReadiness()
        assertTrue(ShadowNativePreloader.calls.isEmpty())
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", true)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.INITIALIZING)
        Ads.notifyNativeReadiness()
        assertTrue(ShadowNativePreloader.calls.isEmpty())
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        Ads.notifyNativeReadiness()
        Ads.preloadNative()
        assertEquals(listOf("start:1"), ShadowNativePreloader.calls)
        assertEquals(setOf(AdPlatform.TOPON), pending.keys) // AdMob need not wait for the second SDK.
        assertSame(observer, ReflectionHelpers.getStaticField(Ads::class.java, "initializationCallback"))
        advance(300_000)
        assertEquals(listOf("start:1", "destroy"), ShadowNativePreloader.calls)
    }

    @Test fun `cached loser restarts expired warmup and keeps demand until page releases`() {
        Ads.preloadNative()
        var destroyed = false
        val cached = object : NativeAdHandle {
            override val platform = AdPlatform.ADMOB
            override val adSource = "test"
            override val responseId = "retained"
            override val isTemplate = false
            override val canCache = true
            override val expiresAtMillis = SystemClock.elapsedRealtime() + 3_600_000
            override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("not rendered")
            override fun destroy() { destroyed = true }
        }
        NativeAdCache.retain(request, 320, cached, NativeAdCache.generation)
        advance(300_000)
        assertEquals(listOf("start:1", "destroy"), ShadowNativePreloader.calls)
        var delivered: NativeAdHandle? = null
        val demand = NativeAdCache.load(Activity(), request, 320, false, object : NativeCallbacks {
            override fun loaded(ad: NativeAdHandle) { delivered = ad }
            override fun failed(reason: String, errorCode: String?) { error(reason) }
            override fun impression(adSource: String?, responseId: String?) = Unit
            override fun clicked(adSource: String?, responseId: String?) = Unit
            override fun closed() = Unit
            override fun overlayOpened() = Unit
            override fun overlayClosed() = Unit
            override fun paid(revenue: NativeRevenue) = Unit
        })
        assertSame(cached, delivered) // Cache hits preserve immediate delivery as well as ownership.
        advance(300_000)
        assertEquals(listOf("start:1", "destroy", "start:1"), ShadowNativePreloader.calls)
        demand.cancel()
        advance(300_000)
        assertEquals(listOf("start:1", "destroy", "start:1", "destroy"), ShadowNativePreloader.calls)
        assertFalse(destroyed) // Only the page owns the delivered object.
        cached.destroy()
    }

    private fun stage(name: String) = ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage",
        checkNotNull(fields.getValue("initializationStage")!!.javaClass.enumConstants).single { (it as Enum<*>).name == name })
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}

/** Only intercepts the public SDK boundary; these tests perform no SDK requests. */
@Implements(className = "com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader\$Companion", isInAndroidSdk = false)
class ShadowNativePreloader {
    @Implementation fun start(id: String, configuration: PreloadConfiguration, callback: PreloadCallback): Boolean {
        calls += "start:${configuration.bufferSize}"
        return true
    }
    @Implementation fun destroy(id: String): Boolean { calls += "destroy"; return true }
    companion object { val calls = mutableListOf<String>() }
}
