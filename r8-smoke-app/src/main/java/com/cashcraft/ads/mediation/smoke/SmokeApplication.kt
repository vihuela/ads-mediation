package com.cashcraft.ads.mediation.smoke

import android.app.Application
import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsConfig
import com.cashcraft.ads.mediation.BiddingProviderConfig
import com.cashcraft.ads.mediation.TopOnIds
import com.cashcraft.ads.mediation.TopOnProviderConfig
import com.cashcraft.ads.mediation.UmpConsentConfig

/** Demo/R8 consumer. TopOn development IDs come from short-drama/config/app.properties. */
class SmokeApplication : Application() {
    var providerName: String? = null
        private set

    fun initializeAds(providerName: String, autoMode: String = "off") {
        if (this.providerName != null) return
        this.providerName = providerName
        Ads.initialize(
            application = this,
            config = AdsConfig(
                provider = BiddingProviderConfig(
                    admob = AdMobProviderConfig(AdMobIds.TEST),
                    topon = TopOnProviderConfig(
                        TopOnIds(
                            applicationId = "h1h7t76tddrt25",
                            applicationKey = "a5892462779e07420fcf84e9e6b341ece",
                            appOpenPlacementId = "n1h7t76tij7hlj",
                            interstitialPlacementId = "n1h7t76tg76hja",
                            rewardedPlacementId = "n1h7t76tevmst6",
                            nativePlacementId = "n1h7t76th560qm",
                        ),
                    ),
                ).let { bidding ->
                    when (providerName) {
                        "admob" -> bidding.admob
                        "topon" -> bidding.topon
                        else -> bidding
                    }
                },
                umpConsent = UmpConsentConfig(enabled = false),
                loggingEnabled = true,
                autoShowAppOpen = autoMode != "off",
                autoAppOpenMode = if (autoMode == "mixed") com.cashcraft.ads.mediation.AutoAppOpenMode.APP_OPEN_OR_INTERSTITIAL
                    else com.cashcraft.ads.mediation.AutoAppOpenMode.APP_OPEN_ONLY,
                eventListener = DemoAudit,
            ),
        )
    }
}
