package com.cashcraft.ads.mediation

import android.app.Application
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import com.cashcraft.ads.mediation.admob.AdMobNextGenBidPrice
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoadResult
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import java.lang.reflect.Field
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in network probe for GMA 1.2.1. Uses only Google's native test inventory. */
@Suppress("DEPRECATION")
class NativePreloadProbeTest : InstrumentationTestCase() {
    fun testPeekIdentityPriceAndAutomaticRefill() {
        val id = "native-preload-probe"
        val loaded = CopyOnWriteArrayList<String?>()
        val owned = mutableListOf<NativeAd>()
        onMain {
            Ads.initialize(instrumentation.targetContext.applicationContext as Application,
                AdsConfig(AdMobProviderConfig(AdMobIds.TEST),
                    umpConsent = UmpConsentConfig(enabled = false), autoShowAppOpen = false))
        }
        await("SDK initialized") { Ads.nativeAvailability(AdPlatform.ADMOB).ready }
        try {
            onMain {
                // Free this isolated test process's full-screen buffers before testing native.
                AppOpenAdPreloader.destroyAll()
                InterstitialAdPreloader.destroyAll()
                RewardedAdPreloader.destroyAll()
                AdMobNextGenBidPrice.initialize(instrumentation.targetContext)
                val request = NativeAdRequest.Builder("ca-app-pub-3940256099942544/2247696110",
                    listOf(NativeAd.NativeAdType.NATIVE)).build()
                assertTrue(NativeAdPreloader.start(id, PreloadConfiguration(request, 2),
                    object : PreloadCallback {
                        override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                            loaded += responseInfo.responseId
                            Log.i(TAG, "callback loaded response=${responseInfo.responseId} total=${loaded.size}")
                        }
                        override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                            Log.i(TAG, "callback failed code=${adError.code} message=${adError.message}")
                        }
                        override fun onAdsExhausted(preloadId: String) {
                            Log.i(TAG, "callback exhausted")
                        }
                    }))
            }
            await("initial buffer=2") { NativeAdPreloader.getNumAdsAvailable(id) == 2 }
            repeat(3) { round ->
                val before = onMain { quote(id) }
                // Model losing an auction: do not poll, destroy, or request another ad.
                SystemClock.sleep(1_000)
                onMain {
                    val retained = quote(id)
                    assertSame("Peek must retain the same SDK object", before.ad, retained.ad)
                    assertEquals(before.responseId, retained.responseId)
                    assertEquals(2, NativeAdPreloader.getNumAdsAvailable(id))
                    val result = NativeAdPreloader.pollAd(id) as NativeAdLoadResult.NativeAdSuccess
                    val ad = result.ad
                    owned += ad
                    assertEquals(before.responseId, ad.getResponseInfo().responseId)
                    assertSame("Quoted configuration must back the polled NativeAd", before.configuration,
                        field(field(ad, "a"), "b"))
                    assertEquals(before.usd, AdMobNextGenBidPrice.fromNative(ad))
                    Log.i(TAG, "round=$round polled response=${ad.getResponseInfo().responseId} " +
                        "sameConfiguration=true usd=${before.usd} remaining=${NativeAdPreloader.getNumAdsAvailable(id)}")
                }
                // No further start/load call: the SDK must refill after poll, before ad destruction.
                await("automatic refill after round=$round") {
                    NativeAdPreloader.getNumAdsAvailable(id) == 2 && loaded.size >= round + 3
                }
                onMain {
                    Log.i(TAG, "round=$round automaticRefill=true available=2 callbacks=${loaded.size}")
                    owned.removeAt(0).destroy()
                }
            }
            Log.i(TAG, "PASS rounds=3 peekNonConsuming=true identity=true price=true automaticRefill=true")
        } finally {
            onMain {
                owned.forEach { it.destroy() }
                NativeAdPreloader.destroy(id)
                assertEquals(0, NativeAdPreloader.getNumAdsAvailable(id))
            }
        }
    }

    private data class Quote(val ad: Any, val configuration: Any, val responseId: String, val usd: Double)

    private fun quote(id: String): Quote {
        val root = Class.forName("ads_mobile_sdk.gt0").getDeclaredMethod("a")
            .apply { isAccessible = true }.invoke(null)!!
        val provider = field(root, "P0")
        val registry = provider.javaClass.getMethod("get").apply { isAccessible = true }.invoke(provider)!!
        val mapField = fields(registry).single { candidate ->
            (candidate.apply { isAccessible = true }.get(registry) as? Map<*, *>)?.containsKey(id) == true
        }
        val manager = (mapField.get(registry) as Map<*, *>)[id]!!
        val queue = field(manager, "B") as Queue<*>
        val item = checkNotNull(queue.peek())
        val ad = field(field(item, "a"), "a")
        Log.i(TAG, "path registry=${registry.javaClass.name}.${mapField.name} manager=${manager.javaClass.name}.B " +
            "head=${item.javaClass.name} internalAd=${ad.javaClass.name}")
        try {
            val response = ad.javaClass.getMethod("getResponseInfo").apply { isAccessible = true }.invoke(ad) as ResponseInfo
            val config = ad.javaClass.getMethod("b").apply { isAccessible = true }.invoke(ad)!!
            val price = field(config, "m")
            val micros = (field(price, "b") as Number).toLong()
            assertEquals("USD", field(price, "d"))
            assertTrue(micros >= 0)
            val responseId = checkNotNull(response.responseId)
            assertEquals(responseId, NativeAdPreloader.peekAdResponseInfo(id)?.responseId)
            Log.i(TAG, "peek response=$responseId micros=$micros currency=USD count=${queue.size}")
            return Quote(ad, config, responseId, micros / 1_000_000.0)
        } catch (error: Throwable) {
            Log.e(TAG, "shape fields=${fields(ad).map { it.name + ":" + it.type.name }} " +
                "methods=${ad.javaClass.declaredMethods.map { it.name + ":" + it.returnType.name }}", error)
            throw error
        }
    }

    private fun fields(value: Any): List<Field> = generateSequence(value.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.toList()

    private fun field(value: Any, name: String): Any = fields(value).first { it.name == name }
        .apply { isAccessible = true }.get(value) ?: error("Null field $name in ${value.javaClass.name}")

    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            if (onMain(condition)) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object { const val TAG = "NativePreloadProbe" }
}
