package com.cashcraft.ads.mediation.smoke

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.cashcraft.ads.mediation.AdMobNativeAdRenderer
import com.cashcraft.ads.mediation.NativeAdRenderers
import com.cashcraft.ads.mediation.TopOnNativeAdRenderer
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.thinkup.nativead.api.TUNativeMaterial
import com.thinkup.nativead.api.TUNativePrepareInfo
import java.net.URL
import java.util.concurrent.Executors

/** Layout, assets and fullscreen presentation belong to the host; registration belongs to the SDK. */
internal fun smokeNativeRenderers(fullscreen: Boolean = false) = NativeAdRenderers(
    admob = object : AdMobNativeAdRenderer {
        override fun createView(context: Context) = NativeAdView(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, if (fullscreen) -1 else -2)
            addView(nativeContent(context, fullscreen))
        }
        override fun bind(view: NativeAdView, ad: NativeAd): MediaView {
            view.findViewById<TextView>(R.id.native_title).text = ad.headline
            view.findViewById<TextView>(R.id.native_body).text = ad.body
            view.findViewById<TextView>(R.id.native_advertiser).text = ad.advertiser
            view.findViewById<TextView>(R.id.native_cta).apply {
                text = ad.callToAction
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            val icon = view.findViewById<ImageView>(R.id.native_icon)
            icon.setImageDrawable(ad.icon?.drawable)
            icon.visibility = if (ad.icon == null) View.GONE else View.VISIBLE
            view.headlineView = view.findViewById(R.id.native_title)
            view.bodyView = view.findViewById(R.id.native_body)
            view.advertiserView = view.findViewById(R.id.native_advertiser)
            view.callToActionView = view.findViewById(R.id.native_cta)
            view.iconView = icon
            return MediaView(view.context).also { media ->
                view.findViewById<FrameLayout>(R.id.native_media).addView(media, FrameLayout.LayoutParams(-1, -1))
            }
        }
    },
    topon = object : TopOnNativeAdRenderer {
        override fun createView(context: Context): ViewGroup = nativeContent(context, fullscreen)
        override fun bind(view: ViewGroup, material: TUNativeMaterial): TUNativePrepareInfo {
            val title = view.findViewById<TextView>(R.id.native_title).apply { text = material.title }
            val body = view.findViewById<TextView>(R.id.native_body).apply { text = material.descriptionText }
            val advertiser = view.findViewById<TextView>(R.id.native_advertiser).apply { text = material.adFrom }
            val cta = view.findViewById<TextView>(R.id.native_cta).apply {
                text = material.callToActionText
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            val icon = view.findViewById<ImageView>(R.id.native_icon)
            loadIcon(icon, material.iconImageUrl)
            val media = material.getAdMediaView()
            val mediaHost = view.findViewById<FrameLayout>(R.id.native_media)
            if (media != null) mediaHost.addView(media, FrameLayout.LayoutParams(-1, -1))
            else mediaHost.visibility = View.GONE
            return TUNativePrepareInfo().apply {
                titleView = title
                descView = body
                adFromView = advertiser
                iconView = icon
                mainImageView = media
                ctaView = cta
                clickViewList = listOf(title, body, icon, cta)
            }
        }
    },
)

private fun nativeContent(context: Context, fullscreen: Boolean): ViewGroup =
    (LayoutInflater.from(context).inflate(
        if (fullscreen) R.layout.demo_native_fullscreen else R.layout.demo_native_card, null,
    ) as ViewGroup).apply {
        layoutParams = ViewGroup.LayoutParams(-1, if (fullscreen) -1 else -2)
        if (fullscreen) {
            findViewById<TextView>(R.id.native_title).setTextColor(Color.WHITE)
            findViewById<TextView>(R.id.native_body).setTextColor(Color.LTGRAY)
            findViewById<TextView>(R.id.native_advertiser).setTextColor(Color.LTGRAY)
        }
    }

// Small demo-only loader; a production host can use its normal lifecycle-aware image library.
private val iconExecutor = Executors.newSingleThreadExecutor()
private fun loadIcon(view: ImageView, url: String?) {
    if (url.isNullOrBlank()) { view.visibility = View.GONE; return }
    val target = java.lang.ref.WeakReference(view)
    iconExecutor.execute {
        val bitmap = runCatching {
            val connection = URL(url).openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
            connection.getInputStream().use(BitmapFactory::decodeStream)
        }.getOrNull()
        target.get()?.post { target.get()?.takeIf { it.isAttachedToWindow }?.setImageBitmap(bitmap) }
    }
}
