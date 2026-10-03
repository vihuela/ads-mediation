package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Looper
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdsInitializationTest {
    private val initialStage = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "initializationStage")
    private val initialAdMobState = AdMobAds.state

    @Before
    @After
    fun clearPendingInitialization() {
        // These tests stop before UMP or either ad SDK starts. Restore only the state they touch.
        val listener = ReflectionHelpers.getStaticField<AdLifecycleMonitor.Listener>(Ads::class.java, "lifecycleListener")
        AdLifecycleMonitor.removeListener(listener)
        val controller = ReflectionHelpers.getStaticField<Any?>(Ads::class.java, "autoBiddingAppOpenController")
        controller?.let {
            AdLifecycleMonitor.removeListener(ReflectionHelpers.getField(it, "lifecycleListener"))
        }
        val callbacks = ReflectionHelpers.getStaticField<Application.ActivityLifecycleCallbacks>(
            AdLifecycleMonitor::class.java, "callbacks",
        )
        val installedApplication = ReflectionHelpers.getStaticField<Application?>(
            AdLifecycleMonitor::class.java, "installedApplication",
        )
        installedApplication?.unregisterActivityLifecycleCallbacks(callbacks)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "installedApplication", null)
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationCallback", null)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", initialAdMobState)
        val bannerProviders = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "bannerProviders")
        ReflectionHelpers.getField<MutableMap<*, *>>(bannerProviders, "states").clear()
        listOf("application", "config", "mediationMode", "facadeEvents", "umpConsentManager", "autoBiddingAppOpenController")
            .forEach { ReflectionHelpers.setStaticField(Ads::class.java, it, null) }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage", initialStage)
    }

    @Test
    fun `configured Banner resolves before SDK readiness and keeps the requested position and size`() {
        initializePending()
        assertEquals(AdsState.INITIALIZING, Ads.state)
        assertEquals(
            BannerRequest(AdPlatform.ADMOB, checkNotNull(AdMobIds.TEST.bannerId), "footer", BannerSize.AnchoredAdaptive),
            Ads.bannerRequest("footer"),
        )
        assertEquals(BannerSize.Standard320x50, Ads.bannerRequest("footer", BannerSize.Standard320x50).size)
        assertThrows(IllegalArgumentException::class.java) { Ads.bannerRequest(" ") }
    }

    @Test
    fun `configured Banner rejects missing initialization and ID without affecting explicit requests`() {
        assertThrows(IllegalStateException::class.java) { Ads.bannerRequest("footer") }
        Ads.initialize(RuntimeEnvironment.getApplication(), AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST.copy(bannerId = null)), loggingEnabled = false,
        ))
        val error = assertThrows(IllegalStateException::class.java) { Ads.bannerRequest("footer") }
        assertTrue(error.message.orEmpty().contains("AdMobIds.bannerId"))
        assertEquals("explicit", BannerRequest(AdPlatform.ADMOB, "explicit", "footer", BannerSize.Standard320x50).adUnitId)
    }

    @Test
    fun `configured Banner selects only AdMob in bidding and rejects TopOn only`() {
        Ads.initialize(RuntimeEnvironment.getApplication(), AdsConfig(
            BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST), topOn()), loggingEnabled = false,
        ))
        assertEquals(AdPlatform.ADMOB, Ads.bannerRequest("footer").platform)
        assertEquals(AdMobIds.TEST.bannerId, Ads.bannerRequest("footer").adUnitId)
        clearPendingInitialization()
        Ads.initialize(RuntimeEnvironment.getApplication(), AdsConfig(topOn(), loggingEnabled = false))
        val error = assertThrows(IllegalStateException::class.java) { Ads.bannerRequest("footer") }
        assertEquals("topon_banner_not_supported", error.message)
    }

    @Test
    fun `repeated initialization accepts equal config and rejects changes without reporting success`() {
        val application = RuntimeEnvironment.getApplication()
        val provider = AdMobProviderConfig(AdMobIds.TEST)
        val config = AdsConfig(provider, loggingEnabled = false)
        var callbacks = 0
        assertNull(Ads.mediationMode)
        assertNull(Ads.platform)

        // No Activity resumes: UMP stays pending and no advertising SDK is initialized.
        Ads.initialize(application, config) { callbacks++ }
        Ads.initialize(application, config.copy()) { callbacks++ }
        assertEquals(AdMediationMode.ADMOB, Ads.mediationMode)
        assertEquals(AdPlatform.ADMOB, Ads.platform)

        val conflicts = listOf(
            config.copy(provider = BiddingProviderConfig(provider, topOn())),
            config.copy(provider = provider.copy(ids = provider.ids.copy(interstitialId = "other-id"))),
            config.copy(umpConsent = UmpConsentConfig(enabled = false)),
            config.copy(eventListener = AdEventListener { }),
        )
        conflicts.forEach { conflicting ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                Ads.initialize(application, conflicting) { callbacks++ }
            }
            assertTrue(error.message.orEmpty().contains("different AdsConfig"))
        }
        assertEquals(AdMediationMode.ADMOB, Ads.mediationMode)
        assertEquals(AdsState.INITIALIZING, Ads.state)
        assertEquals(0, callbacks)
        Ads.initialize(application, config)
    }

    @Test
    fun `bidding exposes its mode without a fixed platform and rejects another owner or mode`() {
        val application = RuntimeEnvironment.getApplication()
        val provider = BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST), topOn())
        val config = AdsConfig(provider, loggingEnabled = false)
        Ads.initialize(application, config)
        assertEquals(AdMediationMode.BIDDING, provider.mediationMode)
        assertEquals(AdMediationMode.BIDDING, Ads.mediationMode)
        assertNull(Ads.platform)

        assertThrows(IllegalArgumentException::class.java) {
            Ads.initialize(application, config.copy(provider = provider.admob))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ads.initialize(Application(), config)
        }
        Ads.initialize(application, config.copy())
        assertEquals(AdMediationMode.BIDDING, Ads.mediationMode)
        assertNull(Ads.platform)
    }

    @Test
    fun `Banner only rejects every full screen entry before SDK initialization or waiting`() {
        val events = mutableListOf<AdEvent>()
        Ads.initialize(RuntimeEnvironment.getApplication(), AdsConfig(
            provider = AdMobProviderConfig(AdMobIds(AdMobIds.TEST.applicationId)),
            eventListener = AdEventListener(events::add),
            loggingEnabled = false,
        ))
        // Disabled-format rejection precedes Activity eligibility and never starts either SDK.
        val activity = Activity()
        val results = mutableListOf<AdShowResult>()
        val rewardedResults = mutableListOf<AdRewardResult>()
        Ads.showAppOpen(activity, onResult = results::add)
        Ads.showInterstitial(activity, "disabled", results::add)
        Ads.showRewarded(activity, "disabled", rewardedResults::add)
        Ads.showAppOpenWhenReady(activity, onResult = results::add)
        Ads.showInterstitialWhenReady(activity, onResult = results::add)
        Ads.showRewardedWhenReady(activity, onResult = rewardedResults::add)

        assertEquals(List(4) { AdShowResult.Failed("ad_format_disabled") }, results)
        assertEquals(List(2) { AdShowResult.Failed("ad_format_disabled") }, rewardedResults.map { it.showResult })
        assertTrue(rewardedResults.none { it.rewardEarned })
        assertTrue(events.none { it.name == AdEventName.LOAD_REQUEST })
        assertEquals(AdsState.INITIALIZING, Ads.state)
        for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
            assertFalse(Ads.isReady(format))
        }
    }

    @Test
    fun `bidding rejects a disabled format but retains consent gating for an enabled format`() {
        val provider = BiddingProviderConfig(
            AdMobProviderConfig(AdMobIds(AdMobIds.TEST.applicationId, rewardedId = AdMobIds.TEST.rewardedId)),
            TopOnProviderConfig(TopOnIds("app-id", "app-key", rewardedPlacementId = "reward-id")),
        )
        Ads.initialize(RuntimeEnvironment.getApplication(), AdsConfig(provider, loggingEnabled = false))
        val activity = Activity()
        val results = mutableListOf<AdShowResult>()
        Ads.showAppOpen(activity, onResult = results::add)
        Ads.showInterstitialWhenReady(activity, onResult = results::add)
        Ads.showRewarded(activity, "enabled") { results += it.showResult }
        assertEquals(listOf(
            AdShowResult.Failed("ad_format_disabled"),
            AdShowResult.Failed("ad_format_disabled"),
            AdShowResult.Failed("consent_not_obtained"),
        ), results)
    }

    @Test
    fun `successful initialization notifies once`() = assertInitializationNotifiesOnce(true)

    @Test
    fun `failed initialization notifies once`() = assertInitializationNotifiesOnce(false)

    @Test
    fun `completion clears callback before invoking it`() {
        initializePending()
        val results = mutableListOf<Boolean>()
        Ads.observeInitialization {
            results += it
            if (results.size == 1) finishInitialization(false)
        }

        finishInitialization(true)

        assertEquals(listOf(true), results)
    }

    @Test
    fun `cancellation from another thread prevents notification`() {
        initializePending()
        val results = mutableListOf<Boolean>()
        val cancel = Ads.observeInitialization(results::add)
        var error: Throwable? = null
        Thread { error = runCatching { cancel(); cancel() }.exceptionOrNull() }
            .apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()

        finishInitialization(false)

        assertNull(error)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `replacement survives cancellation of the old listener`() {
        initializePending()
        val oldResults = mutableListOf<Boolean>()
        val newResults = mutableListOf<Boolean>()
        val cancelOld = Ads.observeInitialization(oldResults::add)
        Ads.observeInitialization(newResults::add)

        cancelOld()
        finishInitialization(false)

        assertTrue(oldResults.isEmpty())
        assertEquals(listOf(false), newResults)
    }

    @Test
    fun `terminal states notify synchronously on main and retain no callback`() {
        initializePending()
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        for (success in listOf(true, false)) {
            finishInitialization(success)
            assertEquals(if (success) AdsState.READY else AdsState.FAILED, Ads.state)
            val results = mutableListOf<Boolean>()
            var callbackLooper: Looper? = null

            val cancel = Ads.observeInitialization {
                results += it
                callbackLooper = Looper.myLooper()
            }

            assertEquals(listOf(success), results)
            assertEquals(Looper.getMainLooper(), callbackLooper)
            cancel()
            finishInitialization(success)
            assertEquals(listOf(success), results)
        }
    }

    @Test
    fun `throwing callback is not retained after completion`() {
        initializePending()
        var calls = 0
        Ads.observeInitialization {
            calls++
            throw IllegalStateException("callback failure")
        }

        finishInitialization(false)
        finishInitialization(false)

        assertEquals(1, calls)
        val results = mutableListOf<Boolean>()
        Ads.observeInitialization(results::add)
        assertEquals(listOf(false), results)
    }

    @Test
    fun `default initialization callback preserves waiter before and during initialization`() {
        val application = RuntimeEnvironment.getApplication()
        val config = AdsConfig(AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false)
        val results = mutableListOf<Boolean>()
        Ads.observeInitialization(results::add)
        assertTrue(results.isEmpty())

        Ads.initialize(application, config)
        Ads.initialize(application, config.copy())
        assertTrue(results.isEmpty())
        finishInitialization(false)

        assertEquals(listOf(false), results)
    }

    @Test
    fun `explicit initialize callback replaces the observer`() {
        val config = initializePending()
        val observerResults = mutableListOf<Boolean>()
        val initializeResults = mutableListOf<Boolean>()
        val cancelObserver = Ads.observeInitialization(observerResults::add)

        Ads.initialize(RuntimeEnvironment.getApplication(), config, initializeResults::add)
        cancelObserver()
        finishInitialization(false)

        assertTrue(observerResults.isEmpty())
        assertEquals(listOf(false), initializeResults)
    }

    @Test
    fun `registration outside main thread is rejected`() {
        var error: Throwable? = null
        var calls = 0
        Thread {
            error = runCatching { Ads.observeInitialization { calls++ } }.exceptionOrNull()
        }.apply { start(); join() }

        assertNotNull(error)
        initializePending()
        finishInitialization(false)
        assertEquals(0, calls)
    }

    private fun assertInitializationNotifiesOnce(success: Boolean) {
        initializePending()
        val results = mutableListOf<Boolean>()
        Ads.observeInitialization(results::add)
        assertTrue(results.isEmpty())

        finishInitialization(success)
        finishInitialization(success)

        assertEquals(listOf(success), results)
    }

    private fun initializePending(): AdsConfig {
        val config = AdsConfig(AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false)
        Ads.initialize(RuntimeEnvironment.getApplication(), config)
        return config
    }

    private fun finishInitialization(success: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            Ads, "finishInitialization",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, success),
        )
    }

    private fun topOn() = TopOnProviderConfig(
        TopOnIds("app-id", "app-key", "app-open", "interstitial", "rewarded"),
    )
}
