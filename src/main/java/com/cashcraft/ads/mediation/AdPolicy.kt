package com.cashcraft.ads.mediation

/** A host-owned snapshot. Missing platform/position entries are enabled. */
data class AdPolicy(
    val enabled: Boolean = true,
    val platforms: Map<AdPlatform, Boolean> = emptyMap(),
    val positions: Map<String, Boolean> = emptyMap(),
    val frequency: AdFrequencyPolicy = AdFrequencyPolicy(),
)

data class AdFrequencyPolicy @JvmOverloads constructor(
    val enabled: Boolean = false,
    val newUserDelaySeconds: Long = 0,
    val fullscreenGapSeconds: Long = 0,
    val dailyMaxShows: Long = Long.MAX_VALUE,
    val dailyMaxClicks: Long = Long.MAX_VALUE,
    /** Null selects legacy global quotas; even an empty map selects main-type quotas. */
    val mainTypeQuotas: Map<AdMainType, AdMainTypeQuota>? = null,
)

/** Host entry identity, independent of the provider, position, or eventual ad format. */
enum class AdMainType(val configKey: String) {
    OPEN("open"),
    INTER("inter"),
    NATIVE_FULLSCREEN("native_fullscreen"),
    REWARDED("rewarded"),
    NATIVE("native"),
    BANNER("banner"),
}

data class AdMainTypeQuota(
    val enabled: Boolean = false,
    val dailyMaxShows: Long? = null,
    val dailyMaxClicks: Long? = null,
)

sealed interface AdPolicyCheckResult {
    data object Passed : AdPolicyCheckResult
    data class Blocked(val reason: AdBlockReason) : AdPolicyCheckResult
}

/** Business identity only; provider and format are deliberately absent. */
data class AdBlockInfo(val position: String, val reason: AdBlockReason)

enum class AdBlockReason(val code: String) {
    GLOBAL_DISABLED("global_disabled"),
    POSITION_DISABLED("position_disabled"),
    NEW_USER_PROTECTION("new_user_protection"),
    FULLSCREEN_GAP("fullscreen_gap"),
    DAILY_SHOW_LIMIT("daily_show_limit"),
    DAILY_CLICK_LIMIT("daily_click_limit"),
    INVALID_MAIN_TYPE("invalid_main_type"),
}
