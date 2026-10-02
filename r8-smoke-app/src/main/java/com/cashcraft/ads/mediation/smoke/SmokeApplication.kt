package com.cashcraft.ads.mediation.smoke

import android.app.Application
import android.util.Log
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsConfig
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdRevenuePayload
import com.cashcraft.ads.mediation.BiddingProviderConfig
import com.cashcraft.ads.mediation.TopOnIds
import com.cashcraft.ads.mediation.TopOnProviderConfig
import com.cashcraft.ads.mediation.UmpConsentConfig

/** Compile/R8-only consumer that keeps every provider path reachable in the minified APK. */
class SmokeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Ads.initialize(application = this, config = if (BuildConfig.NATIVE_SMOKE) nativeSmokeConfig() else defaultSmokeConfig())
    }

    private fun defaultSmokeConfig() = AdsConfig(
        provider = BiddingProviderConfig(
            admob = AdMobProviderConfig(AdMobIds.TEST),
            topon = TopOnProviderConfig(
                TopOnIds(
                    applicationId = "smoke-app-id",
                    applicationKey = "smoke-app-key",
                    appOpenPlacementId = "smoke-app-open",
                    interstitialPlacementId = "smoke-interstitial",
                    rewardedPlacementId = "smoke-rewarded",
                ),
            ),
        ),
        umpConsent = UmpConsentConfig(enabled = false),
        loggingEnabled = true,
        revenueListener = AdRevenueListener { payload ->
            if (payload.format == AdFormat.BANNER) Log.i("BannerRevenue",
                "session_id=${payload.sessionId} response_id=${payload.impressionId} micros=${payload.valueMicros}")
        },
        autoShowAppOpen = false,
    )

    private fun nativeSmokeConfig(): AdsConfig {
        val admob = AdMobProviderConfig(AdMobIds.TEST)
        val topon = TopOnProviderConfig(
            TopOnIds(
                applicationId = BuildConfig.NATIVE_TEST_TOPON_APP_ID.ifBlank { "native-smoke-missing-application-id" },
                applicationKey = BuildConfig.NATIVE_TEST_TOPON_APP_KEY.ifBlank { "native-smoke-missing-application-key" },
                appOpenPlacementId = BuildConfig.NATIVE_TEST_APP_OPEN_PLACEMENT.ifBlank { "smoke-app-open" },
                interstitialPlacementId = BuildConfig.NATIVE_TEST_INTERSTITIAL_PLACEMENT.ifBlank { "smoke-interstitial" },
                rewardedPlacementId = BuildConfig.NATIVE_TEST_REWARDED_PLACEMENT.ifBlank { "smoke-rewarded" },
                nativePlacementId = BuildConfig.NATIVE_TEST_NATIVE_PLACEMENT.takeIf { it.isNotBlank() },
            ),
        )
        return AdsConfig(
            provider = when (BuildConfig.NATIVE_PLATFORM) {
                "topon" -> topon
                "bidding" -> BiddingProviderConfig(admob, topon)
                else -> admob
            },
            umpConsent = UmpConsentConfig(enabled = false),
            eventListener = AdEventListener(::logNativeEvent),
            revenueListener = AdRevenueListener(::logNativeRevenue),
            loggingEnabled = false,
            autoShowAppOpen = false,
        )
    }

    private fun logNativeEvent(event: AdEvent) {
        Log.i(
            TAG,
            "event=${event.name.analyticsName} platform=${event.platform.analyticsValue} " +
                "format=${event.format.analyticsValue} position=${event.position} " +
                "sessionId=${event.sessionId} requestId=${event.requestId} " +
                "adUnitId=${event.adUnitId} adSource=${event.adSource} " +
                "responseId=${event.responseId} result=${event.result} reason=${event.reason} " +
                "mode=${event.mediationMode.analyticsValue} winner=${event.winnerPlatform} " +
                "admobAvailable=${event.admobAvailable} toponAvailable=${event.topOnAvailable} " +
                "admobUsd=${event.admobValue} toponUsd=${event.topOnValue}",
        )
    }

    private fun logNativeRevenue(payload: AdRevenuePayload) {
        Log.i(
            TAG,
            "ilrd platform=${payload.platform.analyticsValue} format=${payload.format.analyticsValue} " +
                "eventId=${payload.eventId} sessionId=${payload.sessionId} " +
                "position=${payload.position} placementId=${payload.placementId} " +
                "impressionId=${payload.impressionId} valueMicros=${payload.valueMicros} " +
                "currency=${payload.currencyCode} adNetwork=${payload.adNetwork}",
        )
    }

    private companion object {
        const val TAG = "NativeSmoke"
    }
}
