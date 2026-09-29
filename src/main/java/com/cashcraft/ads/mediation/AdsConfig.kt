package com.cashcraft.ads.mediation

/** The SDK that owns a full-screen ad opportunity; bidding mode can keep both platforms active. */
enum class AdPlatform(val analyticsValue: String) {
    ADMOB("admob"),
    TOPON("topon"),
}

/** Host-selected mediation mode, independent of the platform that wins an individual impression. */
enum class AdMediationMode(val analyticsValue: String) {
    ADMOB("admob"),
    TOPON("topon"),
    BIDDING("bidding"),
}

/** Provider-specific configuration hidden behind the common [Ads] facade. */
sealed interface AdProviderConfig {
    val platform: AdPlatform
    fun adUnitId(format: AdFormat): String
}

internal val AdProviderConfig.mediationMode: AdMediationMode
    get() = when (this) {
        is AdMobProviderConfig -> AdMediationMode.ADMOB
        is TopOnProviderConfig -> AdMediationMode.TOPON
        is BiddingProviderConfig -> AdMediationMode.BIDDING
    }

/** AdMob application and ad-unit IDs. Production builds should inject their own values. */
data class AdMobIds(
    val applicationId: String,
    val appOpenId: String,
    val interstitialId: String,
    val rewardedId: String,
) {
    init {
        require(applicationId.isNotBlank()) { "applicationId must not be blank" }
        require(appOpenId.isNotBlank()) { "appOpenId must not be blank" }
        require(interstitialId.isNotBlank()) { "interstitialId must not be blank" }
        require(rewardedId.isNotBlank()) { "rewardedId must not be blank" }
    }

    companion object {
        /** Official Google demo IDs. Safe for local development and automated tests only. */
        val TEST = AdMobIds(
            applicationId = "ca-app-pub-3940256099942544~3347511713",
            appOpenId = "ca-app-pub-3940256099942544/9257395921",
            interstitialId = "ca-app-pub-3940256099942544/1033173712",
            rewardedId = "ca-app-pub-3940256099942544/5224354917",
        )
    }
}

/** Per-format limits for the GMA Next-Gen SDK's continuously replenished preload buffers. */
data class AdMobPreloadConfig(
    val appOpen: Int = DEFAULT_BUFFER_SIZE,
    val interstitial: Int = DEFAULT_BUFFER_SIZE,
    val rewarded: Int = DEFAULT_BUFFER_SIZE,
) {
    init {
        require(appOpen in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) { "appOpen must be in 1..15" }
        require(interstitial in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) { "interstitial must be in 1..15" }
        require(rewarded in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) { "rewarded must be in 1..15" }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 2
        private const val MIN_BUFFER_SIZE = 1
        private const val MAX_BUFFER_SIZE = 15
    }
}

/** UMP consent collection runs before any selected mediation provider is initialized. */
data class UmpConsentConfig(
    val enabled: Boolean = true,
    val tagForUnderAgeOfConsent: Boolean = false,
)

/** Source-compatible name retained for hosts that configured UMP through the AdMob provider. */
typealias AdMobConsentConfig = UmpConsentConfig

data class AdMobProviderConfig(
    val ids: AdMobIds,
    val preload: AdMobPreloadConfig = AdMobPreloadConfig(),
    val consent: UmpConsentConfig = UmpConsentConfig(),
) : AdProviderConfig {
    override val platform: AdPlatform = AdPlatform.ADMOB

    override fun adUnitId(format: AdFormat): String = when (format) {
        AdFormat.BANNER -> throw IllegalArgumentException("Banner requires an explicit ad unit ID")
        AdFormat.APP_OPEN -> ids.appOpenId
        AdFormat.INTERSTITIAL -> ids.interstitialId
        AdFormat.REWARDED -> ids.rewardedId
    }
}

/** TopOn overseas application credentials and placement IDs. */
data class TopOnIds(
    val applicationId: String,
    val applicationKey: String,
    val appOpenPlacementId: String,
    val interstitialPlacementId: String,
    val rewardedPlacementId: String,
) {
    init {
        require(applicationId.isNotBlank()) { "applicationId must not be blank" }
        require(applicationKey.isNotBlank()) { "applicationKey must not be blank" }
        require(appOpenPlacementId.isNotBlank()) { "appOpenPlacementId must not be blank" }
        require(interstitialPlacementId.isNotBlank()) { "interstitialPlacementId must not be blank" }
        require(rewardedPlacementId.isNotBlank()) { "rewardedPlacementId must not be blank" }
    }
}

data class TopOnProviderConfig(
    val ids: TopOnIds,
) : AdProviderConfig {
    override val platform: AdPlatform = AdPlatform.TOPON

    override fun adUnitId(format: AdFormat): String = when (format) {
        AdFormat.BANNER -> throw IllegalArgumentException("Banner requires an explicit placement ID")
        AdFormat.APP_OPEN -> ids.appOpenPlacementId
        AdFormat.INTERSTITIAL -> ids.interstitialPlacementId
        AdFormat.REWARDED -> ids.rewardedPlacementId
    }
}

/** Loads both providers for each format and chooses the available ad with the higher USD value. */
data class BiddingProviderConfig(
    val admob: AdMobProviderConfig,
    val topon: TopOnProviderConfig,
) : AdProviderConfig {
    // AdMob is the deterministic zero-price/no-candidate fallback for the unchanged event schema.
    override val platform: AdPlatform = AdPlatform.ADMOB

    override fun adUnitId(format: AdFormat): String = admob.adUnitId(format)
}

/** One immutable object is the complete host-side integration surface. */
data class AdsConfig(
    val provider: AdProviderConfig,
    /** Global gate shared by direct AdMob and every mediation provider that can include Google. */
    val umpConsent: UmpConsentConfig = when (provider) {
        is AdMobProviderConfig -> provider.consent
        is BiddingProviderConfig -> provider.admob.consent
        is TopOnProviderConfig -> UmpConsentConfig()
    },
    val eventListener: AdEventListener = AdEventListener.NONE,
    /** Dedicated ILRD channel; generic analytics never converts revenue events. */
    val revenueListener: AdRevenueListener = AdRevenueListener.NONE,
    /** Emits module-owned Logcat entries; enabled by default for debug variants. */
    val loggingEnabled: Boolean = BuildConfig.DEBUG,
    /** Logcat tag for module-owned ad and UMP diagnostics. */
    val logTag: String = "AdsMediation",
    val autoShowAppOpen: Boolean = true,
    val appOpenPosition: String = "app_foreground",
) {
    init {
        require(logTag.isNotBlank()) { "logTag must not be blank" }
    }
}
