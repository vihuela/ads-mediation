@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation.admob

import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdRevenueListener

/** Source-compatible provider API retained for existing hosts. New code should use `Ads`. */
typealias AdMobIds = com.cashcraft.ads.mediation.AdMobIds
typealias AdMobPreloadConfig = com.cashcraft.ads.mediation.AdMobPreloadConfig
typealias AdMobConsentConfig = com.cashcraft.ads.mediation.AdMobConsentConfig
typealias AdMobConsentStatus = com.cashcraft.ads.mediation.AdConsentStatus
typealias AdMobConsentSnapshot = com.cashcraft.ads.mediation.AdConsentSnapshot
typealias AdMobFormat = com.cashcraft.ads.mediation.AdFormat
typealias AdMobEventName = com.cashcraft.ads.mediation.AdEventName
typealias AdMobEvent = com.cashcraft.ads.mediation.AdEvent
typealias AdMobEventListener = com.cashcraft.ads.mediation.AdEventListener
typealias AdMobShowResult = com.cashcraft.ads.mediation.AdShowResult
typealias AdMobRewardResult = com.cashcraft.ads.mediation.AdRewardResult

/**
 * Legacy AdMob-only configuration retained only for existing integrations.
 * New integrations should use `com.cashcraft.ads.mediation.AdsConfig` with
 * `com.cashcraft.ads.mediation.Ads`. Do not mix the legacy entry point with `Ads`;
 * the legacy entry point does not provide the unified gates.
 */
@Deprecated(
    message = "Retained for existing integrations only. Use com.cashcraft.ads.mediation.AdsConfig " +
        "with com.cashcraft.ads.mediation.Ads for new integrations. Do not mix the legacy entry " +
        "point with Ads; the legacy entry point does not provide the unified gates.",
    level = DeprecationLevel.WARNING,
)
data class AdMobConfig(
    val ids: AdMobIds,
    val preload: AdMobPreloadConfig = AdMobPreloadConfig(),
    val eventListener: AdEventListener = AdEventListener.NONE,
    val loggingEnabled: Boolean = com.cashcraft.ads.mediation.BuildConfig.DEBUG,
    val logTag: String = "AdsMediation",
    val autoShowAppOpen: Boolean = true,
    val appOpenPosition: String = "app_foreground",
    val revenueListener: AdRevenueListener = AdRevenueListener.NONE,
    val mediationMode: AdMediationMode = AdMediationMode.ADMOB,
)
