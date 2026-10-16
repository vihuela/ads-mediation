@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation.smoke

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.KeyEvent
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.Ads
import org.json.JSONObject
import java.io.File

/** Official test ads only; distinguish initial pool filling from results after a real take. */
class AdMobAutoLoadTest : InstrumentationTestCase() {
    fun testRefillAfterConsumptionUsesAutomaticRequestIdentities() {
        val context = instrumentation.targetContext
        val application = context.applicationContext as ProbeApplication
        val activity = instrumentation.startActivitySync(
            Intent(context, SmokeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("banner_active", false),
        ) as SmokeActivity
        val terminalNames = setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL)
        try {
            await("initial buffer of at least two ads") {
                application.interstitialEvents.count { it.name == AdEventName.LOADED } >= 2
            }
            SystemClock.sleep(3_000)
            val baseline = onMain { application.interstitialEvents.count { it.name in terminalNames } }
            val takenAt = SystemClock.elapsedRealtime()
            Log.i("AdTracking", "baseline_callbacks=$baseline take_at_elapsed_ms=$takenAt")
            onMain { Ads.showInterstitial(activity, "tracking_refill") }
            await("actual interstitial exposure") {
                application.interstitialEvents.any { it.name == AdEventName.SHOW && it.position == "tracking_refill" }
            }
            await("new SDK refill result after take") {
                application.interstitialEvents.count { it.name in terminalNames } > baseline
            }
            val observedAt = SystemClock.elapsedRealtime()
            val events = onMain { application.interstitialEvents.toList() }
            val loads = events.filter { it.name == AdEventName.LOAD || it.name in terminalNames }
            val groups = loads.groupBy { it.requestId }
            assertFalse(groups.containsKey(null))
            assertTrue(groups.size > baseline)
            groups.values.forEachIndexed { index, pair ->
                assertEquals(2, pair.size)
                assertEquals(AdEventName.LOAD, pair.first().name)
                assertTrue(pair.last().name in terminalNames)
                assertEquals(pair.first().requestId, pair.last().requestId)
                assertTrue(pair.all { checkNotNull(it.bufferSize) >= 2 })
                if (index > 0) pair.forEach { event ->
                    assertEquals("sdk_auto", event.analyticsParameters()["load_trigger"])
                    assertFalse(event.analyticsParameters().containsKey("latency_ms"))
                }
            }
            val report = buildString {
                appendLine("baseline_callbacks=$baseline take_at_elapsed_ms=$takenAt observed_at_elapsed_ms=$observedAt")
                events.forEach { appendLine("event=${it.name.analyticsName} params=${JSONObject(it.analyticsParameters())}") }
            }
            File(context.getExternalFilesDir(null), "ad-tracking-refill.txt").writeText(report)
            Log.i("AdTracking", "PASS refill_results=${groups.size - baseline} request_groups=${groups.size}")
        } finally {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            onMain { activity.finish() }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(100)
        }
        fail("未覆盖：$label")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
