package com.cashcraft.ads.mediation

/** UI status only; Ready is not proof of an impression or revenue. */
sealed interface BannerState {
    data object Inactive : BannerState
    data object Waiting : BannerState
    data object Loading : BannerState
    data object Ready : BannerState
    data class Failed(val reason: String) : BannerState
    data object Destroyed : BannerState
}
