package com.cashcraft.ads.mediation

/** 当前广告的只读素材快照；不包含平台对象，也不赋予展示、点击或延长有效期的权利。 */
data class NativeAssets(
    val headline: String? = null,
    val body: String? = null,
    val callToAction: String? = null,
    val advertiser: String? = null,
    val mediaType: NativeMediaType = NativeMediaType.UNKNOWN,
    /** 素材宽高比（宽 / 高），平台未提供有效尺寸时为 null。 */
    val mediaAspectRatio: Float? = null,
    val adFrom: String? = null,
    val domain: String? = null,
    val warning: String? = null,
)

/** 仅接受平台明确的类型或真实主图，不根据视频时长猜测图片。 */
enum class NativeMediaType { IMAGE, VIDEO, UNKNOWN }

internal fun nativeMediaAspectRatio(ratio: Float): Float? =
    ratio.takeIf { it.isFinite() && it > 0f }

internal fun nativeMediaAspectRatio(width: Int, height: Int): Float? =
    if (width > 0 && height > 0) nativeMediaAspectRatio(width.toFloat() / height) else null
