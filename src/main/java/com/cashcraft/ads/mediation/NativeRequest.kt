package com.cashcraft.ads.mediation

/** 页面只提供业务位置和展示参数，平台与广告位由初始化配置决定。 */
data class NativeRequest(
    val position: String,
    /** 已确认的 TopOn 模板宽高比；自渲染广告不需要。 */
    val topOnTemplateAspectRatio: Float? = null,
    /** 竞价最长等待时间；到期后已加载广告可参与选择。 */
    val bidTimeoutMillis: Long = 7_000,
    /** 有可用缓存时立即选择；两端均无缓存时仍按竞价期限等待。 */
    val preferCachedAds: Boolean = false,
) {
    internal fun failureReason(): String? = when {
        position.isBlank() -> "invalid_position"
        topOnTemplateAspectRatio?.let { !it.isFinite() || it <= 0f } == true -> "invalid_template_ratio"
        bidTimeoutMillis <= 0 -> "invalid_native_bid_timeout"
        else -> null
    }
}

/** 仅内部加载、库存和事件链路持有真实广告位。 */
internal data class ResolvedNativeRequest(
    val platform: AdPlatform? = null,
    val adUnitId: String = "",
    val position: String,
    val topOnTemplateAspectRatio: Float? = null,
    val admobAdUnitId: String? = null,
    val topOnPlacementId: String? = null,
    val bidTimeoutMillis: Long = 7_000,
    val preferCachedAds: Boolean = false,
) {
    val isBidding: Boolean get() = platform == null && admobAdUnitId != null && topOnPlacementId != null

    fun failureReason(): String? = when {
        platform != null && (admobAdUnitId != null || topOnPlacementId != null) -> "native_conflicting_platform_selection"
        platform == null && adUnitId.isNotEmpty() -> "native_platform_required"
        platform != null && adUnitId.isBlank() -> "invalid_ad_unit_id"
        platform == null && admobAdUnitId == null && topOnPlacementId == null -> "native_not_configured"
        admobAdUnitId?.isBlank() == true || topOnPlacementId?.isBlank() == true -> "invalid_ad_unit_id"
        else -> NativeRequest(position, topOnTemplateAspectRatio, bidTimeoutMillis).failureReason()
    }

    fun candidates(): List<ResolvedNativeRequest> = if (platform != null) listOf(this) else buildList {
        admobAdUnitId?.let { add(ResolvedNativeRequest(AdPlatform.ADMOB, it, position, preferCachedAds = preferCachedAds)) }
        topOnPlacementId?.let { add(ResolvedNativeRequest(AdPlatform.TOPON, it, position, topOnTemplateAspectRatio,
            preferCachedAds = preferCachedAds)) }
    }
}

internal fun AdProviderConfig.resolveNativeRequest(request: NativeRequest, fullScreen: Boolean = false): ResolvedNativeRequest {
    val admobId = when (this) {
        is AdMobProviderConfig -> if (fullScreen) ids.fullScreenNativeId ?: ids.nativeId else ids.nativeId
        is BiddingProviderConfig -> if (fullScreen) admob.ids.fullScreenNativeId ?: admob.ids.nativeId else admob.ids.nativeId
        is TopOnProviderConfig -> null
    }
    val topOnId = when (this) {
        is TopOnProviderConfig -> if (fullScreen) ids.fullScreenNativePlacementId ?: ids.nativePlacementId else ids.nativePlacementId
        is BiddingProviderConfig -> if (fullScreen) topon.ids.fullScreenNativePlacementId ?: topon.ids.nativePlacementId else topon.ids.nativePlacementId
        is AdMobProviderConfig -> null
    }
    return ResolvedNativeRequest(
        position = request.position,
        topOnTemplateAspectRatio = request.topOnTemplateAspectRatio,
        admobAdUnitId = admobId,
        topOnPlacementId = topOnId,
        bidTimeoutMillis = request.bidTimeoutMillis,
        preferCachedAds = request.preferCachedAds,
    )
}

sealed interface NativeState {
    data object Idle : NativeState
    data object Loading : NativeState
    /** Loaded is not an impression. Only the platform can confirm exposure. */
    data object Loaded : NativeState
    data class Failed(val reason: String, val errorCode: String? = null) : NativeState
    data object Destroyed : NativeState
}
