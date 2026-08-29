package com.cashcraft.ads.mediation

sealed interface AdShowResult {
    /** The full-screen ad was displayed and then dismissed by the user. */
    data object Dismissed : AdShowResult

    /** No impression occurred. [reason] is stable enough for logs and telemetry. */
    data class Failed(val reason: String) : AdShowResult
}

data class AdRewardResult(
    val rewardEarned: Boolean,
    val showResult: AdShowResult,
    /** Correlates the SDK reward callback with the later business settlement event. */
    val sessionId: String? = null,
)
