package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdsInitializationTest {
    private val initialStage = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "initializationStage")

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
        ReflectionHelpers.getStaticField<MutableList<*>>(Ads::class.java, "initializationListeners").clear()
        val bannerProviders = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "bannerProviders")
        ReflectionHelpers.getField<MutableMap<*, *>>(bannerProviders, "states").clear()
        listOf("application", "config", "mediationMode", "facadeEvents", "umpConsentManager", "autoBiddingAppOpenController")
            .forEach { ReflectionHelpers.setStaticField(Ads::class.java, it, null) }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage", initialStage)
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

    private fun topOn() = TopOnProviderConfig(
        TopOnIds("app-id", "app-key", "app-open", "interstitial", "rewarded"),
    )
}
