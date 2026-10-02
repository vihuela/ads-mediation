package com.cashcraft.ads.mediation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Keep Custom instances stable (remember in Compose). Each factory call creates fresh views. */
sealed interface NativeLayout {
    data object Default : NativeLayout
    class Custom private constructor(
        val factory: (Context) -> NativeLayoutBinding,
        private val assetsFactory: ((Context, NativeAssets) -> NativeLayoutBinding)?,
    ) : NativeLayout {
        /** 保留旧构造及 factory 属性，旧布局不依赖素材。 */
        constructor(factory: (Context) -> NativeLayoutBinding) : this(factory, null)

        internal fun create(context: Context, assets: NativeAssets): NativeLayoutBinding =
            assetsFactory?.invoke(context, assets) ?: factory(context)

        companion object {
            /** 素材仅用于选择布局；实际文案填充与平台注册仍由库完成。 */
            fun withAssets(factory: (Context, NativeAssets) -> NativeLayoutBinding): Custom =
                Custom({ context -> factory(context, NativeAssets()) }, factory)
        }
    }
}

/** Views belong to root; provided media/icon/AdChoices containers must initially be empty.
 * Media-free layouts support AdMob image/text and identified TopOn Pangle image ads with an icon.
 * Video and other TopOn sources require a media slot until their compact format is supported.
 * TopOn requires slots for returned body/icon/source/domain/warning assets; advertiser is the
 * legacy source fallback when adFrom is absent. close and advertiserInfo are optional SDK controls.
 */
data class NativeLayoutBinding(
    val root: View,
    val headline: TextView,
    val callToAction: TextView,
    val media: ViewGroup? = null,
    val adLabel: TextView,
    val adChoices: ViewGroup,
    val body: TextView? = null,
    val advertiser: TextView? = null,
    val icon: ViewGroup? = null,
    val adFrom: TextView? = null,
    val domain: TextView? = null,
    val warning: TextView? = null,
    val close: View? = null,
    val advertiserInfo: View? = null,
) {
    internal fun validate() {
        require(root.parent == null) { "native_layout_root_attached" }
        val assets = listOfNotNull(headline, callToAction, media, adLabel, adChoices, body, advertiser, icon,
            adFrom, domain, warning, close, advertiserInfo)
        require(assets.distinct().size == assets.size) { "native_layout_duplicate_asset" }
        require(assets.all { it !== root && it.isDescendantOf(root) }) { "native_layout_asset_outside_root" }
        require(listOfNotNull(media, adChoices, icon).all { it.childCount == 0 }) { "native_layout_occupied_asset" }
        require(adLabel.text.isNotBlank() && adLabel.visibility == View.VISIBLE) { "native_layout_missing_ad_label" }
    }
}

/** Check the measured SDK media view, including space lost to the host's padding. */
internal fun View.requireNativeVideoSize(widthPx: Int = measuredWidth, heightPx: Int = measuredHeight) {
    val minimumPx = 120 * resources.displayMetrics.density
    require(widthPx >= minimumPx && heightPx >= minimumPx) { "native_video_media_too_small" }
}

internal fun View.observeNativeVideoSize(onInvalid: () -> Unit): () -> Unit {
    requireNativeVideoSize()
    val listener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
        if (runCatching { requireNativeVideoSize(r - l, b - t) }.isFailure) onInvalid()
    }
    addOnLayoutChangeListener(listener)
    return { removeOnLayoutChangeListener(listener) }
}

private fun View.isDescendantOf(root: View): Boolean {
    var ancestor = parent
    while (ancestor is View) {
        if (ancestor === root) return true
        ancestor = ancestor.parent
    }
    return false
}
