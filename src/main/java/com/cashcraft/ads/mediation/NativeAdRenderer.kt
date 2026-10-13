package com.cashcraft.ads.mediation

import android.content.Context
import android.view.ViewGroup
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.thinkup.nativead.api.TUNativeMaterial
import com.thinkup.nativead.api.TUNativePrepareInfo

/** Per-opportunity layouts: use the same contract for a feed card, dialog, or host activity. */
data class NativeAdRenderers(
    val admob: AdMobNativeAdRenderer? = null,
    val topon: TopOnNativeAdRenderer? = null,
)

interface AdMobNativeAdRenderer {
    /** Return a fresh, unattached view. All ad assets must be inside this view. */
    fun createView(context: Context): NativeAdView

    /**
     * Populate assets and assign headlineView, callToActionView, etc. Return the media view, if any.
     * The library calls registerNativeAd. Do not replace callbacks, register, or destroy the ad.
     */
    fun bind(view: NativeAdView, ad: NativeAd): MediaView?
}

interface TopOnNativeAdRenderer {
    /** Return a fresh, unattached content view. The library supplies the TUNativeAdView wrapper. */
    fun createView(context: Context): ViewGroup

    /** Populate assets and describe interactive views. The library calls renderAdContainer/prepare. */
    fun bind(view: ViewGroup, material: TUNativeMaterial): TUNativePrepareInfo
}

sealed interface NativeAdShowResult {
    /** View attached successfully; an impression is reported only by the provider SDK callback. */
    data class Rendered(val handle: NativeAdHandle) : NativeAdShowResult
    data class Failed(val reason: String) : NativeAdShowResult
}

/** One rendered ad. Release on view recycling, Fragment.onDestroyView, or Compose onDispose. */
interface NativeAdHandle {
    val platform: AdPlatform
    val sessionId: String
    val isDestroyed: Boolean

    /** Idempotent. Removes only this ad's view and releases its provider resources on the main thread. */
    fun destroy()
}
