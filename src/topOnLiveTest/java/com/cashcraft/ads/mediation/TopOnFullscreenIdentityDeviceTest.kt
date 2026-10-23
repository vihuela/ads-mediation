package com.cashcraft.ads.mediation

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.thinkup.core.api.TUAdInfo
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** Opt in with -PtopOnLiveTest=<method>; invoke each method in a fresh instrumentation process. */
@Suppress("DEPRECATION")
class TopOnFullscreenIdentityDeviceTest : InstrumentationTestCase() {
    fun testAppOpenIdentityAndLegacyRevenue() = verify(AdFormat.APP_OPEN)
    fun testInterstitialIdentityAndLegacyRevenue() = verify(AdFormat.INTERSTITIAL)
    fun testRewardedIdentityAndLegacyRevenue() = verify(AdFormat.REWARDED)

    private fun verify(format: AdFormat) {
        val properties = Properties().apply {
            File(instrumentation.targetContext.filesDir, "topon-test.properties").inputStream().use(::load)
        }
        fun required(key: String) = checkNotNull(properties.getProperty(key)?.takeIf { it.isNotBlank() }) {
            "Missing private test property: $key"
        }
        val ids = TopOnIds(required("applicationId"), required("applicationKey"),
            appOpenPlacementId = if (format == AdFormat.APP_OPEN) required("appOpenPlacement") else "",
            interstitialPlacementId = if (format == AdFormat.INTERSTITIAL) required("interstitialPlacement") else "",
            rewardedPlacementId = if (format == AdFormat.REWARDED) required("rewardedPlacement") else "")
        val events = CopyOnWriteArrayList<AdEvent>()
        val payments = CopyOnWriteArrayList<AdRevenuePayload>()
        val results = CopyOnWriteArrayList<AdShowResult>()
        val position = "device-real-${format.analyticsValue}"
        var activity: NativeFragmentTestActivity? = null
        var task: AdTask? = null
        try {
            onMain {
                assertEquals("Use a fresh process for every method", AdsState.NOT_INITIALIZED, Ads.state)
                Ads.initialize(instrumentation.targetContext.applicationContext as Application,
                    AdsConfig(TopOnProviderConfig(ids), umpConsent = UmpConsentConfig(enabled = false),
                        autoShowAppOpen = false, loggingEnabled = false,
                        eventListener = AdEventListener(events::add),
                        revenueListener = AdRevenueListener(payments::add)))
            }
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                NativeFragmentTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeFragmentTestActivity
            await("focused host and real $format fill", events) {
                onMain {
                    assertFalse("SDK initialization failed", Ads.state == AdsState.FAILED)
                    AdLifecycleMonitor.currentActivity === activity && activity?.hasWindowFocus() == true && Ads.isReady(format)
                }
            }
            onMain {
                task = when (format) {
                    AdFormat.APP_OPEN -> Ads.showOpen(position, results::add)
                    AdFormat.INTERSTITIAL -> Ads.showInter(position, onResult = results::add)
                    AdFormat.REWARDED -> Ads.showRewardedWhenReady(checkNotNull(activity), position) {
                        results += it.showResult
                    }
                    else -> error("Unsupported test format")
                }
            }
            await("actual exposure and ordinary revenue listener for $format", events) {
                assertFalse("Show ended without confirmed exposure: $results",
                    results.any { it is AdShowResult.Failed || it is AdShowResult.Blocked })
                events.any { it.name == AdEventName.SHOW && it.position == position } && payments.isNotEmpty()
            }
            // Keep observing after the first payment so duplicate SDK delivery is visible to assertions.
            SystemClock.sleep(1_000L)
            onMain {
                val shown = events.filter { it.name == AdEventName.SHOW && it.position == position }
                assertEquals(1, shown.size)
                assertEquals(1, payments.size)
                val show = shown.single()
                val payment = payments.single() as TopOnRevenuePayload
                assertEquals(format, show.format)
                assertEquals(format, payment.format)
                assertEquals(position, payment.position)
                assertEquals(show.sessionId, payment.sessionId)
                assertEquals(show.adUnitId, payment.placementId)
                assertEquals(AdPlatform.TOPON, payment.platform)
                assertTrue(payment.valueMicros >= 0L)
                assertEquals("USD", payment.currencyCode)
                val info = payment.adInfo as TUAdInfo
                assertTrue("Real SDK did not echo showCustomExt", !info.showCustomExt.isNullOrBlank())
                assertEquals(info.showId?.trim()?.takeIf { it.isNotEmpty() }, payment.impressionId)
                for (event in events.filter { it.position == position }) {
                    val fields = event.analyticsParameters()
                    assertTrue("Missing serialized contract for ${event.name}", fields.isNotEmpty())
                    assertEquals(format.analyticsValue, fields["ad_format"])
                    assertFalse(fields.containsKey("ad_type"))
                    assertEquals(show.sessionId, fields["ad_session_id"])
                }
                assertEquals(1, events.count { it.name == AdEventName.IMPRESSION && it.sessionId == show.sessionId })
                assertFalse(events.any { it.name == AdEventName.SHOW_FAIL && it.sessionId == show.sessionId })
            }
        } finally {
            val directory = instrumentation.targetContext.filesDir
            File(directory, "topon-identity-${format.analyticsValue}.jsonl").bufferedWriter().use { writer ->
                events.forEach { event -> writer.appendLine(JSONObject(event.analyticsParameters())
                    .put("event", event.name.analyticsName).toString()) }
                payments.forEach { payment -> writer.appendLine(JSONObject()
                    .put("payment_format", payment.format.analyticsValue).put("session_id", payment.sessionId)
                    .put("position", payment.position).put("value_micros", payment.valueMicros)
                    .put("currency", payment.currencyCode).put("token_present",
                        !(payment as TopOnRevenuePayload).let { it.adInfo as TUAdInfo }.showCustomExt.isNullOrBlank())
                    .toString()) }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "topon-identity-${format.analyticsValue}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            onMain { task?.cancel(); activity?.finish() }
        }
    }

    private fun await(label: String, events: List<AdEvent>, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        fail("UNCOVERED: $label; callbacks=${events.map { "${it.format}:${it.name}:${it.errorCode}" }}")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
