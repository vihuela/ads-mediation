package com.cashcraft.ads.mediation.internal.nativeads

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cashcraft.ads.mediation.NativeLayoutBinding
import com.cashcraft.ads.mediation.R
import kotlin.math.roundToInt

/** Only the built-in card opts into asset-sized media; custom containers own their size. */
internal class DefaultNativeMediaView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var aspectRatio = 0.0

    fun setMediaDimensions(width: Int, height: Int, imageCount: Int = 1) {
        if (width <= 0 || height <= 0) return
        aspectRatio = width.toDouble() * imageCount / height
        layoutParams = layoutParams.apply { this.height = ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspectRatio > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val contentWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val height = (contentWidth / aspectRatio + paddingTop + paddingBottom).roundToInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                resolveSize(height, heightMeasureSpec), MeasureSpec.EXACTLY,
            ))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

internal fun createDefaultNativeLayout(context: Context): NativeLayoutBinding {
    val root = LayoutInflater.from(context).inflate(R.layout.ads_native_default, null, false)
    return NativeLayoutBinding(
        root = root,
        headline = root.requireView(R.id.ads_native_headline),
        callToAction = root.requireView(R.id.ads_native_call_to_action),
        media = root.requireView(R.id.ads_native_media),
        adLabel = root.requireView(R.id.ads_native_ad_label),
        adChoices = root.requireView(R.id.ads_native_ad_choices),
        body = root.requireView(R.id.ads_native_body),
        advertiser = root.requireView(R.id.ads_native_advertiser),
        icon = root.requireView(R.id.ads_native_icon),
        adFrom = root.requireView(R.id.ads_native_ad_from),
        domain = root.requireView(R.id.ads_native_domain),
        warning = root.requireView(R.id.ads_native_warning),
        advertiserInfo = root.requireView(R.id.ads_native_advertiser_info),
    )
}

private inline fun <reified T : View> View.requireView(id: Int): T =
    findViewById<T>(id) ?: error("native_layout_missing_view_$id")
