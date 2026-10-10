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

/** Explicit business reward policy for an ordinary interstitial in a reward opportunity. */
enum class InterstitialRewardPolicy {
    NONE,
    /** Grant only after an impression followed by dismissal; does not imply completed viewing. */
    ON_DISMISSED,
}

/** Selected identity may be present even if showing fails; null means no candidate was selected. */
data class AdMixedShowResult(
    val platform: AdPlatform?,
    val format: AdFormat?,
    val showResult: AdShowResult,
    val sessionId: String?,
    /** True only when the rewarded SDK actually delivered its reward callback. */
    val sdkRewardEarned: Boolean = false,
    /** Business reward eligibility, including the explicitly selected interstitial reward policy. */
    val rewardEarned: Boolean = sdkRewardEarned,
)

internal fun mixedRewardEarned(
    format: AdFormat?,
    sdkRewardEarned: Boolean,
    showResult: AdShowResult,
    interstitialRewardPolicy: InterstitialRewardPolicy,
): Boolean = when (format) {
    AdFormat.REWARDED -> sdkRewardEarned
    AdFormat.INTERSTITIAL -> interstitialRewardPolicy == InterstitialRewardPolicy.ON_DISMISSED &&
        showResult == AdShowResult.Dismissed
    else -> false
}
