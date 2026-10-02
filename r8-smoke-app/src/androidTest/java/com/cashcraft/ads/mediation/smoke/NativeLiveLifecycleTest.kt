package com.cashcraft.ads.mediation.smoke

import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cashcraft.ads.mediation.AdsNativeView
import com.cashcraft.ads.mediation.NativeState
import com.cashcraft.ads.mediation.NativeAssets
import com.cashcraft.ads.mediation.NativeLayout
import com.cashcraft.ads.mediation.NativeLayoutBinding
import com.cashcraft.ads.mediation.NativeMediaType
import java.io.File

/** Opt-in real SDK checks: official AdMob test ID, no ad clicks or device network changes. */
@Suppress("DEPRECATION")
class NativeLiveLifecycleTest : InstrumentationTestCase() {
    private lateinit var activity: NativeSmokeActivity
    private val histories = mutableMapOf<AdsNativeView, MutableList<NativeState>>()

    override fun tearDown() {
        if (::activity.isInitialized) {
            onMain { activity.finish() }
            await("test Activity finally destroyed") { activity.isDestroyed }
        }
        super.tearDown()
    }

    fun testGoogleVideoWithoutMediaSlotStillBindsOtherAssets() {
        val original = launch(compactVideo = true).single()
        val compact = original.layout as NativeLayout.Custom
        var assets: NativeAssets? = null
        var binding: NativeLayoutBinding? = null
        val card = onMain {
            val parent = original.parent as ViewGroup
            original.destroy()
            parent.removeView(original)
            AdsNativeView(activity, activity, original.request,
                NativeLayout.Custom.withAssets { context, snapshot ->
                    assets = snapshot
                    compact.factory(context).let { views ->
                        views.media?.let { (it.parent as ViewGroup).removeView(it) }
                        views.copy(media = null).also { binding = it }
                    }
                }, active = false,
            ).also { parent.addView(it); track(it) }
        }
        try {
            load(card)
            onMain {
                val actual = checkNotNull(assets)
                val views = checkNotNull(binding)
                assertEquals(NativeMediaType.VIDEO, actual.mediaType)
                val mediaClass = Class.forName("com.google.android.libraries.ads.mobile.sdk.nativead.MediaView")
                assertFalse(descendants(card).any { mediaClass.isInstance(it) })
                assertEquals(actual.headline, views.headline.text.toString())
                assertEquals(actual.body, views.body!!.text.toString())
                assertEquals(actual.callToAction, views.callToAction.text.toString())
                listOf(views.headline, views.body!!, views.callToAction, views.adLabel).forEach {
                    assertEquals(View.VISIBLE, it.visibility)
                    assertTrue(it.isShown)
                }
            }
            stableLoads(card, 1)
            snapshot("google-video-without-media")
            onMain { card.setVisible(false) }
            released(card)
        } finally {
            onMain { card.destroy() }
        }
    }

    fun testVisibilityParentDialogAndDetailsReturn() {
        val card = launch().single()
        load(card)
        snapshot("lifecycle-loaded")
        onMain { card.retry() }
        stableLoads(card, 1)

        onMain { card.setVisible(false) }
        released(card)
        onMain { repeat(3) { card.retry() }; card.setVisible(true) }
        loaded(card)
        stableLoads(card, 2)

        val parent = onMain { card.parent as ViewGroup }
        onMain { parent.visibility = View.GONE }
        released(card)
        onMain { parent.visibility = View.VISIBLE }
        loaded(card)
        stableLoads(card, 3)

        val dialog = onMain {
            Dialog(activity).apply {
                setContentView(TextView(activity).apply { text = "Native lifecycle focus check" })
                show()
            }
        }
        try {
            released(card)
            stableLoads(card, 3)
        } finally {
            onMain { dialog.dismiss() }
        }
        loaded(card)
        stableLoads(card, 4)

        val monitor = instrumentation.addMonitor(NativeSmokeDetailsActivity::class.java.name, null, false)
        try {
            onMain {
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text.toString() == "Details" }.performClick()
            }
            val details = checkNotNull(monitor.waitForActivityWithTimeout(8_000))
            try {
                released(card)
                stableLoads(card, 4)
            } finally {
                onMain { details.finish() }
            }
            loaded(card)
            stableLoads(card, 5)
            snapshot("lifecycle-returned")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    fun testGoogleImageRetentionKeepsOriginalPlatformViewWithoutReload() {
        val original = launch(compactVideo = true, image = true).single()
        var assets: NativeAssets? = null
        val card = onMain {
            val parent = original.parent as ViewGroup
            original.destroy()
            parent.removeView(original)
            AdsNativeView(activity, activity, original.request,
                NativeLayout.Custom.withAssets { context, snapshot ->
                    assets = snapshot
                    (original.layout as NativeLayout.Custom).factory(context)
                }, active = false,
                retentionPolicy = com.cashcraft.ads.mediation.NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE,
            ).also { parent.addView(it); track(it) }
        }
        load(card)
        val platformView = onMain {
            assertEquals(NativeMediaType.IMAGE, checkNotNull(assets).mediaType)
            card.getChildAt(0)
        }
        snapshot("google-image-retain-before")
        onMain { card.setVisible(false) }
        SystemClock.sleep(2_000)
        onMain {
            assertEquals(NativeState.Loaded, card.state)
            assertFalse(platformView.isAttachedToWindow)
            assertEquals(0, card.childCount)
            card.setVisible(true)
        }
        await("原图片平台 View 恢复") {
            card.childCount == 1 && card.getChildAt(0) === platformView && platformView.isShown
        }
        stableLoads(card, 1)
        snapshot("google-image-retain-after")
        onMain { card.destroy() }
        onMain { assertEquals(NativeState.Destroyed, card.state); assertEquals(0, card.childCount) }
    }

    fun testSamePlacementCardsReleaseIndependently() {
        val cards = launch(twoCards = true)
        onMain { cards.forEach { it.setActive(true) } }
        cards.forEach(::loaded)
        val secondContainer = onMain {
            assertFalse(cards[0].request.position == cards[1].request.position)
            assertNotSame(cards[0].getChildAt(0), cards[1].getChildAt(0))
            cards[1].getChildAt(0)
        }
        onMain { cards[0].destroy(); cards[0].retry(); cards[0].setActive(true) }
        stableLoads(cards[1], 1)
        onMain {
            assertEquals(NativeState.Destroyed, cards[0].state)
            assertEquals(0, cards[0].childCount)
            assertEquals(NativeState.Loaded, cards[1].state)
            assertSame(secondContainer, cards[1].getChildAt(0))
        }
        snapshot("lifecycle-two-cards")
    }

    fun testRotationDestroysOldCardAndCreatesIndependentRequest() {
        val old = launch().single()
        load(old)
        val before = activity
        val originalOrientation = onMain { activity.requestedOrientation }
        val monitor = instrumentation.addMonitor(NativeSmokeActivity::class.java.name, null, false)
        try {
            val orientation = onMain { activity.resources.configuration.orientation }
            onMain {
                activity.requestedOrientation = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) as NativeSmokeActivity
            await("old rotated owner destroyed") { before.isDestroyed && old.state == NativeState.Destroyed }
            val replacement = onMain { cards().single().also(::track) }
            onMain {
                assertNotSame(old, replacement)
                assertEquals(0, old.childCount)
                old.retry()
                old.setActive(true)
                assertEquals(NativeState.Destroyed, old.state)
            }
            load(replacement)
            stableLoads(replacement, 1)
            snapshot("lifecycle-rotation")
        } finally {
            // Wait for the restoration recreation before the next case obtains its Activity.
            instrumentation.removeMonitor(monitor)
            val restoration = instrumentation.addMonitor(NativeSmokeActivity::class.java.name, null, false)
            try {
                onMain { activity.requestedOrientation = originalOrientation }
                val restored = restoration.waitForActivityWithTimeout(10_000)
                if (restored != null) activity = restored as NativeSmokeActivity
                await("orientation restored and stable") { !activity.isDestroyed && activity.hasWindowFocus() }
            } finally {
                instrumentation.removeMonitor(restoration)
            }
        }
    }

    fun testLeavingDuringLoadDoesNotResurrectCard() {
        val card = launch().single()
        onMain { card.setActive(true) }
        await("request in flight") { card.state == NativeState.Loading }
        onMain { card.destroy(); activity.finish() }
        SystemClock.sleep(10_000) // Observe real late SDK callbacks without assuming network cancellation.
        onMain {
            assertEquals(NativeState.Destroyed, card.state)
            assertEquals(0, card.childCount)
            assertFalse(histories.getValue(card).contains(NativeState.Loaded))
        }
        stableLoads(card, 1)
    }

    private fun launch(twoCards: Boolean = false, compactVideo: Boolean = false, image: Boolean = false): List<AdsNativeView> {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeSmokeActivity::class.java)
                .putExtra("platform", "admob").putExtra("mode", "view")
                .putExtra("active", false).putExtra("twoCards", twoCards)
                .putExtra("layout", if (compactVideo) "compact" else "default")
                // Google 官方 Native Video 测试广告，不替换宿主正式配置。
                .putExtra("placement", if (compactVideo && !image) "ca-app-pub-3940256099942544/1044960115" else null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeSmokeActivity
        await("host ready with window focus") {
            activity.hasWindowFocus() && cards().size == if (twoCards) 2 else 1
        }
        return onMain { cards().onEach(::track) }
    }

    private fun track(card: AdsNativeView) {
        val states = mutableListOf<NativeState>()
        histories[card] = states
        card.setOnStateChanged {
            states += it
            Log.i("NativeLiveLifecycle", "test=$name card=${card.request.position} state=$it")
        }
    }

    private fun load(card: AdsNativeView) {
        onMain { card.setActive(true) }
        loaded(card)
    }

    private fun loaded(card: AdsNativeView) {
        await("SDK Loaded", 90_000) {
            assertFalse("SDK failed: ${card.state}", card.state is NativeState.Failed)
            card.state == NativeState.Loaded && card.childCount == 1
        }
    }

    private fun released(card: AdsNativeView) {
        await("hidden card released") { card.state == NativeState.Idle && card.childCount == 0 }
    }

    private fun stableLoads(card: AdsNativeView, expected: Int) {
        SystemClock.sleep(1_000)
        onMain { assertEquals(expected, histories.getValue(card).count { it == NativeState.Loading }) }
        Log.i("NativeLiveLifecycle", "test=$name card=${card.request.position} loads=$expected checked")
    }

    private fun cards() = descendants(activity.window.decorView).filterIsInstance<AdsNativeView>()

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
    }

    private fun snapshot(label: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "$label.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
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
