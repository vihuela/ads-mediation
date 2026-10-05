package com.cashcraft.ads.mediation.internal.nativeads

import android.app.Activity
import android.view.View
import com.cashcraft.ads.mediation.NativeAssets
import com.cashcraft.ads.mediation.NativeLayoutBinding
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.internal.BidDecision

/** Only the two real Native SDK implementations share this boundary. */
internal fun interface NativeProvider {
    fun load(activity: Activity, request: ResolvedNativeRequest, widthPx: Int, callbacks: NativeCallbacks): NativeLoad
}

internal fun interface NativeLoad {
    /** Detaches UI delivery; it does not promise cancellation of the platform network/cache. */
    fun cancel()
}

internal interface NativeAdHandle {
    /** 只读素材不携带平台对象或独立展示权。 */
    val assets: NativeAssets get() = NativeAssets()
    /** 未取得逐来源设备证据时，保留请求安全降级为释放。 */
    fun pauseForRetention(): Boolean = false
    fun resumeAfterRetention(): Boolean = false
    val platform: AdPlatform? get() = null
    /** USD per impression, read before display. Null is unknown, never an inferred zero. */
    val bidPriceUsd: Double? get() = null
    val adSource: String?
    val responseId: String?
    val isTemplate: Boolean
    val isValid: Boolean get() = true
    /** Only never-rendered objects whose SDK callbacks can be rebound may enter the shared cache. */
    val canCache: Boolean get() = false
    fun setCallbacks(callbacks: NativeCallbacks?) = Unit
    /** Monotonic expiry when known; TopOn must not invent a load time for cached objects. */
    val expiresAtMillis: Long?
    /** AdMob 已有不可重置的原期限；TopOn 将首次保留上限写回自身，领取后继续使用。 */
    fun bindDeadline(deadline: Long) = Unit
    /** Null binding is allowed only for a platform template. Listener installation precedes this. */
    fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View
    fun destroy()
}

/** Platform data, not a second public revenue channel. Conversion uses the existing payloads. */
internal data class NativeRevenue(
    val valueMicros: Long,
    val currencyCode: String,
    val adSource: String?,
    val responseId: String?,
    val precisionType: String?,
    val mediationAdapterClassName: String? = null,
    val topOnAdInfo: Any? = null,
)

internal interface NativeCallbacks {
    val isActive: Boolean get() = true
    fun loadStarted(platform: AdPlatform) = Unit
    fun candidateLoaded(platform: AdPlatform, ad: NativeAdHandle) = Unit
    fun candidateFailed(platform: AdPlatform, reason: String, errorCode: String? = null) = Unit
    fun bidResult(decision: BidDecision) = Unit
    fun loaded(ad: NativeAdHandle)
    fun failed(reason: String, errorCode: String? = null)
    /** Actual SDK exposure; revenue is supplied only by TopOn onAdImpressed. */
    fun impression(adSource: String?, responseId: String?, revenue: NativeRevenue? = null)
    fun clicked(adSource: String?, responseId: String?)
    fun closed()
    fun overlayOpened()
    fun overlayClosed()
    fun paid(revenue: NativeRevenue)
}
