package com.cashcraft.ads.mediation

/** Requested Banner size; constructing it does not certify support by a selected provider. */
sealed interface BannerSize {
    /** A fixed 320 × 50 dp ad; the host must provide at least 320 dp of content width. */
    data object Standard320x50 : BannerSize

    /** A compact anchored adaptive request using the host's available content width. */
    data object StandardAnchoredAdaptive : BannerSize

    /** A large anchored adaptive request; preserves this option's original sizing behavior. */
    data object AnchoredAdaptive : BannerSize
}

/** Values identifying one Banner placement; an equal request keeps the same host binding. */
data class BannerRequest(
    val platform: AdPlatform,
    val adUnitId: String,
    val position: String,
    val size: BannerSize,
) {
    init {
        require(adUnitId.isNotBlank()) { "adUnitId must not be blank" }
        require(position.isNotBlank()) { "position must not be blank" }
    }

    /** Call after a positive content width is measured, before asking the selected SDK to load. */
    internal fun sizeError(contentWidthDp: Int): String? {
        require(contentWidthDp > 0) { "contentWidthDp must be positive" }
        return if (size == BannerSize.Standard320x50 && contentWidthDp < 320) {
            "320 x 50 dp Banner requires at least 320 dp content width"
        } else null
    }

    internal fun supportError(): String? = when (platform) {
        AdPlatform.ADMOB -> null
        AdPlatform.TOPON -> "topon_banner_not_supported"
    }
}
