package com.cashcraft.ads.mediation.smoke

import android.app.Application
import android.util.Log
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsConfig
import com.cashcraft.ads.mediation.BiddingProviderConfig
import com.cashcraft.ads.mediation.TopOnIds
import com.cashcraft.ads.mediation.TopOnProviderConfig
import com.cashcraft.ads.mediation.UmpConsentConfig

/** Compile/R8-only consumer that keeps every provider path reachable in the minified APK. */
class SmokeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Ads.initialize(
            application = this,
            config = AdsConfig(
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
            ),
        )
    }
}
