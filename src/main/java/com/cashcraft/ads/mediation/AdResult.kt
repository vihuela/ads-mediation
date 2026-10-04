package com.cashcraft.ads.mediation

sealed interface AdShowResult {
    data class Blocked(val reason: AdBlockReason) : AdShowResult, BannerState
    /** The full-screen ad was displayed and then dismissed by the user. */
    data object Dismissed : AdShowResult

    /**
     * A full-screen show attempt or Banner request failed; [reason] describes it for diagnostics.
     * Full-screen failures mean no impression occurred for that attempt. A Banner failure does not
     * negate earlier impressions or revenue, and SDK refresh may recover the same Banner.
     */
    data class Failed(val reason: String) : AdShowResult, BannerState
}

data class AdRewardResult(
    val rewardEarned: Boolean,
    val showResult: AdShowResult,
    /** Correlates the SDK reward callback with the later business settlement event. */
    val sessionId: String? = null,
)
