package com.cashcraft.ads.mediation.smoke

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.cashcraft.ads.mediation.AdsNativeView
import com.cashcraft.ads.mediation.NativeState
import java.io.File
import kotlin.math.roundToInt

/** Select individual methods: options check needs no fill; live check needs a TopOn template. */
@Suppress("DEPRECATION")
class NativeSmokeSizingTest : InstrumentationTestCase() {
    fun testSizingOptionsKeepHistoricalCandidateExplicit() {
        val base = Intent().putExtra("platform", "topon")
        val configured = NativeSmokeOptions.from(base)
        assertEquals(BuildConfig.NATIVE_TEST_TEMPLATE_RATIO.toFloatOrNull(), configured.request(0).topOnTemplateAspectRatio)
        assertNull(configured.templatePreset)
        assertEquals(2f, NativeSmokeOptions.from(Intent(base).putExtra("templateRatio", "2")).request(0).topOnTemplateAspectRatio)

        val candidate = Intent(base).putExtra("templatePreset", "healthtracker-4x1")
        val options = NativeSmokeOptions.from(candidate)
        assertEquals(4f, options.request(0).topOnTemplateAspectRatio)
        assertFalse(options.customLayout)
        assertTrue(options.summary().contains("CANDIDATE (not backend-confirmed)"))
        assertTrue(NativeSmokeOptions.from(Intent(base).putExtra("layout", "compact")).summary().contains("layout=compact"))
        for (invalid in listOf(
            Intent(candidate).putExtra("layout", "custom"),
            Intent(candidate).putExtra("layout", "compact"),
            Intent(candidate).putExtra("platform", "admob"),
            Intent(candidate).putExtra("templateRatio", "2"),
            Intent(candidate).putExtra("templatePreset", "unknown"),
        )) {
            assertTrue("Invalid candidate combination must be rejected", runCatching {
                NativeSmokeOptions.from(invalid)
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    fun testLiveTopOnTemplateCandidateDimensions() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeSmokeActivity::class.java)
                .putExtra("platform", "topon").putExtra("mode", "view")
                .putExtra("templatePreset", "healthtracker-4x1").putExtra("active", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeSmokeActivity
        try {
            await("host ready") {
                descendants(activity.window.decorView).filterIsInstance<AdsNativeView>().size == 1 && activity.hasWindowFocus()
            }
            val card = onMain { descendants(activity.window.decorView).filterIsInstance<AdsNativeView>().single() }
            val states = mutableListOf<NativeState>()
            onMain {
                card.setOnStateChanged { state ->
                    states += state
                    Log.i("NativeSizing", "state=$state")
                }
                card.setActive(true)
            }
            await("SDK terminal result", 90_000) { card.state == NativeState.Loaded || card.state is NativeState.Failed }
            snapshot("topon-template-candidate-terminal")
            onMain { assertEquals("SDK must deliver an ad before geometry can be checked", NativeState.Loaded, card.state) }
            await("rendered container measured") { card.childCount == 1 && card.getChildAt(0).height > 0 }
            SystemClock.sleep(3_000)
            snapshot("topon-template-candidate-3s")
            onMain {
                val width = card.width - card.paddingLeft - card.paddingRight
                val height = (width / 4f).roundToInt().coerceAtLeast(1)
                val rendered = card.getChildAt(0)
                val params = rendered.layoutParams
                Log.i("NativeSizing", "candidate=healthtracker-4x1 hostContentWidth=$width expectedHeight=$height " +
                    "container=${rendered.width}x${rendered.height} params=${params.width}x${params.height}")
                // Current library declares explicit width/height only for its template branch.
                assertTrue("Non-template fill: template sample not covered", params.width > 0 && params.height > 0)
                assertEquals(width, params.width)
                assertEquals(height, params.height)
                assertEquals(width, rendered.width)
                assertEquals(height, rendered.height)
                assertEquals(1, states.count { it == NativeState.Loading })
                descendants(rendered).take(24).forEach {
                    Log.i("NativeSizing", "view=${it.javaClass.name} size=${it.width}x${it.height} shown=${it.isShown}")
                }
                card.setVisible(false)
                assertEquals(NativeState.Idle, card.state)
                assertEquals(0, card.childCount)
            }
            Log.i("NativeSizing", "geometry-and-hide=passed visual=requires-screenshot-review ratio=not-backend-confirmed")
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
    }

    private fun snapshot(label: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.filesDir, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun await(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $label")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
