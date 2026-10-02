@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation.internal.admob

import com.cashcraft.ads.mediation.AdConsentSnapshot
import com.cashcraft.ads.mediation.AdConsentStatus
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdMobConsentConfig
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdRewardResult
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.BuildConfig

/** Source-compatible provider API retained for existing hosts. New code should use `Ads`. */
typealias AdMobIds = com.cashcraft.ads.mediation.AdMobIds
typealias AdMobPreloadConfig = com.cashcraft.ads.mediation.AdMobPreloadConfig
typealias AdMobConsentConfig = AdMobConsentConfig
typealias AdMobConsentStatus = AdConsentStatus
typealias AdMobConsentSnapshot = AdConsentSnapshot
typealias AdMobFormat = AdFormat
typealias AdMobEventName = AdEventName
typealias AdMobEvent = AdEvent
typealias AdMobEventListener = AdEventListener
typealias AdMobShowResult = AdShowResult
typealias AdMobRewardResult = AdRewardResult

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
    val loggingEnabled: Boolean = BuildConfig.DEBUG,
    val logTag: String = "AdsMediation",
    val autoShowAppOpen: Boolean = true,
    val appOpenPosition: String = "app_foreground",
    val revenueListener: AdRevenueListener = AdRevenueListener.NONE,
    val mediationMode: AdMediationMode = AdMediationMode.ADMOB,
)
