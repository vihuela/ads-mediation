package com.cashcraft.ads.mediation

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle
import com.cashcraft.ads.mediation.internal.nativeads.NativeCallbacks
import com.cashcraft.ads.mediation.internal.nativeads.NativeRevenue
import java.util.concurrent.CopyOnWriteArrayList

/** Real Google test inventory; each method runs in an isolated instrumentation process. */
@Suppress("DEPRECATION")
class NativeTelemetryDeviceTest : InstrumentationTestCase() {
    private lateinit var activity: NativeFragmentTestActivity

    override fun setUp() {
        super.setUp()
        events.clear()
        onMain {
            Ads.initialize(instrumentation.targetContext.applicationContext as Application, config)
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            NativeFragmentTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeFragmentTestActivity
        await("foreground and SDK ready") {
            AdLifecycleMonitor.isAppInForeground && Ads.nativeAvailability(AdPlatform.ADMOB).ready
        }
    }

    fun testRealPreloadAndSdkRefillHaveDistinctPairedEvents() {
        val request = ResolvedNativeRequest(AdPlatform.ADMOB, checkNotNull(AdMobIds.TEST.nativeId), "device-telemetry-a")
        var first: AutoCloseable? = null
        var second: AutoCloseable? = null
        var ad: NativeAdHandle? = null
        try {
            onMain { first = NativeAdCache.acquirePreload(request) }
            await("initial test ad") { NativeAdCache.peekPreload(request) != null && filled(request).size == 1 }
            onMain {
                val initial = filled(request).single()
                assertFalse(initial.sdkAutoLoad)
                assertNotNull(initial.latencyMillis)
                assertNotNull(initial.responseId)
                second = NativeAdCache.acquirePreload(request.copy(position = "device-telemetry-b"))
                assertEquals("Shared demand must not duplicate a request", 1,
                    nativeEvents(request).count { it.name == AdEventName.LOAD })
                ad = checkNotNull(NativeAdCache.takePreload(request, callbacks))
                assertEquals(initial.responseId, ad!!.responseId)
            }
            await("SDK automatic refill") { filled(request).size >= 2 && NativeAdCache.peekPreload(request) != null }
            onMain {
                val initial = filled(request).first()
                val refill = filled(request).first { it.requestId != initial.requestId }
                assertTrue(refill.sdkAutoLoad)
                assertNull(refill.latencyMillis)
                assertEquals("sdk_auto", refill.analyticsParameters()["load_trigger"])
                assertFalse(refill.analyticsParameters().containsKey("latency_ms"))
                for (result in listOf(initial, refill)) {
                    val pair = nativeEvents(request).filter { it.requestId == result.requestId }
                    assertEquals(listOf(AdEventName.LOAD, AdEventName.LOADED), pair.map { it.name })
                    assertEquals(pair.first().number, result.number)
                    assertEquals(pair.first().sdkAutoLoad, result.sdkAutoLoad)
                }
                assertFalse(initial.responseId == refill.responseId)
                Log.i(TAG, "PASS real_preload=true shared_demand=true sdk_refill=true distinct_request_ids=true sdk_auto_latency_absent=true")
            }
        } finally {
            onMain { first?.close(); second?.close(); NativeAdCache.closePreload(request); ad?.destroy() }
        }
    }

    fun testRealSdkInvalidUnitPreservesErrorCodeAndDetail() {
        val request = ResolvedNativeRequest(AdPlatform.ADMOB, "invalid-device-test-ad-unit", "device-telemetry-invalid")
        var demand: AutoCloseable? = null
        try {
            onMain { demand = NativeAdCache.acquirePreload(request) }
            await("SDK invalid-unit failure") { nativeEvents(request).any { it.name == AdEventName.LOAD_FAIL } }
            onMain {
                val failed = nativeEvents(request).first { it.name == AdEventName.LOAD_FAIL }
                assertFalse("SDK code must survive the callback", failed.errorCode.isNullOrBlank())
                assertFalse("SDK detail must survive the callback", failed.errorDetail.isNullOrBlank())
                assertEquals("native_inventory_load_failed", failed.reason)
                val properties = failed.analyticsParameters()
                assertEquals(failed.errorCode, properties["error_code"])
                assertEquals(failed.errorDetail, properties["error_detail"])
                assertEquals(listOf(AdEventName.LOAD, AdEventName.LOAD_FAIL),
                    nativeEvents(request).filter { it.requestId == failed.requestId }.map { it.name })
                Log.i(TAG, "PASS real_sdk_failure=true code=${failed.errorCode} detail_present=true paired_request=true")
            }
        } finally {
            onMain { demand?.close(); NativeAdCache.closePreload(request) }
        }
    }

    private fun nativeEvents(request: ResolvedNativeRequest) =
        events.filter { it.format == AdFormat.NATIVE && it.adUnitId == request.adUnitId }
    private fun filled(request: ResolvedNativeRequest) = nativeEvents(request).filter { it.name == AdEventName.LOADED }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label; events=${events.map { "${it.name}:${it.errorCode}" }}")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun tearDown() {
        try { onMain { NativeAdCache.clear(); if (::activity.isInitialized) activity.finish() } }
        finally { super.tearDown() }
    }

    private companion object {
        const val TAG = "NativeTelemetryDevice"
        val events = CopyOnWriteArrayList<AdEvent>()
        val config = AdsConfig(
            AdMobProviderConfig(AdMobIds(AdMobIds.TEST.applicationId, nativeId = AdMobIds.TEST.nativeId)),
            umpConsent = UmpConsentConfig(enabled = false), autoShowAppOpen = false,
            eventListener = AdEventListener(events::add),
        )
        val callbacks = object : NativeCallbacks {
            override fun loaded(ad: NativeAdHandle) = Unit
            override fun failed(reason: String, errorCode: String?, errorDetail: String?) = Unit
            override fun impression(adSource: String?, responseId: String?, revenue: NativeRevenue?) = Unit
            override fun clicked(adSource: String?, responseId: String?) = Unit
            override fun closed() = Unit
            override fun overlayOpened() = Unit
            override fun overlayClosed() = Unit
            override fun paid(revenue: NativeRevenue) = Unit
        }
    }
}
