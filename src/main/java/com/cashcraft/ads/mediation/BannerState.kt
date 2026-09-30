package com.cashcraft.ads.mediation

/** UI status only; Ready is not proof of an impression or revenue. Failures use [AdShowResult.Failed]. */
sealed interface BannerState {
    data object Inactive : BannerState
    data object Waiting : BannerState
    data object Loading : BannerState
    data object Ready : BannerState
    data object Destroyed : BannerState
}
