package com.cashcraft.ads.mediation.internal.nativeads

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.cashcraft.ads.mediation.NativeAssets
import com.cashcraft.ads.mediation.NativeMediaType
import com.cashcraft.ads.mediation.nativeMediaAspectRatio
import com.cashcraft.ads.mediation.NativeLayoutBinding
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.admob.AdMobNextGenBidPrice
import com.cashcraft.ads.mediation.observeNativeVideoSize
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoadResult
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class AdMobNativeProvider : NativeProvider {
    override fun load(
        activity: Activity,
        request: ResolvedNativeRequest,
        widthPx: Int,
        callbacks: NativeCallbacks,
    ): NativeLoad {
        require(widthPx > 0) { "native_invalid_width" }

        AdMobNextGenBidPrice.initialize(activity.applicationContext)
        val settled = AtomicBoolean(false)
        val adRequest = NativeAdRequest.Builder(
            request.adUnitId,
            listOf(NativeAd.NativeAdType.NATIVE),
        ).build()

        NativeAdLoader.load(
            adRequest,
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) = NativeMainThread.run {
                    if (!settled.compareAndSet(false, true)) {
                        nativeAd.destroy()
                        return@run
                    }
                    val handle = try { AdMobNativeAdHandle(nativeAd, callbacks) } catch (_: Exception) {
                        runCatching { nativeAd.destroy() }
                        callbacks.failed("native_load_failed")
                        return@run
                    }
                    callbacks.loaded(handle)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = NativeMainThread.run {
                    if (!settled.compareAndSet(false, true)) return@run
                    callbacks.failed(
                        if (adError.code == LoadAdError.ErrorCode.NO_FILL) "no_fill" else "native_load_failed",
                        adError.code.toString(),
                    )
                }
            },
        )

        return NativeLoad { settled.set(true) }
    }
}

/** 共用生产句柄；预加载入口在报价门槛通过前仅由设备验收调用。 */
internal class AdMobNativeInventory(
    private val id: String,
    private val adUnitId: String,
) {
    private var token = 0L
    private var active = false
    private var deadline: Long? = null

    fun prepare(expiresAt: Long, complete: (Boolean) -> Unit): NativeLoad {
        check(!active) { "native_preload_already_started" }
        active = true
        deadline = expiresAt
        val generation = ++token
        val request = NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE)).build()
        val started = try {
            NativeAdPreloader.start(id, PreloadConfiguration(request, 1), object : PreloadCallback {
                override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                    NativeMainThread.post { if (active && token == generation) complete(available()) }
                }
                override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                    NativeMainThread.post { if (active && token == generation) complete(available()) }
                }
                override fun onAdsExhausted(preloadId: String) {
                    NativeMainThread.post { if (active && token == generation) complete(false) }
                }
            })
        } catch (failure: Exception) {
            close()
            throw failure
        }
        if (!started) { close(); complete(false) }
        return NativeLoad {
            // 停止本层状态交付；实际 SDK 会话由 close 显式销毁。
            if (token == generation) token++
        }
    }

    fun available(): Boolean = active && deadline?.let { SystemClock.elapsedRealtime() < it } == true &&
        NativeAdPreloader.getNumAdsAvailable(id) > 0

    fun peekIdentity(): String? = if (available()) NativeAdPreloader.peekAdResponseInfo(id)?.responseId else null

    fun take(callbacks: NativeCallbacks): NativeAdHandle? {
        if (!available()) return null
        val end = checkNotNull(deadline)
        val result = NativeAdPreloader.pollAd(id)
        if (result !is NativeAdLoadResult.NativeAdSuccess) return null
        return try {
            AdMobNativeAdHandle(result.ad, callbacks, end)
        } catch (failure: Exception) {
            runCatching { result.ad.destroy() }
            throw failure
        }
    }

    fun close() {
        val wasActive = active
        token++
        active = false
        deadline = null
        if (wasActive) NativeAdPreloader.destroy(id)
    }
}

private class AdMobNativeAdHandle(
    private val nativeAd: NativeAd,
    @Volatile private var listener: NativeCallbacks?,
    override val expiresAtMillis: Long = SystemClock.elapsedRealtime() + NATIVE_AD_MAX_AGE_MILLIS,
) : NativeAdHandle {
    private val destroyed = AtomicBoolean(false)
    private var nativeAdView: NativeAdView? = null
    private var removeMediaSizeListener: (() -> Unit)? = null

    private val responseInfo: ResponseInfo = nativeAd.getResponseInfo()

    override val platform: AdPlatform = AdPlatform.ADMOB
    override val bidPriceUsd: Double? = AdMobNextGenBidPrice.fromNative(nativeAd)

    override val adSource: String?
        get() = responseInfo.loadedAdSourceResponseInfo?.name

    override val responseId: String?
        get() = responseInfo.responseId

    // 缺少真实主图且未明确有视频时保留 UNKNOWN，不以时长为零推断图片。
    override val assets: NativeAssets = nativeAd.mediaContent.let { media ->
        NativeAssets(
            headline = nativeAd.headline?.takeIf(String::isNotBlank),
            body = nativeAd.body?.takeIf(String::isNotBlank),
            callToAction = nativeAd.callToAction?.takeIf(String::isNotBlank),
            advertiser = nativeAd.advertiser?.takeIf(String::isNotBlank),
            mediaType = adMobNativeMediaType(media.hasVideoContent, media.mainImage != null),
            mediaAspectRatio = nativeMediaAspectRatio(media.aspectRatio),
            // AdMob 没有对应的来源披露文案接口，不用广告网络名替代 adFrom。
        )
    }

    override val isTemplate: Boolean = false
    override val isValid: Boolean get() = !destroyed.get()
    override val canCache: Boolean get() = isValid && nativeAdView == null
    // 仅启用实测的 Google 图片来源；视频、未知素材及第三方适配器仍释放降级。
    override fun pauseForRetention(): Boolean = isValid && nativeAdView != null &&
        assets.mediaType == NativeMediaType.IMAGE && !nativeAd.mediaContent.hasVideoContent &&
        responseInfo.adapterClassName == "com.google.ads.mediation.admob.AdMobAdapter"
    override fun resumeAfterRetention(): Boolean = pauseForRetention()
    override fun setCallbacks(callbacks: NativeCallbacks?) { this.listener = callbacks }

    init {
        nativeAd.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() {
                listener?.impression(adSource, responseId)
            }

            override fun onAdClicked() {
                listener?.clicked(adSource, responseId)
            }

            override fun onAdSwipeGestureClicked() {
                listener?.clicked(adSource, responseId)
            }

            override fun onAdPaid(value: AdValue) {
                val valueMicros = value.valueMicros
                val currencyCode = value.currencyCode
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                    .orEmpty()

                val sourceInfo = responseInfo.loadedAdSourceResponseInfo
                listener?.paid(
                    NativeRevenue(
                        valueMicros = valueMicros,
                        currencyCode = currencyCode,
                        adSource = sourceInfo?.name,
                        responseId = responseInfo.responseId,
                        precisionType = value.precisionType.name,
                        mediationAdapterClassName = sourceInfo?.adapterClassName
                            ?: responseInfo.adapterClassName,
                    ),
                )
            }

            override fun onAdShowedFullScreenContent() {
                listener?.overlayOpened()
            }

            override fun onAdDismissedFullScreenContent() {
                listener?.overlayClosed()
            }

            override fun onAdFailedToShowFullScreenContent(
                fullScreenContentError: FullScreenContentError,
            ) {
                // NativeCallbacks has no card failure or overlay-failure channel. The card stays
                // loaded; the owner only uses overlayOpened/overlayClosed for app-open gating.
            }
        }
    }

    override fun render(
        activity: Activity,
        binding: NativeLayoutBinding?,
        widthPx: Int,
    ): View {
        check(!destroyed.get()) { "native_ad_destroyed" }
        check(nativeAdView == null) { "native_ad_already_rendered" }
        require(widthPx > 0) { "native_invalid_width" }

        val binding = binding ?: throw IllegalArgumentException("native_binding_required")
        binding.validate()
        binding.bindAdMobText(assets)
        val adView = NativeAdView(activity)
        var localViewDestroyed = false

        try {
            adView.addView(
                binding.root,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            adView.headlineView = binding.headline
            adView.callToActionView = binding.callToAction
            adView.bodyView = binding.body
            adView.advertiserView = binding.advertiser

            val mediaView = binding.media?.let { media ->
                MediaView(activity).also { view ->
                    media.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }

            val icon = nativeAd.icon
            val iconView = binding.icon?.takeIf { icon != null }?.let { container ->
                val imageView = ImageView(activity)
                imageView.setImageDrawable(icon?.drawable)
                container.addView(
                    imageView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                imageView
            }
            binding.icon?.visibility = if (iconView == null) View.GONE else View.VISIBLE

            adView.iconView = iconView
            if (mediaView != null && nativeAd.mediaContent.hasVideoContent) {
                // Measure the populated tree before registration; XML dimensions alone are not enough.
                adView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                // A custom View's measurement can synchronously navigate away and destroy us.
                check(!destroyed.get()) { "native_ad_destroyed" }
                removeMediaSizeListener = mediaView.observeNativeVideoSize {
                    if (!destroyed.get() && nativeAdView === adView) {
                        // Do not draw a now-invalid video while failure delivery removes the card.
                        adView.visibility = View.INVISIBLE
                        listener?.failed("native_video_media_too_small")
                    }
                }
            }
            // Registering can synchronously re-enter the owner and destroy this handle.
            nativeAdView = adView
            adView.registerNativeAd(nativeAd, mediaView)
            if (destroyed.get()) {
                runCatching { adView.destroy() }
                localViewDestroyed = true
                throw IllegalStateException("native_ad_destroyed")
            }
            adView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return adView
        } catch (error: Throwable) {
            removeMediaSizeListener?.invoke()
            removeMediaSizeListener = null
            if (nativeAdView === adView) nativeAdView = null
            if (!localViewDestroyed) runCatching { adView.destroy() }
            throw error
        }
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        removeMediaSizeListener?.invoke()
        removeMediaSizeListener = null
        runCatching { nativeAd.adEventCallback = null }
        val view = nativeAdView
        nativeAdView = null
        runCatching { view?.destroy() }
        runCatching { nativeAd.destroy() }
    }
}

/** 业务工厂可先隐藏文字槽位；真实必需文案填入后必须恢复可见。 */
internal fun NativeLayoutBinding.bindAdMobText(assets: NativeAssets) {
    val title = assets.headline?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("native_missing_headline")
    val cta = assets.callToAction?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("native_missing_call_to_action")
    bindOptionalText(headline, title)
    bindOptionalText(callToAction, cta)
    bindOptionalText(body, assets.body)
    bindOptionalText(advertiser, assets.advertiser)
    // AdMob 无这些披露文案接口，不保留业务占位文字或伪造来源。
    bindOptionalText(adFrom, null)
    bindOptionalText(domain, null)
    bindOptionalText(warning, null)
}

private fun bindOptionalText(view: TextView?, value: String?) {
    if (view == null) return
    view.text = value.orEmpty()
    view.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
}

/** 主图存在才确认图片；视频标记优先于可能同时返回的主图。 */
internal fun adMobNativeMediaType(hasVideo: Boolean, hasMainImage: Boolean): NativeMediaType = when {
    hasVideo -> NativeMediaType.VIDEO
    hasMainImage -> NativeMediaType.IMAGE
    else -> NativeMediaType.UNKNOWN
}

private const val NATIVE_AD_MAX_AGE_MILLIS = 3_600_000L
