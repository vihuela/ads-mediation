package com.cashcraft.ads.mediation.internal.nativeads

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.NativeAssets
import com.cashcraft.ads.mediation.NativeMediaType
import com.cashcraft.ads.mediation.nativeMediaAspectRatio
import com.cashcraft.ads.mediation.NativeLayoutBinding
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.AdPlatform
import com.thinkup.core.api.AdError
import com.thinkup.core.api.TUAdConst
import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.TUAdRevenueListener
import com.thinkup.nativead.api.NativeAd
import com.thinkup.nativead.api.TUNative
import com.thinkup.nativead.api.TUNativeAdView
import com.thinkup.nativead.api.TUNativeDislikeListener
import com.thinkup.nativead.api.TUNativeEventListener
import com.thinkup.nativead.api.TUNativeNetworkListener
import com.thinkup.nativead.api.TUNativeImageView
import com.thinkup.nativead.api.TUNativePrepareInfo
import com.thinkup.nativead.unitgroup.api.CustomNativeAd
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val NATIVE_CONSENT_GENERATION = "cashcraft_native_consent_generation"

internal class TopOnNativeProvider : NativeProvider {
    override fun load(
        activity: Activity,
        request: ResolvedNativeRequest,
        widthPx: Int,
        callbacks: NativeCallbacks,
    ): NativeLoad {
        require(widthPx > 0) { "native_width_invalid" }

        val requestGeneration = NativeAdCache.generation
        val completion = AtomicBoolean(false)
        var refreshed = false
        var refresh: NativeLoad? = null
        val loader = try {
            TUNative(activity.applicationContext, request.adUnitId, null)
        } catch (failure: Throwable) {
            NativeMainThread.run { callbacks.failed("native_load_exception") }
            return NativeLoad { }
        }
        fun finish(): Boolean {
            if (!completion.compareAndSet(false, true)) return false
            refresh?.cancel()
            refresh = null
            runCatching { loader.setAdListener(null) }
                .onFailure { Ads.nativeLog("TopOn", warning = true, error = it) { "移除加载监听失败" } }
            return true
        }
        val networkListener = object : TUNativeNetworkListener {
            override fun onNativeAdLoaded() {
                NativeMainThread.run {
                    if (completion.get()) return@run
                    val quotedGeneration = try {
                        check(requestGeneration == NativeAdCache.generation) { "native_consent_changed" }
                        clearStaleSdkAds(loader, requestGeneration)
                        loader.checkAdStatus().getTUTopAdInfo()?.localExtra?.get(NATIVE_CONSENT_GENERATION)
                    } catch (failure: Throwable) {
                        if (finish()) callbacks.failed(failure.message ?: "native_load_setup_failed")
                        return@run
                    }
                    if (quotedGeneration != requestGeneration) {
                        if (refresh != null) return@run
                        if (refreshed) {
                            if (finish()) callbacks.failed("native_consent_generation_mismatch")
                            return@run
                        }
                        // 清除旧库存后SDK仍可能回调旧成功；退出回调栈后只刷新一次，不递归消耗队列。
                        refreshed = true
                        refresh = NativeMainThread.post {
                            refresh = null
                            if (!completion.get()) {
                                try {
                                    check(requestGeneration == NativeAdCache.generation) { "native_consent_changed" }
                                    clearStaleSdkAds(loader, requestGeneration)
                                    loader.makeAdRequest()
                                } catch (failure: Throwable) {
                                    if (finish()) callbacks.failed(failure.message ?: "native_load_exception")
                                }
                            }
                        }
                        return@run
                    }
                    // Claim before getNativeAd: duplicate/late callbacks must not consume the cache.
                    if (!finish()) return@run
                    var acquired: NativeAd? = null
                    var initialized: TopOnNativeAd? = null
                    val handle = try {
                        check(requestGeneration == NativeAdCache.generation) { "native_consent_changed" }
                        val ad = loader.nativeAd
                            ?: throw IllegalStateException("native_ad_missing_after_load")
                        acquired = ad
                        // 同placement共享缓存可能返回旧请求对象，不能把本次回调当成广告的许可代次。
                        check(requestGeneration == NativeAdCache.generation &&
                            ad.adInfo?.localExtra?.get(NATIVE_CONSENT_GENERATION) == requestGeneration) {
                            "native_consent_generation_mismatch"
                        }
                        TopOnNativeAd(ad, request, widthPx, callbacks).also {
                            initialized = it
                            it.installListeners()
                        }
                    } catch (failure: Throwable) {
                        initialized?.destroy() ?: acquired?.let(::releaseTopOnAd)
                        callbacks.failed(failure.message ?: "native_load_setup_failed")
                        return@run
                    }
                    try {
                        callbacks.loaded(handle)
                    } catch (failure: Throwable) {
                        handle.destroy()
                        throw failure
                    }
                }
            }

            override fun onNativeAdLoadFail(error: AdError) {
                NativeMainThread.run {
                    if (!finish()) return@run
                    val reason = if (
                        error.code.contains("no_fill", ignoreCase = true) ||
                        error.desc.contains("no fill", ignoreCase = true)
                    ) "no_fill" else "native_load_failed"
                    callbacks.failed(reason, error.code)
                }
            }
        }
        try {
            loader.setAdListener(networkListener)
            val extra = mutableMapOf<String, Any>(
                TUAdConst.KEY.AD_CHOICES_PLACEMENT to TUAdConst.AD_CHOICES_PLACEMENT_TOP_RIGHT,
                NATIVE_CONSENT_GENERATION to requestGeneration,
            )
            request.topOnTemplateAspectRatio?.let { ratio ->
                extra[TUAdConst.KEY.AD_WIDTH] = widthPx
                extra[TUAdConst.KEY.AD_HEIGHT] = templateHeight(widthPx, ratio)
            }
            loader.setLocalExtra(extra)
            clearStaleSdkAds(loader, requestGeneration)
            loader.makeAdRequest()
        } catch (failure: Throwable) {
            if (finish()) NativeMainThread.run { callbacks.failed("native_load_exception") }
        }
        return NativeLoad {
            if (completion.compareAndSet(false, true)) NativeMainThread.run {
                refresh?.cancel()
                refresh = null
                runCatching { loader.setAdListener(null) }
                    .onFailure { Ads.nativeLog("TopOn", warning = true, error = it) { "移除加载监听失败" } }
            }
        }
    }

    /** 与一次性过渡加载共用许可标记、句柄和素材绑定，不持有页面。 */
    fun inventory(context: android.content.Context, key: NativeInventoryKey): Inventory =
        Inventory(context.applicationContext, key)

    inner class Inventory(context: android.content.Context, private val key: NativeInventoryKey) {
        private val loader = TUNative(context, key.id, null)
        private val requestGeneration = NativeAdCache.generation
        private val sessionId = java.util.UUID.randomUUID().toString()
        private var closed = false
        private var token = 0L

        fun prepare(complete: (Boolean) -> Unit): NativeLoad {
            check(!closed) { "native_inventory_closed" }
            val generation = ++token
            val listener = object : TUNativeNetworkListener {
                override fun onNativeAdLoaded() {
                    NativeMainThread.post {
                        if (closed) clearOwnedCaches()
                        else if (token == generation) complete(available())
                    }
                }
                override fun onNativeAdLoadFail(error: AdError) {
                    NativeMainThread.post { if (!closed && token == generation) complete(false) }
                }
            }
            val extra = mutableMapOf<String, Any>(
                TUAdConst.KEY.AD_CHOICES_PLACEMENT to TUAdConst.AD_CHOICES_PLACEMENT_TOP_RIGHT,
                NATIVE_CONSENT_GENERATION to requestGeneration,
                "cashcraft_native_inventory_session" to sessionId,
            )
            key.templateRatio?.let { ratio ->
                val width = checkNotNull(key.widthPx)
                extra[TUAdConst.KEY.AD_WIDTH] = width
                extra[TUAdConst.KEY.AD_HEIGHT] = templateHeight(width, ratio)
            }
            try {
                loader.setLocalExtra(extra)
                loader.setAdListener(listener)
                clearStaleSdkAds(loader, requestGeneration)
                val previous = loader.checkValidAdCaches()?.filter {
                    val session = it?.localExtra?.get("cashcraft_native_inventory_session")
                    session is String && session != sessionId
                }
                if (!previous.isNullOrEmpty()) loader.clearCache(previous)
                loader.makeAdRequest()
            } catch (failure: Exception) {
                token++
                runCatching { loader.setAdListener(null) }
                throw failure
            }
            return NativeLoad {
                if (token == generation) {
                    token++
                    loader.setAdListener(null)
                }
            }
        }

        fun available(): Boolean {
            if (closed || requestGeneration != NativeAdCache.generation) return false
            clearStaleSdkAds(loader, requestGeneration)
            val extra = loader.checkAdStatus().getTUTopAdInfo()?.localExtra
            return extra?.get(NATIVE_CONSENT_GENERATION) == requestGeneration &&
                extra["cashcraft_native_inventory_session"] == sessionId
        }

        fun peekIdentity(): String? = if (available())
            loader.checkAdStatus().getTUTopAdInfo()?.requestId else null

        fun take(request: ResolvedNativeRequest, widthPx: Int, callbacks: NativeCallbacks): NativeAdHandle? {
            if (!available()) return null
            val ad = loader.nativeAd ?: return null
            var handle: TopOnNativeAd? = null
            return try {
                check(requestGeneration == NativeAdCache.generation &&
                    ad.adInfo?.localExtra?.get(NATIVE_CONSENT_GENERATION) == requestGeneration &&
                    ad.adInfo?.localExtra?.get("cashcraft_native_inventory_session") == sessionId) {
                    "native_consent_generation_mismatch"
                }
                TopOnNativeAd(ad, request, widthPx, callbacks).also {
                    handle = it
                    it.installListeners()
                }
            } catch (failure: Throwable) {
                handle?.destroy() ?: releaseTopOnAd(ad)
                throw failure
            }
        }

        fun close() {
            if (closed) return
            closed = true
            token++
            loader.setAdListener(null)
            clearOwnedCaches()
        }

        private fun clearOwnedCaches() {
            // SDK 请求不可取消；只清理当前会话明确标记的缓存，不消费外部共享对象。
            val owned = loader.checkValidAdCaches()?.filter {
                it?.localExtra?.get("cashcraft_native_inventory_session") == sessionId
            }
            if (!owned.isNullOrEmpty()) loader.clearCache(owned)
        }
    }

    private fun clearStaleSdkAds(loader: TUNative, generation: Long) {
        val caches = loader.checkValidAdCaches() ?: return
        var stale: ArrayList<TUAdInfo>? = null
        for (info in caches) {
            val loadedGeneration = info?.localExtra?.get(NATIVE_CONSENT_GENERATION)
            if (loadedGeneration is Long && loadedGeneration != generation) {
                if (stale == null) stale = ArrayList(caches.size)
                stale.add(info)
            }
        }
        // 只选择清理本库明确标记的旧对象；不取光共享缓存，也不声称取消SDK在途网络请求。
        stale?.let { loader.clearCache(it) }
    }

    private fun releaseTopOnAd(ad: NativeAd) {
        runCatching { ad.setNativeEventListener(null) }
        runCatching { ad.setDislikeCallbackListener(null) }
        runCatching { ad.setAdRevenueListener(null) }
        runCatching { ad.destory() }
    }

    private inner class TopOnNativeAd(
        private val ad: NativeAd,
        private val request: ResolvedNativeRequest,
        private val loadWidthPx: Int,
        private var listener: NativeCallbacks?,
    ) : NativeAdHandle {
        private val destroyed = AtomicBoolean(false)
        private var renderedContainer: TUNativeAdView? = null
        private var renderedBinding: NativeLayoutBinding? = null
        private var sdkCta: View? = null

        fun installListeners() {
            ad.setNativeEventListener(object : TUNativeEventListener {
                override fun onAdImpressed(view: TUNativeAdView, adInfo: TUAdInfo) {
                    NativeMainThread.run {
                        if (!destroyed.get()) listener?.impression(adInfo.networkName, adInfo.showId)
                    }
                }
                override fun onAdClicked(view: TUNativeAdView, adInfo: TUAdInfo) {
                    NativeMainThread.run {
                        if (!destroyed.get()) listener?.clicked(adInfo.networkName, adInfo.showId)
                    }
                }
                override fun onAdVideoStart(view: TUNativeAdView) = Unit
                override fun onAdVideoProgress(view: TUNativeAdView, progress: Int) = Unit
                override fun onAdVideoEnd(view: TUNativeAdView) = Unit
            })
            ad.setDislikeCallbackListener(object : TUNativeDislikeListener() {
                override fun onAdCloseButtonClick(view: TUNativeAdView, adInfo: TUAdInfo) {
                    NativeMainThread.run { if (!destroyed.get()) listener?.closed() }
                }
            })
            ad.setAdRevenueListener(object : TUAdRevenueListener {
                override fun onAdRevenuePaid(adInfo: TUAdInfo) {
                    NativeMainThread.run {
                        revenueOrNull(adInfo)?.let { listener?.paid(it) }
                    }
                }
            })
        }

        private val info = runCatching { ad.getAdInfo() }.getOrNull()
        override val platform: AdPlatform = AdPlatform.TOPON
        override val adSource: String? = info?.networkName
        override val responseId: String? = info?.requestId
        override val bidPriceUsd: Double? = runCatching {
            topOnNativeBidPrice(info?.getEcpm(TUAdConst.CURRENCY.USD))
        }.getOrNull()
        override val isTemplate: Boolean = ad.isNativeExpress()
        // 模板不读取自渲染素材；未知类型不借用封面尺寸冒充媒体比例。
        override val assets: NativeAssets = if (isTemplate) NativeAssets() else ad.getAdMaterial()?.let { material ->
            val mediaType = topOnNativeMediaType(material.getAdType())
            NativeAssets(
                headline = material.getTitle()?.takeIf(String::isNotBlank),
                body = material.getDescriptionText()?.takeIf(String::isNotBlank),
                callToAction = material.getCallToActionText()?.takeIf(String::isNotBlank),
                advertiser = material.getAdvertiserName()?.takeIf(String::isNotBlank),
                mediaType = mediaType,
                mediaAspectRatio = when (mediaType) {
                    NativeMediaType.VIDEO -> nativeMediaAspectRatio(material.getVideoWidth(), material.getVideoHeight())
                    NativeMediaType.IMAGE -> nativeMediaAspectRatio(material.getMainImageWidth(), material.getMainImageHeight())
                    NativeMediaType.UNKNOWN -> null
                },
                adFrom = material.getAdFrom()?.takeIf(String::isNotBlank),
                domain = material.getDomain()?.takeIf(String::isNotBlank),
                warning = material.getWarning()?.takeIf(String::isNotBlank),
            )
        } ?: NativeAssets()
        override var expiresAtMillis: Long? = null
            private set

        override fun bindDeadline(deadline: Long) {
            val current = expiresAtMillis
            if (current == null || deadline < current) {
                expiresAtMillis = deadline
            }
        }
        override val isValid: Boolean get() = !destroyed.get() && runCatching { ad.isValid }.getOrDefault(false)
        override val canCache: Boolean get() = isValid && renderedContainer == null
        override fun setCallbacks(callbacks: NativeCallbacks?) { this.listener = callbacks }

        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View {
            require(widthPx > 0) { "native_width_invalid" }
            check(isValid) { "native_ad_expired" }
            check(renderedContainer == null) { "native_ad_already_rendered" }
            check(expiresAtMillis?.let { SystemClock.elapsedRealtime() >= it } != true) { "native_ad_expired" }

            val container = try {
                TUNativeAdView(activity)
            } catch (failure: Throwable) {
                destroyInternal()
                throw failure
            }
            var actualBinding: NativeLayoutBinding? = null
            try {
                renderedContainer = container
                check(!destroyed.get()) { "native_ad_destroyed" }
                if (isTemplate) {
                    require(binding == null) { "native_template_binding_unexpected" }
                    val heightPx = topOnNativeTemplateHeight(widthPx, loadWidthPx, request.topOnTemplateAspectRatio)
                    container.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
                    ad.renderAdContainer(container, null)
                    check(!destroyed.get()) { "native_ad_destroyed" }
                    ad.prepare(container, null)
                    check(!destroyed.get()) { "native_ad_destroyed" }
                } else {
                    actualBinding = binding
                        ?: throw IllegalArgumentException("native_self_render_binding_required")
                    actualBinding.validate()
                    renderedBinding = actualBinding
                    val prepareInfo = TUNativePrepareInfo()
                    bindMaterial(activity, ad, assets, actualBinding, prepareInfo) { sdkCta = it }
                    container.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    ad.renderAdContainer(container, actualBinding.root)
                    check(!destroyed.get()) { "native_ad_destroyed" }
                    ad.prepare(container, prepareInfo)
                    check(!destroyed.get()) { "native_ad_destroyed" }
                }
                return container
            } catch (failure: Throwable) {
                destroyInternal()
                throw failure
            }
        }

        override fun destroy() {
            NativeMainThread.run { destroyInternal() }
        }

        private fun destroyInternal() {
            if (!destroyed.compareAndSet(false, true)) return
            val container = renderedContainer
            renderedContainer = null
            runCatching { (container?.parent as? ViewGroup)?.removeView(container) }
            runCatching { container?.removeAllViews() }
            runCatching { renderedBinding?.clearTopOnChildren(sdkCta) }
            renderedBinding = null
            sdkCta = null
            releaseTopOnAd(ad)
        }
    }

    private fun bindMaterial(
        activity: Activity,
        ad: NativeAd,
        assets: NativeAssets,
        binding: NativeLayoutBinding,
        prepareInfo: TUNativePrepareInfo,
        rememberSdkCta: (View) -> Unit,
    ) {
        val material = ad.getAdMaterial()
            ?: throw IllegalArgumentException("native_material_missing")
        val clickViews = ArrayList<View>()
        val description = assets.body
        val sdkIcon = material.getAdIconView()
        val iconUrl = material.getIconImageUrl()?.takeIf(String::isNotBlank)
        val adFrom = assets.adFrom
        binding.validateTopOnMaterial(description, sdkIcon, iconUrl, adFrom,
            assets.domain, assets.warning)
        prepareInfo.setParentView(binding.root)
        binding.headline.bindTopOnText(assets.headline, prepareInfo::setTitleView, clickViews)
        binding.body?.bindTopOnText(description, prepareInfo::setDescView, clickViews)
        binding.callToAction.bindTopOnText(assets.callToAction, prepareInfo::setCtaView, clickViews)

        binding.media?.let { media ->
            val sdkMedia = material.getAdMediaView(media)
            requireTopOnSdkMedia(material.getAdType(), sdkMedia)
            val imageUrls = material.getImageUrlList()?.filterIsInstance<String>()?.filter(String::isNotBlank).orEmpty()
            val mainUrl = material.getMainImageUrl()?.takeIf(String::isNotBlank)
            val mediaView = sdkMedia ?: when {
                imageUrls.size > 1 -> LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    imageUrls.forEach { url ->
                        addView(nativeImage(activity, url), LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    }
                }
                mainUrl != null -> nativeImage(activity, mainUrl)
                imageUrls.size == 1 -> nativeImage(activity, imageUrls.single())
                else -> null
            }
            if (mediaView == null) {
                throw IllegalArgumentException("native_media_missing")
            } else {
                require(mediaView !== media) { "native_media_invalid" }
                val width = if (sdkMedia != null && material.getAdType() == CustomNativeAd.NativeAdConst.VIDEO_TYPE)
                    material.getVideoWidth() else material.getMainImageWidth()
                val height = if (sdkMedia != null && material.getAdType() == CustomNativeAd.NativeAdConst.VIDEO_TYPE)
                    material.getVideoHeight() else material.getMainImageHeight()
                (media as? DefaultNativeMediaView)?.setMediaDimensions(width, height,
                    if (sdkMedia == null && imageUrls.size > 1) imageUrls.size else 1)
                media.attachTopOnView(mediaView)
                // SDK media registers itself; only actual host images bind as the main image.
                if (sdkMedia == null) prepareInfo.setMainImageView(mediaView)
                clickViews += mediaView
            }
        }
        binding.icon?.let { icon ->
            val iconView = sdkIcon ?: iconUrl?.let { nativeImage(activity, it) }
            icon.visibility = if (iconView == null) View.GONE else View.VISIBLE
            iconView?.let {
                icon.attachTopOnView(it)
                prepareInfo.setIconView(it)
                clickViews += it
            }
        }
        val logo = material.getAdLogoView()
            ?: material.getAdChoiceIconUrl()?.takeIf(String::isNotBlank)?.let { nativeImage(activity, it) }
            ?: material.getAdLogo()?.let { bitmap ->
                ImageView(activity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER }
            }
        logo?.let {
            binding.adChoices.attachTopOnView(it)
            prepareInfo.setAdLogoView(it)
        }
        prepareInfo.setChoiceViewLayoutParams(FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))
        binding.bindTopOnAttribution(assets, prepareInfo::setAdFromView)
        binding.domain?.bindTopOnText(assets.domain, prepareInfo::setDomainView, clickViews)
        binding.warning?.bindTopOnText(assets.warning, prepareInfo::setWarningView, clickViews)
        binding.close?.let(prepareInfo::setCloseView)
        binding.advertiserInfo?.let { entry ->
            val info = material.getAdvertiserInfoOperate()
            entry.visibility = if (info == null) View.GONE else View.VISIBLE
            entry.setOnClickListener(if (info == null) null else View.OnClickListener {
                info.showAdvertiserInfoDialog(entry, true)
            })
        }
        // Use the SDK button in the caller's CTA position without a Huawei dependency.
        val sdkButton = material.getCallToActionButton()
        if (sdkButton != null && sdkButton !== binding.callToAction) {
            val parent = binding.callToAction.parent as ViewGroup
            require(sdkButton !== parent && sdkButton !== binding.root) { "native_cta_invalid" }
            rememberSdkCta(sdkButton)
            sdkButton.detachFromParent()
            val index = parent.indexOfChild(binding.callToAction)
            val params = binding.callToAction.layoutParams
            if (binding.callToAction.id != View.NO_ID) sdkButton.id = binding.callToAction.id
            parent.removeView(binding.callToAction)
            parent.addView(sdkButton, index, params)
            sdkButton.visibility = View.VISIBLE
            clickViews.remove(binding.callToAction)
            prepareInfo.setCtaView(sdkButton)
            clickViews += sdkButton
        }
        prepareInfo.setClickViewList(clickViews)
    }

    private fun nativeImage(activity: Activity, url: String) =
        TUNativeImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImage(url)
        }

    private fun View.detachFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun NativeLayoutBinding.clearTopOnChildren(sdkCta: View?) {
        listOfNotNull(media, icon, adChoices).forEach { runCatching { it.removeAllViews() } }
        runCatching { sdkCta?.detachFromParent() }
        advertiserInfo?.setOnClickListener(null)
    }

    private fun revenueOrNull(info: TUAdInfo): NativeRevenue? {
        val value = info.getPublisherRevenue()
        if (value == null || !value.isFinite()) {
            Ads.nativeLog("TopOn", warning = true) { "忽略收益：金额缺失或非有限值" }
            return null
        }
        if (value < 0.0) {
            Ads.nativeLog("TopOn", warning = true) { "忽略收益：金额为负数" }
            return null
        }
        val currency = info.getCurrency()
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)
            ?: run {
                Ads.nativeLog("TopOn", warning = true) { "忽略收益：缺少币种" }
                return null
            }
        val valueMicros = runCatching {
            BigDecimal.valueOf(value)
                .movePointRight(6)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrElse {
            Ads.nativeLog("TopOn", warning = true) { "忽略收益：金额超出微单位范围" }
            return null
        }
        if (valueMicros < 0L) {
            Ads.nativeLog("TopOn", warning = true) { "忽略收益：微单位金额为负数" }
            return null
        }
        return NativeRevenue(
            valueMicros = valueMicros,
            currencyCode = currency,
            adSource = info.networkName,
            responseId = info.showId,
            precisionType = info.ecpmPrecision,
            topOnAdInfo = info,
        )
    }
}

/** 模板只使用请求中已确认的比例，显示宽度必须与加载宽度一致。 */
internal fun topOnNativeTemplateHeight(widthPx: Int, loadWidthPx: Int, aspectRatio: Float?): Int {
    val ratio = aspectRatio ?: throw IllegalArgumentException("native_template_size_unknown")
    require(widthPx == loadWidthPx) { "native_template_size_changed" }
    return templateHeight(widthPx, ratio)
}

private fun templateHeight(widthPx: Int, aspectRatio: Float): Int =
    (widthPx / aspectRatio).roundToInt().coerceAtLeast(1)

/** 已知视频必须交给 SDK 媒体 View，不能用封面或图片列表伪装成功。 */
internal fun requireTopOnSdkMedia(adType: String?, sdkMedia: View?) {
    require(adType != CustomNativeAd.NativeAdConst.VIDEO_TYPE || sdkMedia != null) {
        "native_video_media_missing"
    }
}

/** 旧 advertiser 槽位优先显示来源；没有来源时保留真实广告主文案。 */
internal fun NativeLayoutBinding.bindTopOnAttribution(assets: NativeAssets, bindAdFrom: (View) -> Unit) {
    (adFrom ?: advertiser)?.bindTopOnText(assets.adFrom, bindAdFrom)
    if (adFrom != null || assets.adFrom.isNullOrBlank()) {
        advertiser?.bindTopOnText(assets.advertiser, {})
    }
}

/** TopOn requires display of returned assets, including URL-only icons. */
internal fun NativeLayoutBinding.validateTopOnMaterial(
    description: String?, sdkIcon: View?, iconUrl: String? = null,
    adFromText: String? = null, domainText: String? = null, warningText: String? = null,
) {
    require(description.isNullOrBlank() || body != null) { "native_body_required" }
    require((sdkIcon == null && iconUrl.isNullOrBlank()) || icon != null) { "native_icon_required" }
    require(adFromText.isNullOrBlank() || adFrom != null || advertiser != null) { "native_ad_from_required" }
    require(domainText.isNullOrBlank() || domain != null) { "native_domain_required" }
    require(warningText.isNullOrBlank() || warning != null) { "native_warning_required" }
}

internal fun TextView.bindTopOnText(value: String?, bind: (View) -> Unit, clicks: MutableList<View>? = null) {
    text = value.orEmpty()
    visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    if (!value.isNullOrBlank()) {
        bind(this)
        clicks?.add(this)
    }
}

/** Detach SDK-owned assets before reuse, filling the host-owned asset container. */
internal fun ViewGroup.attachTopOnView(view: View) {
    var ancestor: View? = this
    while (ancestor != null) {
        require(view !== ancestor) { "native_asset_invalid" }
        ancestor = ancestor.parent as? View
    }
    (view.parent as? ViewGroup)?.removeView(view)
    removeAllViews()
    addView(view, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    visibility = View.VISIBLE
}

/** 只映射 SDK 明确返回的创意类型；其他值和缺失值均保持未知。 */
internal fun topOnNativeMediaType(adType: String?): NativeMediaType = when (adType) {
    CustomNativeAd.NativeAdConst.IMAGE_TYPE -> NativeMediaType.IMAGE
    CustomNativeAd.NativeAdConst.VIDEO_TYPE -> NativeMediaType.VIDEO
    else -> NativeMediaType.UNKNOWN
}

/** 展示前使用美元 eCPM，换算为美元/次展示；非法金额视为未知，真实零价保留。 */
internal fun topOnNativeBidPrice(ecpmUsd: Double?): Double? =
    ecpmUsd?.takeIf { it.isFinite() && it >= 0.0 }?.div(1_000.0)
