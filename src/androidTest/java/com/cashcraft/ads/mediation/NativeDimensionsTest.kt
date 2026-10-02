package com.cashcraft.ads.mediation

import android.app.Activity
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.FrameLayout
import com.cashcraft.ads.mediation.internal.nativeads.*
import java.lang.reflect.InvocationTargetException

/** Real View layout/padding with a controlled loader, without requesting a production template. */
@Suppress("DEPRECATION")
class NativeDimensionsTest : InstrumentationTestCase() {
    fun testPaddingInvalidatesLoadingAndDisplayedTemplateOnlyOnce() = withHost { host ->
        host.start()
        val old = host.loads.single()
        host.card.setPadding(20, 0, 20, 0)
        host.layout()
        repeat(3) { host.card.requestLayout(); host.layout() }
        assertEquals(listOf(360, 320), host.widths)
        val stale = Template()
        old.loaded(stale)
        assertEquals(1, stale.destroyed)
        assertEquals(0, host.card.childCount)
        val current = Template()
        host.loads.last().loaded(current)
        host.layout()
        assertEquals(320, current.renderedWidth)
        assertEquals(1, host.card.childCount)
        host.card.setPadding(30, 0, 30, 0)
        host.layout()
        repeat(3) { host.card.requestLayout(); host.layout() }
        assertEquals(1, current.destroyed)
        assertEquals(0, host.card.childCount)
        assertEquals(listOf(360, 320, 300), host.widths)
    }

    fun testPaddingChangedBeforeNextLayoutRejectsOldResultAtMount() = withHost { host ->
        host.start()
        host.card.setPadding(20, 0, 20, 0)
        val stale = Template()
        host.loads.single().loaded(stale)
        assertEquals(1, stale.destroyed)
        assertEquals(0, stale.renderedWidth)
        assertEquals(listOf(360, 320), host.widths)
        host.layout()
        val current = Template()
        host.loads.last().loaded(current)
        host.layout()
        assertEquals(320, current.renderedWidth)
        assertEquals(NativeState.Loaded, host.card.state)
        assertEquals(2, host.loads.size)
    }

    private fun withHost(block: (Host) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeFragmentTestActivity
        try {
            var outcome: Result<Unit>? = null
            instrumentation.runOnMainSync {
                outcome = runCatching {
                    val host = Host(activity)
                    try { block(host) } finally { host.card.destroy() }
                }
            }
            outcome!!.getOrThrow()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private class Host(activity: NativeFragmentTestActivity) {
        val request = NativeRequest("dimensions", topOnTemplateAspectRatio = 2f)
        val card = AdsNativeView(activity, activity, request, active = false)
        val widths = mutableListOf<Int>()
        val loads = mutableListOf<NativeCallbacks>()
        private val presentation = (AdsNativeView::class.java.getDeclaredField("entry").apply {
            isAccessible = true
        }.get(card) as NativePositionRegistry.Entry<*>).value
        private val widthField = presentation.javaClass.getDeclaredField("requestedWidth").apply { isAccessible = true }
        private val renderMethod = presentation.javaClass.getDeclaredMethod(
            "render", NativeAdHandle::class.java, Function0::class.java,
        ).apply { isAccessible = true }
        private val controller = NativeCardController(
            availability = { NativeAvailability(ready = true) },
            canDisplay = { card.width > card.paddingLeft + card.paddingRight },
            newSlot = { NativeSlot(ResolvedNativeRequest(AdPlatform.TOPON, "test-only-no-load", request.position, request.topOnTemplateAspectRatio), 1, { 1 }, AdEventListener {}, AdRevenueListener {}) },
            load = {
                val width = card.width - card.paddingLeft - card.paddingRight
                widthField.setInt(presentation, width)
                widths += width
                loads += it
                NativeLoad { }
            },
            render = { ad, current ->
                try { renderMethod.invoke(presentation, ad, current) }
                catch (error: InvocationTargetException) { throw error.targetException }
            },
            removeView = { card.removeAllViews() }, clock = { 0L }, dispatch = { it() },
            interaction = { _, _ -> }, onStateChanged = {},
        )
        init {
            // Replace SDK effects only; the card's layout and mount path remain real.
            presentation.javaClass.getDeclaredField("controller").apply {
                isAccessible = true
                (get(presentation) as NativeCardController).destroy()
                set(presentation, controller)
            }
        }
        fun start() { layout(); controller.update(true, true) }
        fun layout() {
            card.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            card.layout(0, 0, 360, 640)
        }
    }

    private class Template : NativeAdHandle {
        var destroyed = 0
        var renderedWidth = 0
        override val adSource = "controlled-template"
        override val responseId = "test"
        override val isTemplate = true
        override val expiresAtMillis: Long? = null
        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View {
            check(binding == null)
            renderedWidth = widthPx
            return FrameLayout(activity).apply { layoutParams = FrameLayout.LayoutParams(widthPx, widthPx / 2) }
        }
        override fun destroy() { destroyed++ }
    }
}
