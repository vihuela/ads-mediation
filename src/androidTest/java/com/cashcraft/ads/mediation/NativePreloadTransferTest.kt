package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.cashcraft.ads.mediation.admob.AdMobNextGenBidPrice
import com.cashcraft.ads.mediation.internal.nativeads.createDefaultNativeLayout
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoadResult
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import java.util.concurrent.atomic.AtomicInteger

/**
 * 独立进程运行的 GMA 1.2.1 能力探针，只请求官方测试广告且不点击广告。
 * 不调用旧探针的私有字段读价或 shape dump；价格仅走已有 fromNative。
 * 这里直接操作 SDK，不代表生产缓存、保留策略、Compose 或 R8 已通过验收。
 */
@Suppress("DEPRECATION")
class NativePreloadTransferTest : InstrumentationTestCase() {
    private val owned = mutableListOf<NativeAd>()
    private val views = mutableListOf<NativeAdView>()
    private val activities = mutableListOf<Activity>()
    private val preloads = mutableListOf<String>()
    private val preloaded = AtomicInteger()
    private val loadFailures = AtomicInteger()
    private var unknownPrices = 0
    private var zeroPrices = 0
    private var positivePrices = 0

    override fun setUp() {
        super.setUp()
        onMain {
            Ads.initialize(instrumentation.targetContext.applicationContext as Application,
                AdsConfig(AdMobProviderConfig(AdMobIds.TEST),
                    umpConsent = UmpConsentConfig(enabled = false), autoShowAppOpen = false))
        }
        await("SDK 初始化") { Ads.nativeAvailability(AdPlatform.ADMOB).ready }
        onMain {
            val version = MobileAds.getVersion()
            assertEquals("此探针只覆盖锁定的 GMA 1.2.1", "1.2.1",
                "${version.majorVersion}.${version.minorVersion}.${version.microVersion}")
            // 沿用旧探针的隔离前提：释放本测试进程初始化产生的全屏预加载库存。
            AppOpenAdPreloader.destroyAll()
            InterstitialAdPreloader.destroyAll()
            RewardedAdPreloader.destroyAll()
            AdMobNextGenBidPrice.initialize(instrumentation.targetContext)
        }
    }

    fun testEmptyPollDestroyAndControlledQueueChangeInvalidateOldIdentity() {
        val id = "native-public-invalidation-probe"
        onMain {
            preloads += id
            NativeAdPreloader.destroy(id)
            assertEmpty(id)
        }
        start(id, 2)
        await("初始库存两条") { NativeAdPreloader.getNumAdsAvailable(id) == 2 }
        onMain {
            val oldQuoteIdentity = peekIdentity(id)
            assertEquals(oldQuoteIdentity, peekIdentity(id))
            assertEquals("公开查看不得消费库存", 2, NativeAdPreloader.getNumAdsAvailable(id))
            // 公开 poll 模拟另一消费者改变队首，不修改 SDK 私有队列。
            val consumedByOther = take(id)
            assertEquals(oldQuoteIdentity, identity(consumedByOther))
            val newQuoteIdentity = peekIdentity(id)
            assertFalse("已消费身份不能继续作为队首快照", oldQuoteIdentity == newQuoteIdentity)
            val actual = take(id)
            assertEquals(newQuoteIdentity, identity(actual))
            assertFalse("旧快照不能套用到新对象", oldQuoteIdentity == identity(actual))
            assertNotSame(consumedByOther, actual)
            recordActualPrice(consumedByOther)
            recordActualPrice(actual)
        }
        // 不再次 start/load；只有 SDK 自己补齐两次消费后的库存。
        await("受控消费后自动补货") {
            NativeAdPreloader.getNumAdsAvailable(id) == 2 && preloaded.get() >= 4
        }
        onMain {
            assertTrue(NativeAdPreloader.destroy(id))
            assertEmpty(id)
        }
        // 自动补货中的迟到回调不能让已关闭的公开库存重新可消费。
        SystemClock.sleep(2_000)
        onMain { assertEmpty(id) }
        start(id, 1)
        await("同 key 关闭后重新建立库存") { NativeAdPreloader.getNumAdsAvailable(id) == 1 }
        onMain {
            val oldQuoteIdentity = peekIdentity(id)
            assertTrue(NativeAdPreloader.destroy(id))
            assertEmpty(id)
            // 此时取空，不沿用旧身份或旧价格；公共 API 不提供队首报价。
            assertFalse(oldQuoteIdentity == NativeAdPreloader.peekAdResponseInfo(id)?.responseId)
        }
        Log.i(TAG, "公开库存检查完成：取空、关闭、受控消费均使旧身份失效；队首价格变化=未验证")
    }

    fun testPreloadedAdSurvivesActivityAAndImpressesInActivityB() {
        val first = launch()
        val id = "native-public-transfer-probe"
        start(id, 1)
        await("A 页预加载完成") { NativeAdPreloader.getNumAdsAvailable(id) == 1 }
        val originalIdentity = onMain { peekIdentity(id) }
        onMain { first.finish() }
        await("A 页已销毁") { first.isDestroyed }
        val second = launch()
        val events = Events()
        val rendered = onMain {
            assertEquals("A 销毁后未消费对象身份应保留", originalIdentity, peekIdentity(id))
            val ad = take(id)
            assertEquals(originalIdentity, identity(ad))
            recordActualPrice(ad)
            render(second, ad, events).also { mount(second, it.view) }
        }
        await("B 页平台 View 实际可见") { rendered.view.isShown && rendered.view.hasWindowFocus() }
        await("B 页 SDK 真实曝光") { events.impressions.get() > 0 }
        onMain {
            assertEquals(originalIdentity, identity(rendered.ad))
            assertSame(second, rendered.view.context)
            // registerNativeAd 路径的 MediaView getter 可为空；验证真实 SDK 子 View 已填入。
            assertTrue("SDK 未填充媒体子 View", rendered.media.childCount > 0)
            assertEquals("探针不得点击广告", 0, events.clicks.get())
        }
        snapshot("preload-transfer-b")
        Log.i(TAG, "预加载转移：A 已销毁，B 领取并注册一次，SDK 曝光=${events.impressions.get()}；视觉完整性=未验证")
    }

    fun testDisplayedGoogleImageHidesAndReattachesSameSdkObjectAndView() {
        val activity = launch()
        val id = "native-public-image-retention-probe"
        start(id, 1)
        await("图片探针预加载完成") { NativeAdPreloader.getNumAdsAvailable(id) == 1 }
        val events = Events()
        val rendered = onMain {
            val ad = take(id)
            val media = ad.mediaContent
            val mediaType = when {
                media.hasVideoContent -> "视频"
                media.mainImage != null -> "图片"
                else -> "未知"
            }
            Log.i(TAG, "媒体类型=$mediaType；来源=${ad.getResponseInfo().loadedAdSourceResponseInfo?.name}；适配器=${ad.getResponseInfo().adapterClassName}；视频恢复=未验证")
            // 无视频不等于有图片：主图必须由公开素材给出，禁止用时长猜测。
            assertEquals("非图片填充：图片保留未验证，不计通过", "图片", mediaType)
            recordActualPrice(ad)
            render(activity, ad, events).also { mount(activity, it.view) }
        }
        await("图片平台 View 实际可见") { rendered.view.isShown && rendered.view.hasWindowFocus() }
        await("首次 SDK 真实曝光") { events.impressions.get() > 0 }
        snapshot("preload-image-before")
        val originalIdentity = onMain { identity(rendered.ad) }
        val originalMediaParent = onMain { rendered.media.parent }
        // 留出短暂结算窗口，再记录隐藏前基线；不是长期静默证明。
        SystemClock.sleep(2_000)
        val beforeHide = events.impressions.get()
        onMain { rendered.view.visibility = View.GONE }
        SystemClock.sleep(2_000)
        onMain { assertFalse(rendered.view.isShown) }
        val afterHide = events.impressions.get()
        Log.i(TAG, "图片隐藏两秒：曝光增量=${afterHide - beforeHide}；迟到回调与长期静默需另验")
        onMain {
            (rendered.view.parent as ViewGroup).removeView(rendered.view)
            assertFalse(rendered.view.isAttachedToWindow)
        }
        SystemClock.sleep(2_000)
        val beforeReattach = events.impressions.get()
        val callbacksBefore = preloaded.get()
        onMain {
            // 只换同 Activity 的外层宿主，不重新 poll、render 或 registerNativeAd。
            rendered.view.visibility = View.VISIBLE
            val newHost = mount(activity, rendered.view)
            assertSame(rendered.view, newHost.getChildAt(0))
        }
        await("原平台 View 重新附着并可见") {
            rendered.view.isAttachedToWindow && rendered.view.isShown && rendered.view.hasWindowFocus()
        }
        SystemClock.sleep(5_000)
        onMain {
            assertEquals(originalIdentity, identity(rendered.ad))
            assertSame(originalMediaParent, rendered.media.parent)
            assertSame(rendered.media, (rendered.media.parent as ViewGroup).getChildAt(0))
            // registerNativeAd 路径的 MediaView getter 可为空；验证真实 SDK 子 View 已填入。
            assertTrue("SDK 未填充媒体子 View", rendered.media.childCount > 0)
            assertSame(activity, rendered.view.context)
            assertFalse(rendered.ad.mediaContent.hasVideoContent)
            assertNotNull(rendered.ad.mediaContent.mainImage)
            assertEquals("探针不得点击广告", 0, events.clicks.get())
        }
        snapshot("preload-image-after")
        // 原始 SDK 回调全部计数，不预先去重，不把重复曝光或补货归因于恢复。
        Log.i(TAG, "图片同对象及平台 View 重新附着：注册调用=1，恢复取货=0；" +
            "曝光总数=${events.impressions.get()}，恢复后曝光增量=${events.impressions.get() - beforeReattach}，" +
            "原始收益回调=${events.paid.get()}，同期补货回调增量=${preloaded.get() - callbacksBefore}；" +
            "观察窗口五秒，媒体视觉及音视频能力=未验证")
    }

    fun testManagedInventorySharesDemandAndUsesOriginalSessionDeadline() {
        val first = launch()
        val request = ResolvedNativeRequest(AdPlatform.ADMOB, TEST_NATIVE, "managed-a")
        val secondRequest = request.copy(position = "managed-b")
        var firstDemand: AutoCloseable? = null
        var secondDemand: AutoCloseable? = null
        var handle: com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle? = null
        val impressions = AtomicInteger()
        val callbacks = object : com.cashcraft.ads.mediation.internal.nativeads.NativeCallbacks {
            override fun loaded(ad: com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle) = Unit
            override fun failed(reason: String, errorCode: String?) = fail(reason)
            override fun impression(adSource: String?, responseId: String?) { impressions.incrementAndGet() }
            override fun clicked(adSource: String?, responseId: String?) = Unit
            override fun closed() = Unit
            override fun overlayOpened() = Unit
            override fun overlayClosed() = Unit
            override fun paid(revenue: com.cashcraft.ads.mediation.internal.nativeads.NativeRevenue) = Unit
        }
        val cache = com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
        try {
            val started = SystemClock.elapsedRealtime()
            onMain {
                firstDemand = cache.acquirePreload(request)
                secondDemand = cache.acquirePreload(secondRequest)
            }
            await("真实受管理库存就绪") { cache.peekPreload(request) != null }
            val identity = onMain { cache.peekPreload(request) }
            // B 先进入前台再销毁 A，避免无 Activity 的空窗被正确识别为真实后台。
            val second = launch()
            onMain { firstDemand!!.close(); first.finish() }
            await("A 页销毁") { first.isDestroyed }
            onMain {
                assertEquals(identity, cache.peekPreload(secondRequest))
                handle = checkNotNull(cache.takePreload(secondRequest, callbacks))
                assertEquals(identity, handle!!.responseId)
                val end = checkNotNull(handle!!.expiresAtMillis)
                assertTrue("期限必须继承准备起点", end >= started + 3_600_000L &&
                    end <= started + 3_601_000L)
                val view = handle!!.render(second, createDefaultNativeLayout(second),
                    second.resources.displayMetrics.widthPixels)
                second.setContentView(FrameLayout(second).apply {
                    addView(view, FrameLayout.LayoutParams(-1, -2))
                })
            }
            await("B 页真实曝光") { impressions.get() > 0 }
            await("消费后 SDK 自动补货") {
                cache.peekPreload(secondRequest)?.let { it != identity } == true
            }
            val replenished = onMain { cache.peekPreload(secondRequest) }
            onMain { assertTrue(second.moveTaskToBack(true)) }
            await("受管理库存随真实后台关闭") {
                !com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground &&
                    cache.peekPreload(secondRequest) == null
            }
            launch()
            await("活跃需求回前台重建库存") {
                cache.peekPreload(secondRequest)?.let { it != replenished } == true
            }
            onMain {
                cache.closePreload(request)
                assertNull(cache.peekPreload(request))
                assertEquals("关闭未消费库存不销毁 B 展示对象", identity, handle!!.responseId)
                assertTrue(handle!!.isValid)
            }
            SystemClock.sleep(2_000)
            onMain { assertNull(cache.peekPreload(request)) }
        } finally {
            onMain {
                firstDemand?.close()
                secondDemand?.close()
                cache.closePreload(request)
                handle?.destroy()
            }
        }
    }

    fun testProductionInventoryDeliversDistinctObjectsAndCancelledPageStaysEmpty() {
        val activity = launch()
        val cache = com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
        val ads = mutableListOf<com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle>()
        val operations = mutableListOf<com.cashcraft.ads.mediation.internal.nativeads.NativeLoad>()
        var failures = 0
        var cancelledDeliveries = 0
        fun listener(cancelled: Boolean = false) = object : com.cashcraft.ads.mediation.internal.nativeads.NativeCallbacks {
            override fun loaded(ad: com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle) {
                if (cancelled) { cancelledDeliveries++; ad.destroy() } else ads.add(ad)
            }
            override fun failed(reason: String, errorCode: String?) { failures++ }
            override fun impression(adSource: String?, responseId: String?) = Unit
            override fun clicked(adSource: String?, responseId: String?) = Unit
            override fun closed() = Unit
            override fun overlayOpened() = Unit
            override fun overlayClosed() = Unit
            override fun paid(revenue: com.cashcraft.ads.mediation.internal.nativeads.NativeRevenue) = Unit
        }
        val request = ResolvedNativeRequest(AdPlatform.ADMOB, TEST_NATIVE, "production-a")
        try {
            onMain {
                cache.clear()
                cache.load(activity, request.copy(position = "cancelled"), 320, false, listener(true)).cancel()
                operations.add(cache.load(activity, request, 320, false, listener()))
                operations.add(cache.load(activity, request.copy(position = "production-b"), 320, false, listener()))
            }
            await("两页面从生产库存独占领取") { failures > 0 || ads.size == 2 }
            onMain {
                assertEquals(0, failures)
                assertEquals(0, cancelledDeliveries)
                assertEquals(2, ads.size)
                assertNotSame(ads[0], ads[1])
                assertFalse(ads[0].responseId == ads[1].responseId)
                assertEquals(ads[0].expiresAtMillis, ads[1].expiresAtMillis)
            }
            await("生产消费补货就绪") { cache.peekPreload(request) != null }
            onMain { operations.forEach { it.cancel() }; activity.moveTaskToBack(true) }
            await("无页需求后台清理生产库存") { cache.peekPreload(request) == null }
            onMain { assertEquals(0, cancelledDeliveries) }
        } finally {
            onMain {
                operations.forEach { it.cancel() }
                ads.forEach { it.destroy() }
                cache.clear()
            }
        }
    }

    private fun start(id: String, buffer: Int) = onMain {
        if (id !in preloads) preloads += id
        val request = NativeAdRequest.Builder(TEST_NATIVE, listOf(NativeAd.NativeAdType.NATIVE)).build()
        assertTrue("预加载未启动", NativeAdPreloader.start(id, PreloadConfiguration(request, buffer),
            object : PreloadCallback {
                override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                    preloaded.incrementAndGet()
                }
                override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                    loadFailures.incrementAndGet()
                    Log.i(TAG, "预加载失败：错误码=${adError.code}；本样本未验证")
                }
                override fun onAdsExhausted(preloadId: String) = Unit
            }))
    }

    private fun peekIdentity(id: String): String = checkNotNull(
        NativeAdPreloader.peekAdResponseInfo(id)?.responseId) { "公开队首身份缺失：未验证" }

    private fun identity(ad: NativeAd): String = checkNotNull(ad.getResponseInfo().responseId) {
        "实际对象公开身份缺失：未验证"
    }

    private fun take(id: String): NativeAd {
        val result = NativeAdPreloader.pollAd(id)
        assertTrue("有库存时取货为空：本次身份关联未验证", result is NativeAdLoadResult.NativeAdSuccess)
        return (result as NativeAdLoadResult.NativeAdSuccess).ad.also { owned += it }
    }

    private fun assertEmpty(id: String) {
        assertEquals(0, NativeAdPreloader.getNumAdsAvailable(id))
        assertNull("取空后不得保留旧公开身份", NativeAdPreloader.peekAdResponseInfo(id))
        val result = NativeAdPreloader.pollAd(id)
        // 锁定版的空结果不要求具体失败子类型，成功对象则必须回收并判失败。
        if (result is NativeAdLoadResult.NativeAdSuccess) owned += result.ad
        assertFalse("空库存不能领取成功", result is NativeAdLoadResult.NativeAdSuccess)
    }

    private fun recordActualPrice(ad: NativeAd) {
        val usd = AdMobNextGenBidPrice.fromNative(ad)
        if (usd == null) unknownPrices++ else {
            assertTrue("实际价格必须有限且非负", usd.isFinite() && usd >= 0)
            if (usd == 0.0) zeroPrices++ else positivePrices++
        }
        Log.i(TAG, "实际对象价格（USD/次）=${usd ?: "未知"}；非零队首报价关联=未验证")
    }

    private fun render(activity: Activity, ad: NativeAd, events: Events): Rendered {
        ad.adEventCallback = events
        val binding = createDefaultNativeLayout(activity)
        val view = NativeAdView(activity)
        views += view
        view.addView(binding.root, FrameLayout.LayoutParams(-1, -2))
        binding.headline.text = checkNotNull(ad.headline)
        binding.callToAction.text = checkNotNull(ad.callToAction)
        fun optional(text: TextView?, value: String?) {
            text?.text = value
            text?.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        optional(binding.body, ad.body)
        optional(binding.advertiser, ad.advertiser)
        view.headlineView = binding.headline
        view.callToActionView = binding.callToAction
        view.bodyView = binding.body
        view.advertiserView = binding.advertiser
        val media = MediaView(activity)
        checkNotNull(binding.media).addView(media, ViewGroup.LayoutParams(-1, -1))
        val icon = ad.icon
        if (icon != null) {
            val iconView = ImageView(activity).apply { setImageDrawable(icon.drawable) }
            checkNotNull(binding.icon).addView(iconView, ViewGroup.LayoutParams(-1, -1))
            view.iconView = iconView
        } else binding.icon?.visibility = View.GONE
        // 监听、真实素材和媒体槽位先于平台注册，曝光只认 SDK 回调。
        view.registerNativeAd(ad, media)
        return Rendered(ad, view, media)
    }

    private fun snapshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun mount(activity: Activity, view: NativeAdView): FrameLayout = FrameLayout(activity).apply {
        addView(view, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(this)
    }

    private fun launch(): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
            // 每次要求新实例，避免 NEW_TASK 复用栈顶导致 startActivitySync 无创建回调。
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        .also { activities += it }

    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            if (onMain(predicate)) return
            SystemClock.sleep(100)
        }
        fail("超时：$label；本样本未验证，预加载失败次数=${loadFailures.get()}")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun tearDown() {
        try {
            onMain {
                // 各项清理独立执行，首个异常在清理全部资源后重新抛出。
                var failure: Throwable? = null
                fun cleanup(block: () -> Unit) {
                    try { block() } catch (error: Throwable) {
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
                owned.forEach { ad -> cleanup { ad.adEventCallback = null } }
                views.forEach { view ->
                    cleanup { (view.parent as? ViewGroup)?.removeView(view) }
                    cleanup { view.destroy() }
                }
                owned.forEach { ad -> cleanup { ad.destroy() } }
                preloads.forEach { id -> cleanup { NativeAdPreloader.destroy(id) } }
                activities.forEach { activity -> cleanup { if (!activity.isDestroyed) activity.finish() } }
                Log.i(TAG, "实际价格样本：未知=$unknownPrices，零值=$zeroPrices，正值=$positivePrices；" +
                    "非零队首报价、R8、长期恢复和音视频=未验证")
                failure?.let { throw it }
            }
        } finally {
            super.tearDown()
        }
    }

    private data class Rendered(val ad: NativeAd, val view: NativeAdView, val media: MediaView)

    private class Events : NativeAdEventCallback {
        val impressions = AtomicInteger()
        val paid = AtomicInteger()
        val clicks = AtomicInteger()
        override fun onAdImpression() { impressions.incrementAndGet() }
        override fun onAdPaid(value: AdValue) { paid.incrementAndGet() }
        override fun onAdClicked() { clicks.incrementAndGet() }
        override fun onAdSwipeGestureClicked() { clicks.incrementAndGet() }
    }

    private companion object {
        const val TAG = "NativePublicProbe"
        const val TEST_NATIVE = "ca-app-pub-3940256099942544/2247696110"
    }
}
