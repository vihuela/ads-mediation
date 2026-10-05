package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.FrameLayout
import com.cashcraft.ads.mediation.internal.nativeads.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses official AdMob test inventory. Optional TopOn credentials are supplied only at execution. */
@Suppress("DEPRECATION")
class NativeCacheTransferTest : InstrumentationTestCase() {
    fun testCacheFirstClaimsSdkInventoryBeforeReturningWhileOtherPlatformIsPending() {
        onMain { initializeSdk(AdPlatform.ADMOB, android.os.Bundle()) }
        val activity = launch()
        val request = ResolvedNativeRequest(position = "cache_first", admobAdUnitId = AdMobIds.TEST.nativeId,
            topOnPlacementId = "pending-topon", preferCachedAds = true)
        val google = request.candidates().single { it.platform == AdPlatform.ADMOB }
        val result = Listener()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var demand: AutoCloseable? = null
        var auction: NativeLoad? = null
        try {
            waitUntil { Ads.nativeAvailability(AdPlatform.ADMOB).ready }
            onMain { demand = NativeAdCache.acquirePreload(google) }
            waitUntil { NativeAdCache.peekPreload(google) != null }
            onMain {
                val start = android.os.SystemClock.elapsedRealtime()
                // 真实 SDK 缓存，另一端确定保持未就绪；必须在 start 返回前交付。
                auction = NativeAuction(request, result,
                    availability = { if (it == AdPlatform.ADMOB) Ads.nativeAvailability(it) else NativeAvailability() },
                    startLoad = { candidate, listener -> NativeAdCache.load(activity, candidate,
                        activity.resources.displayMetrics.widthPixels, false, listener) },
                    subscribe = { AutoCloseable {} }, dispatch = NativeMainThread::run,
                    schedule = { action, delay -> handler.postDelayed(action, delay) },
                    unschedule = handler::removeCallbacks, clock = android.os.SystemClock::elapsedRealtime,
                ).also { it.start() }
                assertNull(result.failure)
                assertNotNull("Ready SDK inventory must not wait for the pending platform", result.ad)
                assertTrue(result.ad!!.isValid)
                android.util.Log.i("NativeCacheTransfer",
                    "cache_first sdk_inventory_delivered=true elapsed_ms=${android.os.SystemClock.elapsedRealtime() - start}")
            }
        } finally {
            onMain {
                auction?.cancel()
                result.ad?.destroy()
                demand?.close()
                NativeAdCache.clear()
                activity.finish()
            }
        }
    }

    fun testUnrenderedAdSurvivesLoadingActivityAndRendersInNewActivity() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val platform = if (args.getString("platform") == "topon") AdPlatform.TOPON else AdPlatform.ADMOB
        val ratio = args.getString("templateRatio")?.toFloatOrNull()
        val request = ResolvedNativeRequest(platform,
            if (platform == AdPlatform.ADMOB) "ca-app-pub-3940256099942544/2247696110"
            else requireNotNull(args.getString("nativePlacement")), "cache_transfer", ratio)
        onMain {
            NativeAdCache.clear()
            initializeSdk(platform, args)
        }
        var first: Activity? = launch()
        val width = first!!.resources.displayMetrics.widthPixels
        var second: Activity? = null
        var ad: NativeAdHandle? = null
        var view: View? = null
        var inventoryGeneration = 0L
        try {
            waitUntil { Ads.nativeAvailability(platform).ready }
            val original = Listener()
            onMain {
                inventoryGeneration = NativeAdCache.generation
                NativeAdCache.load(first!!, request, width, true, original)
            }
            assertTrue("SDK did not complete the load", original.loaded.await(45, TimeUnit.SECONDS))
            assertNull(original.failure)
            ad = checkNotNull(original.ad)
            onMain {
                assertTrue(ad!!.canCache)
                android.util.Log.i("NativeCacheTransfer", "platform=$platform stage=loaded source=${ad!!.adSource} template=${ad!!.isTemplate}")
                NativeAdCache.retain(request, width, ad!!, inventoryGeneration)
                first!!.finish()
            }
            waitUntil { first!!.isDestroyed }
            first = null
            second = launch()
            val rebound = Listener()
            onMain {
                NativeAdCache.load(second!!, request, width, true, rebound)
                assertSame("Cache miss: a new SDK object was requested", ad, rebound.ad)
                assertTrue(ad!!.isValid)
                android.util.Log.i("NativeCacheTransfer", "platform=$platform stage=same_object_after_activity_destroyed")
                check(!ad!!.isTemplate || ratio != null) { "TopOn template sample requires a confirmed ratio" }
                val binding = if (ad!!.isTemplate) null else createDefaultNativeLayout(second!!)
                view = ad!!.render(second!!, binding, width)
                assertSame(second, view!!.context)
                second!!.setContentView(FrameLayout(second!!).apply {
                    addView(view, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT))
                })
            }
            assertTrue("No SDK impression in the new Activity", rebound.impression.await(20, TimeUnit.SECONDS))
            assertEquals("Old listener must remain detached", 1L, original.impression.count)
            android.util.Log.i("NativeCacheTransfer", "platform=$platform stage=new_activity_impression old_listener_detached=true")
        } finally {
            onMain {
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                ad?.destroy()
                NativeAdCache.clear()
                first?.finish()
                second?.finish()
            }
        }
    }

    fun testManagedTopOnInventorySharesDemandAndReplenishesAfterTake() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        require(args.getString("platform") == "topon") { "此用例要求已确认的 TopOn 调试配置" }
        if (args.getString("toponTestDeviceId") == null) {
            val client = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val info = client.getMethod("getAdvertisingIdInfo", android.content.Context::class.java)
                .invoke(null, instrumentation.targetContext)
            args.putString("toponTestDeviceId", info.javaClass.getMethod("getId").invoke(info) as String)
        }
        val request = ResolvedNativeRequest(AdPlatform.TOPON,
            requireNotNull(args.getString("nativePlacement")), "managed-topon")
        onMain { initializeSdk(AdPlatform.TOPON, args) }
        val activity = launch()
        val width = activity.resources.displayMetrics.widthPixels
        var a: AutoCloseable? = null
        var b: AutoCloseable? = null
        var ad: NativeAdHandle? = null
        var view: View? = null
        try {
            waitUntil { Ads.nativeAvailability(AdPlatform.TOPON).ready }
            onMain {
                a = NativeAdCache.acquireTopOnInventory(activity, request, width)
                b = NativeAdCache.acquireTopOnInventory(activity, request.copy(position = "managed-other"), width)
            }
            waitUntil { NativeAdCache.peekTopOnInventory(request, width) != null }
            val rebound = Listener()
            onMain {
                a!!.close()
                ad = checkNotNull(NativeAdCache.takeTopOnInventory(request, width, rebound))
                assertFalse("本用例仅覆盖已确认自渲染调试广告", ad!!.isTemplate)
                view = ad!!.render(activity, createDefaultNativeLayout(activity), width)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(width, -2))
                })
            }
            assertTrue("新库存对象未获得真实曝光", rebound.impression.await(30, TimeUnit.SECONDS))
            waitUntil { NativeAdCache.peekTopOnInventory(request, width) != null }
            onMain { assertTrue(activity.moveTaskToBack(true)) }
            waitUntil {
                !com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground &&
                    NativeAdCache.peekTopOnInventory(request, width) == null
            }
            val returned = launch()
            try {
                waitUntil { NativeAdCache.peekTopOnInventory(request, width) != null }
            } finally {
                onMain { returned.finish() }
            }
            onMain {
                NativeAdCache.closeTopOnInventory(request, width)
                assertNull(NativeAdCache.peekTopOnInventory(request, width))
                assertTrue("关闭库存不释放页面展示对象", ad!!.isValid)
            }
        } finally {
            onMain {
                a?.close()
                b?.close()
                NativeAdCache.closeTopOnInventory(request, width)
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                ad?.destroy()
                activity.finish()
            }
        }
    }

    /** 清理许可代次后不能从TopOn共享SDK缓存领回旧广告；默认亦覆盖AdMob本层清理。 */
    fun testOldInventoryCannotBeDeliveredAfterConsentGenerationEnds() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val platform = if (args.getString("platform") == "topon") AdPlatform.TOPON else AdPlatform.ADMOB
        val request = ResolvedNativeRequest(platform,
            if (platform == AdPlatform.ADMOB) "ca-app-pub-3940256099942544/2247696110"
            else requireNotNull(args.getString("nativePlacement")), "consent_generation")
        onMain {
            NativeAdCache.clear()
            if (platform == AdPlatform.TOPON) requireNotNull(args.getString("toponTestDeviceId"))
            initializeSdk(platform, args)
        }
        val activity = launch()
        val width = activity.resources.displayMetrics.widthPixels
        var sdkSeed: com.thinkup.nativead.api.TUNative? = null
        val result = Listener()
        try {
            waitUntil { Ads.nativeAvailability(platform).ready }
            var oldResponse: String? = null
            if (platform == AdPlatform.TOPON) {
                val seeded = CountDownLatch(1)
                var seedFailure: String? = null
                onMain {
                    sdkSeed = com.thinkup.nativead.api.TUNative(activity.applicationContext, request.adUnitId,
                        object : com.thinkup.nativead.api.TUNativeNetworkListener {
                            override fun onNativeAdLoaded() { seeded.countDown() }
                            override fun onNativeAdLoadFail(error: com.thinkup.core.api.AdError) {
                                seedFailure = error.code
                                seeded.countDown()
                            }
                        }).also {
                        it.setLocalExtra(mapOf("cashcraft_native_consent_generation" to NativeAdCache.generation))
                        it.makeAdRequest()
                    }
                }
                assertTrue("SDK seed did not complete", seeded.await(45, TimeUnit.SECONDS))
                assertNull(seedFailure)
                onMain {
                    oldResponse = sdkSeed!!.checkAdStatus().getTUTopAdInfo()?.requestId
                    sdkSeed!!.setAdListener(null)
                }
            } else {
                val original = Listener()
                var generation = 0L
                onMain {
                    generation = NativeAdCache.generation
                    NativeAdCache.load(activity, request, width, true, original)
                }
                assertTrue("SDK seed did not complete", original.loaded.await(45, TimeUnit.SECONDS))
                assertNull(original.failure)
                onMain {
                    val ad = checkNotNull(original.ad)
                    oldResponse = ad.responseId
                    NativeAdCache.retain(request, width, ad, generation)
                }
            }
            assertNotNull("SDK seed identity missing", oldResponse)
            onMain {
                NativeAdCache.clear()
                NativeAdCache.load(activity, request, width, true, result)
            }
            assertTrue("Restored generation did not complete", result.loaded.await(45, TimeUnit.SECONDS))
            assertNull(result.failure)
            val ad = checkNotNull(result.ad)
            assertNotNull("SDK result identity missing", ad.responseId)
            assertFalse("SDK returned an ad from the invalidated consent generation", oldResponse == ad.responseId)
        } finally {
            onMain {
                result.ad?.destroy()
                sdkSeed?.setAdListener(null)
                sdkSeed?.checkValidAdCaches()?.let { sdkSeed?.clearCache(it) }
                NativeAdCache.clear()
                activity.finish()
            }
        }
    }

    private fun initializeSdk(platform: AdPlatform, args: android.os.Bundle) {
        if (platform == AdPlatform.TOPON) args.getString("toponTestDeviceId")?.let {
            com.thinkup.core.api.TUSDK.setDebuggerConfig(instrumentation.targetContext, it,
                com.thinkup.core.api.TUDebuggerConfig.Builder(50).build())
        }
        val provider = if (platform == AdPlatform.ADMOB) AdMobProviderConfig(AdMobIds.TEST)
            else TopOnProviderConfig(TopOnIds(
                requireNotNull(args.getString("applicationId")),
                requireNotNull(args.getString("applicationKey")),
                args.getString("appOpenPlacement") ?: "unused-open",
                args.getString("interstitialPlacement") ?: "unused-interstitial",
                args.getString("rewardedPlacement") ?: "unused-rewarded"))
        Ads.initialize(instrumentation.targetContext.applicationContext as Application,
            AdsConfig(provider, umpConsent = UmpConsentConfig(enabled = false), autoShowAppOpen = false))
    }

    private fun launch(): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun waitUntil(predicate: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for SDK/Activity state")
    }
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private class Listener : NativeCallbacks {
        val loaded = CountDownLatch(1)
        val impression = CountDownLatch(1)
        var ad: NativeAdHandle? = null
        var failure: String? = null
        override fun loaded(ad: NativeAdHandle) { this.ad = ad; loaded.countDown() }
        override fun failed(reason: String, errorCode: String?) { failure = reason; loaded.countDown() }
        override fun impression(adSource: String?, responseId: String?, revenue: NativeRevenue?) { impression.countDown() }
        override fun clicked(adSource: String?, responseId: String?) = Unit
        override fun closed() = Unit
        override fun overlayOpened() = Unit
        override fun overlayClosed() = Unit
        override fun paid(revenue: NativeRevenue) = Unit
    }
}
