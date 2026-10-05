@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation

import android.app.Activity
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.admob.AdMobConfig
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], manifest = Config.NONE,
    shadows = [ShadowBannerPreloader::class, ShadowBannerLoader::class],
    instrumentedPackages = [
        "com.google.android.libraries.ads.mobile.sdk.banner.BannerAd\$Companion",
        "com.google.android.libraries.ads.mobile.sdk.banner.BannerAdPreloader\$Companion",
    ],
)
class BannerPreloadTest {
    private val request = BannerRequest(AdPlatform.ADMOB, "test-unit", "home", BannerSize.Standard320x50)
    private val savedAdsConfig = ReflectionHelpers.getStaticField<Any?>(Ads::class.java, "config")
    private val savedProviderConfig = ReflectionHelpers.getStaticField<Any?>(AdMobAds::class.java, "config")
    private val savedEvents = ReflectionHelpers.getStaticField<Any?>(AdMobAds::class.java, "events")
    private val savedState = AdMobAds.state
    private val events = mutableListOf<AdEvent>()
    private val descriptors = ReflectionHelpers.getStaticField<MutableMap<Any, Any>>(
        AdMobAds::class.java, "bannerPreloadDescriptors",
    )

    @Before
    fun prepare() {
        ShadowBannerPreloader.calls.clear()
        ShadowBannerPreloader.configurations.clear()
        ShadowBannerPreloader.nextAd = null
        ShadowBannerLoader.loads.clear()
        ShadowBannerLoader.duringLoad = null
        descriptors.clear()
        val ids = AdMobIds(applicationId = "test-app")
        val preload = AdMobPreloadConfig(banner = 3)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(AdMobProviderConfig(ids, preload)))
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "config", AdMobConfig(ids, preload))
        ReflectionHelpers.setStaticField(
            AdMobAds::class.java, "events",
            AdEventDispatcher(
                RuntimeEnvironment.getApplication(), AdPlatform.ADMOB, AdMediationMode.ADMOB,
                AdEventListener { events += it }, false, "test",
            ),
        )
        setState(AdMobState.READY)
    }

    @After
    fun restore() {
        descriptors.clear()
        ShadowBannerLoader.duringLoad = null
        ReflectionHelpers.setStaticField(Ads::class.java, "config", savedAdsConfig)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "config", savedProviderConfig)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "events", savedEvents)
        setState(savedState)
    }

    @Test
    fun `default and explicit true use the native preloader with the configured buffer`() {
        for (mode in listOf<Boolean?>(null, true)) {
            val placement = request.copy(position = "mode-$mode")
            if (mode == null) Ads.preloadBanner(Activity(), placement, 320)
            else Ads.preloadBanner(Activity(), placement, 320, autoRefill = mode)
            val id = preloadId(placement)
            assertEquals(3, ShadowBannerPreloader.configurations.getValue(id).bufferSize)
            val ad = TestAd()
            ShadowBannerPreloader.nextAd = ad.value
            assertSame(ad.value, AdMobAds.pollBanner(placement, AdSize.BANNER))
            assertTrue(id in ShadowBannerPreloader.configurations)
            assertEquals(0, ad.destroyed)
        }
        assertTrue(ShadowBannerLoader.loads.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `false loads once and empty or successful polls never use the preloader or replenish`() {
        preloadOnce()
        preloadOnce()
        assertEquals(1, ShadowBannerLoader.loads.size)
        val loadRequest = events.single()
        assertEquals(AdEventName.LOAD, loadRequest.name)
        assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        val ad = TestAd()
        loaded(ad)
        preloadOnce() // Repeating a ready request must retain its object and original age.
        assertSame(ad.value, AdMobAds.pollBanner(request, AdSize.BANNER))
        assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        assertEquals(1, ShadowBannerLoader.loads.size)
        assertTrue(ShadowBannerPreloader.calls.isEmpty())
        assertEquals(0, ad.destroyed) // The consumer owns destruction after a successful poll.
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOADED), events.map { it.name })
        assertLoadIdentity(loadRequest, events.last())
        assertEquals("filled", events.last().result)
        ad.value.destroy()
        assertEquals(1, ad.destroyed)
        preloadOnce() // Only another explicit request starts the next load.
        assertEquals(2, ShadowBannerLoader.loads.size)
        assertTrue(ShadowBannerPreloader.calls.isEmpty())
        assertEquals(3, events.size)
        assertEquals(AdEventName.LOAD, events.last().name)
        assertNotEquals(loadRequest.requestId, events.last().requestId)
    }

    @Test
    fun `initialization drains the latest queued configuration only once`() {
        setState(AdMobState.NOT_INITIALIZED)
        Ads.preloadBanner(Activity(), request, 320)
        setState(AdMobState.INITIALIZING)
        preloadOnce()
        assertTrue(ShadowBannerLoader.loads.isEmpty())
        assertTrue(ShadowBannerPreloader.calls.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(1, descriptors.size)
        setState(AdMobState.READY)
        ReflectionHelpers.callInstanceMethod<Void>(AdMobAds, "startPreloading")
        ReflectionHelpers.callInstanceMethod<Void>(AdMobAds, "startPreloading")
        assertEquals(1, ShadowBannerLoader.loads.size)
        assertEquals(AdEventName.LOAD, events.single().name)
        val ad = TestAd()
        loaded(ad)
        assertSame(ad.value, AdMobAds.pollBanner(request, AdSize.BANNER))
        assertTrue(ShadowBannerPreloader.calls.isEmpty())
    }

    @Test
    fun `switching refill mode destroys old resources and rejects the in flight late result`() {
        val id = preloadId(request)
        Ads.preloadBanner(Activity(), request, 320)
        preloadOnce()
        val obsoleteCallback = ShadowBannerLoader.loads.single()
        Ads.preloadBanner(Activity(), request, 320, autoRefill = true)
        val lateAd = TestAd()
        obsoleteCallback.onAdLoaded(lateAd.value)
        assertEquals(1, lateAd.destroyed)
        assertEquals(listOf("start:$id", "destroy:$id", "start:$id"), ShadowBannerPreloader.calls)
        preloadOnce()
        val cached = TestAd()
        loaded(cached)
        Ads.preloadBanner(Activity(), request, 320, autoRefill = true)
        assertEquals(1, cached.destroyed)
    }

    @Test
    fun `size changes destroy old inventory and late callbacks even when the old size returns`() {
        preloadOnce()
        val cached = TestAd()
        loaded(cached)
        AdMobAds.preloadBanner(request, AdSize.LARGE_BANNER, 1, autoRefill = false)
        assertEquals(1, cached.destroyed)
        val obsoleteCallback = ShadowBannerLoader.loads.last()
        preloadOnce()
        val lateAd = TestAd()
        obsoleteCallback.onAdLoaded(lateAd.value)
        assertEquals(1, lateAd.destroyed)
        assertNull(AdMobAds.pollBanner(request, AdSize.LARGE_BANNER))
        val current = TestAd()
        loaded(current)
        assertSame(current.value, AdMobAds.pollBanner(request, AdSize.BANNER))
        assertEquals(3, ShadowBannerLoader.loads.size)
        assertTrue(ShadowBannerPreloader.calls.isEmpty())
    }

    @Test
    fun `expiry uses request start age and never reloads`() {
        preloadOnce()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(59))
        val ad = TestAd()
        loaded(ad)
        preloadOnce()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        assertEquals(1, ad.destroyed)
        assertEquals(1, ShadowBannerLoader.loads.size)
        preloadOnce()
        assertEquals(2, ShadowBannerLoader.loads.size)
    }

    @Test
    fun `already expired late success is destroyed rather than cached`() {
        preloadOnce()
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        val ad = TestAd()
        loaded(ad)
        assertEquals(1, ad.destroyed)
        assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        assertEquals(1, ShadowBannerLoader.loads.size)
    }

    @Test
    fun `failure does not retry and a success after that terminal callback is destroyed`() {
        preloadOnce()
        ShadowBannerLoader.loads.single().onAdFailedToLoad(
            LoadAdError(LoadAdError.ErrorCode.NO_FILL, "no fill", null),
        )
        assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        val lateAd = TestAd()
        loaded(lateAd)
        assertEquals(1, lateAd.destroyed)
        assertEquals(1, ShadowBannerLoader.loads.size)
        assertTrue(ShadowBannerPreloader.calls.isEmpty())
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map { it.name })
        assertLoadIdentity(events.first(), events.last())
        assertEquals("no_fill", events.last().result)
        assertEquals("no_fill", events.last().reason)
        assertEquals("NO_FILL", events.last().errorCode)
        preloadOnce()
        assertEquals(2, ShadowBannerLoader.loads.size)
    }

    @Test
    fun `synchronous success waits for the actual SDK call before publishing request then result`() {
        val ad = TestAd()
        ShadowBannerLoader.duringLoad = { callback ->
            assertEquals(1, ShadowBannerLoader.loads.size)
            assertTrue(events.isEmpty())
            callback.onAdLoaded(ad.value)
            assertTrue(events.isEmpty())
        }
        preloadOnce()
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOADED), events.map { it.name })
        assertLoadIdentity(events.first(), events.last())
        assertEquals("filled", events.last().result)
        assertSame(ad.value, AdMobAds.pollBanner(request, AdSize.BANNER))
        assertEquals(2, events.size)
    }

    @Test
    fun `synchronous SDK failures preserve codes and controlled reasons with one terminal result`() {
        val errors = mapOf(
            LoadAdError.ErrorCode.NO_FILL to "no_fill",
            LoadAdError.ErrorCode.TIMEOUT to "timeout",
            LoadAdError.ErrorCode.CANCELLED to "cancelled",
        )
        for ((code, reason) in errors) {
            events.clear()
            ShadowBannerLoader.duringLoad = { callback ->
                assertTrue(events.isEmpty())
                val error = LoadAdError(code, "sdk message", null)
                callback.onAdFailedToLoad(error)
                callback.onAdFailedToLoad(error)
                assertTrue(events.isEmpty())
            }
            preloadOnce()
            assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map { it.name })
            assertLoadIdentity(events.first(), events.last())
            assertEquals(reason, events.last().result)
            assertEquals(reason, events.last().reason)
            assertEquals(code.name, events.last().errorCode)
            assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        }
        assertEquals(errors.size, ShadowBannerLoader.loads.size)
    }

    @Test
    fun `throwing SDK load publishes request then exception result and preserves the thrown error`() {
        val failure = IllegalStateException("sdk load threw")
        ShadowBannerLoader.duringLoad = {
            assertEquals(1, ShadowBannerLoader.loads.size)
            assertTrue(events.isEmpty())
            throw failure
        }
        assertSame(failure, runCatching { preloadOnce() }.exceptionOrNull())
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL), events.map { it.name })
        assertLoadIdentity(events.first(), events.last())
        assertEquals("error", events.last().result)
        assertEquals("load_exception", events.last().errorCode)
        assertEquals("exception", events.last().reason)
        assertNull(AdMobAds.pollBanner(request, AdSize.BANNER))
        ShadowBannerLoader.loads.single().onAdFailedToLoad(
            LoadAdError(LoadAdError.ErrorCode.NO_FILL, "late", null),
        )
        assertEquals(2, events.size)
    }

    @Test
    fun `synchronous terminal callback followed by a throw cannot publish a second result`() {
        val failure = IllegalStateException("after callback")
        val ad = TestAd()
        ShadowBannerLoader.duringLoad = { callback ->
            callback.onAdLoaded(ad.value)
            throw failure
        }
        assertSame(failure, runCatching { preloadOnce() }.exceptionOrNull())
        assertEquals(listOf(AdEventName.LOAD, AdEventName.LOADED), events.map { it.name })
        assertLoadIdentity(events.first(), events.last())
        assertEquals("filled", events.last().result)
        assertSame(ad.value, AdMobAds.pollBanner(request, AdSize.BANNER))
    }

    private fun assertLoadIdentity(request: AdEvent, result: AdEvent) {
        assertFalse(request.requestId.isNullOrBlank())
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.number, result.number)
        for (event in listOf(request, result)) {
            assertEquals(AdFormat.BANNER, event.format)
            assertEquals(AdPlatform.ADMOB, event.platform)
            assertEquals(this.request.adUnitId, event.adUnitId)
            assertEquals(event.requestId, event.sessionId)
            assertNull(event.slotId)
            assertFalse(event.analyticsParameters().containsKey("position"))
        }
    }

    private fun preloadOnce() = Ads.preloadBanner(Activity(), request, 320, autoRefill = false)
    private fun loaded(ad: TestAd) = ShadowBannerLoader.loads.last().onAdLoaded(ad.value)
    private fun setState(state: AdMobState) =
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", state)
    private fun preloadId(request: BannerRequest) =
        "cashcraft_banner:${request.adUnitId}:${request.position}:320x50"

    private class TestAd {
        var destroyed = 0
        val value = Proxy.newProxyInstance(BannerAd::class.java.classLoader, arrayOf(BannerAd::class.java)) {
            _, method, _ -> if (method.name == "destroy") { destroyed++; null } else null
        } as BannerAd
    }
}

/** Test-only public SDK boundaries: no SDK initialization or network requests. */
@Implements(
    className = "com.google.android.libraries.ads.mobile.sdk.banner.BannerAd\$Companion",
    isInAndroidSdk = false,
)
class ShadowBannerLoader {
    @Implementation
    fun load(request: BannerAdRequest, callback: AdLoadCallback<BannerAd>) {
        loads += callback
        duringLoad?.invoke(callback)
    }

    companion object {
        val loads = mutableListOf<AdLoadCallback<BannerAd>>()
        var duringLoad: ((AdLoadCallback<BannerAd>) -> Unit)? = null
    }
}

@Implements(
    className = "com.google.android.libraries.ads.mobile.sdk.banner.BannerAdPreloader\$Companion",
    isInAndroidSdk = false,
)
class ShadowBannerPreloader {
    @Implementation
    fun start(id: String, configuration: PreloadConfiguration): Boolean {
        calls += "start:$id"
        return configurations.putIfAbsent(id, configuration) == null
    }

    @Implementation
    fun pollAd(id: String): BannerAd? {
        calls += "poll:$id"
        return nextAd.also { nextAd = null }
    }

    @Implementation
    fun destroy(id: String): Boolean {
        calls += "destroy:$id"
        return configurations.remove(id) != null
    }

    companion object {
        val calls = mutableListOf<String>()
        val configurations = mutableMapOf<String, PreloadConfiguration>()
        var nextAd: BannerAd? = null
    }
}
