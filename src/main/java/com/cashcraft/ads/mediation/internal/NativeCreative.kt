package com.cashcraft.ads.mediation.internal

import android.content.Context
import android.view.View
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobNextGenBidPrice
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.thinkup.core.api.AdError
import com.thinkup.core.api.TUAdConst
import com.thinkup.core.api.TUAdInfo
import com.thinkup.nativead.api.*

internal interface NativeCreative {
    val priceUsd: Double?
    val source: String?
    val responseId: String?
    fun isValid(): Boolean = true
    fun canRender(renderers: NativeAdRenderers): Boolean
    fun render(
        context: Context,
        renderers: NativeAdRenderers,
        session: AdShowSession,
        alive: () -> Boolean,
        impression: (Pair<String?, String?>) -> Unit,
        close: () -> Unit,
        ownView: (View) -> Unit,
    )
    fun destroy()
    fun pause() = Unit
    fun resume() = Unit
}

internal fun loadAdMobNative(
    id: String,
    loaded: (NativeCreative) -> Unit,
    failed: (String, String?, String?) -> Unit,
) {
    NativeAdLoader.load(
        NativeAdRequest.Builder(id, listOf(NativeAd.NativeAdType.NATIVE)).build(),
        object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) = loaded(AdMobNativeCreative(nativeAd))
            override fun onAdFailedToLoad(adError: LoadAdError) = failed(
                when (adError.code) {
                    LoadAdError.ErrorCode.NO_FILL -> "no_fill"
                    LoadAdError.ErrorCode.TIMEOUT -> "timeout"
                    LoadAdError.ErrorCode.CANCELLED -> "cancelled"
                    else -> "error"
                }, adError.code.value.toString(), adError.message,
            )
        },
    )
}

private class AdMobNativeCreative(private val ad: NativeAd) : NativeCreative {
    // Snapshot the loaded creative's pre-impression price. Failed reflection stays unknown, not zero.
    override val priceUsd: Double? = AdMobNextGenBidPrice.nativePrice(ad)
    override val source: String? get() = ad.getResponseInfo().loadedAdSourceResponseInfo?.name
    override val responseId: String? get() = ad.getResponseInfo().responseId
    override fun canRender(renderers: NativeAdRenderers) = renderers.admob != null

    override fun render(
        context: Context, renderers: NativeAdRenderers, session: AdShowSession,
        alive: () -> Boolean, impression: (Pair<String?, String?>) -> Unit,
        close: () -> Unit, ownView: (View) -> Unit,
    ) {
        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() = nativeOnMain { if (alive()) impression(source to responseId) }
            override fun onAdClicked() = nativeOnMain { if (alive()) session.emit(AdEventName.CLICK) }
            override fun onAdPaid(value: AdValue) = nativeOnMain {
                if (alive()) AdMobAds.nativePaid(session, value, ad.getResponseInfo())
            }
        }
        val renderer = checkNotNull(renderers.admob)
        val view = renderer.createView(context)
        check(view.parent == null) { "native_view_already_attached" }
        ownView(view)
        val mediaView = renderer.bind(view, ad)
        check(alive()) { "native_destroyed_during_render" }
        view.registerNativeAd(ad, mediaView)
    }

    override fun destroy() {
        ad.adEventCallback = null
        ad.destroy()
    }
}

internal fun loadTopOnNative(
    context: Context,
    id: String,
    loaded: (NativeCreative) -> Unit,
    failed: (String, String?, String?) -> Unit,
) {
    lateinit var loader: TUNative
    loader = TUNative(context, id, object : TUNativeNetworkListener {
        override fun onNativeAdLoaded() = nativeOnMain {
            loader.setAdListener(null)
            val ad = loader.nativeAd
            if (ad != null && ad.isValid) loaded(TopOnNativeCreative(ad))
            else {
                ad?.destory()
                failed("error", null, "native_ad_unavailable_after_load")
            }
        }
        override fun onNativeAdLoadFail(error: AdError) = nativeOnMain {
            loader.setAdListener(null)
            failed(when {
                error.code.contains("no_fill", true) || error.desc.contains("no fill", true) -> "no_fill"
                error.code.contains("timeout", true) || error.desc.contains("timeout", true) -> "timeout"
                else -> "error"
            }, error.code, error.desc)
        }
    })
    loader.makeAdRequest()
}

private class TopOnNativeCreative(private val ad: com.thinkup.nativead.api.NativeAd) : NativeCreative {
    override val priceUsd: Double? get() = ad.adInfo?.let { info ->
        info.getPublisherRevenue(TUAdConst.CURRENCY.USD).validPriceOrNull()
            ?: (info.getEcpm(TUAdConst.CURRENCY.USD) / 1_000.0).validPriceOrNull()
    }
    override val source: String? get() = ad.adInfo?.networkName
    override val responseId: String? get() = ad.adInfo?.requestId
    override fun isValid(): Boolean = ad.isValid
    override fun canRender(renderers: NativeAdRenderers) = ad.isNativeExpress || renderers.topon != null

    override fun render(
        context: Context, renderers: NativeAdRenderers, session: AdShowSession,
        alive: () -> Boolean, impression: (Pair<String?, String?>) -> Unit,
        close: () -> Unit, ownView: (View) -> Unit,
    ) {
        ad.setNativeEventListener(object : TUNativeEventListener {
            override fun onAdImpressed(view: TUNativeAdView, info: TUAdInfo) = nativeOnMain {
                if (alive()) impression(info.networkName to info.showId)
            }
            override fun onAdClicked(view: TUNativeAdView, info: TUAdInfo) = nativeOnMain {
                if (alive()) session.emit(AdEventName.CLICK, adSource = info.networkName, responseId = info.showId)
            }
            override fun onAdVideoStart(view: TUNativeAdView?) = Unit
            override fun onAdVideoEnd(view: TUNativeAdView?) = Unit
            override fun onAdVideoProgress(view: TUNativeAdView?, progress: Int) = Unit
        })
        ad.setAdRevenueListener { info -> nativeOnMain {
            if (alive()) TopOnAds.nativePaid(session, info)
        } }
        ad.setDislikeCallbackListener(object : TUNativeDislikeListener() {
            override fun onAdCloseButtonClick(view: TUNativeAdView?, info: TUAdInfo) = nativeOnMain {
                if (alive()) close()
            }
        })
        val wrapper = TUNativeAdView(context)
        ownView(wrapper)
        if (ad.isNativeExpress) {
            ad.renderAdContainer(wrapper, null)
            ad.prepare(wrapper, null)
        } else {
            val renderer = checkNotNull(renderers.topon)
            val content = renderer.createView(context)
            check(content.parent == null) { "native_view_already_attached" }
            val prepareInfo = renderer.bind(content, ad.adMaterial)
            check(alive()) { "native_destroyed_during_render" }
            ad.renderAdContainer(wrapper, content)
            ad.prepare(wrapper, prepareInfo)
        }
    }

    override fun pause() = ad.onPause()
    override fun resume() = ad.onResume()
    override fun destroy() {
        ad.setNativeEventListener(null)
        ad.setAdRevenueListener(null)
        ad.setDislikeCallbackListener(null)
        ad.destory()
    }
}

internal fun nativeOnMain(action: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
    else android.os.Handler(android.os.Looper.getMainLooper()).post(action)
}
