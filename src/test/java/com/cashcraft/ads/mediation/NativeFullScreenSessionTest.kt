package com.cashcraft.ads.mediation

import android.app.Activity
import android.view.View
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.nativeads.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NativeFullScreenSessionTest {
    @Test fun `cached selection never waits and keeps the losing object`() {
        val request = ResolvedNativeRequest(position = "fallback", admobAdUnitId = "a", topOnPlacementId = "t")
        val visited = mutableListOf<AdPlatform>()
        assertNull(selectCachedNative(request, { visited += it.platform!!; null }, { _, _ -> fail() }))
        assertEquals(listOf(AdPlatform.ADMOB, AdPlatform.TOPON), visited)
        val a = Ad(AdPlatform.ADMOB, .001)
        val t = Ad(AdPlatform.TOPON, .002)
        val retained = mutableListOf<NativeAdHandle>()
        var bid: com.cashcraft.ads.mediation.internal.BidDecision? = null
        val winner = selectCachedNative(request, { if (it.platform == AdPlatform.ADMOB) a else t },
            { _, ad -> retained += ad }, { bid = it })
        assertEquals(AdPlatform.TOPON, bid!!.selection!!.winner)
        assertTrue(bid!!.admobAvailable && bid!!.topOnAvailable)
        assertEquals(.001, bid!!.admobPriceUsd!!, 0.0)
        assertEquals(.002, bid!!.topOnPriceUsd!!, 0.0)
        assertSame(t, winner!!.second)
        assertEquals(AdPlatform.TOPON, winner.first.platform)
        assertEquals(listOf(a), retained)
        assertFalse(a.destroyed)
        assertFalse(t.destroyed)
    }

    @Test fun `full screen units resolve independently without changing inline requests`() {
        val provider = BiddingProviderConfig(
            AdMobProviderConfig(AdMobIds.TEST.copy(fullScreenNativeId = "full-a")),
            TopOnProviderConfig(TopOnIds("app", "key", nativePlacementId = "inline-t", fullScreenNativePlacementId = "full-t")),
        )
        val request = NativeRequest("fallback")
        assertEquals("full-a", provider.resolveNativeRequest(request, true).admobAdUnitId)
        assertEquals("full-t", provider.resolveNativeRequest(request, true).topOnPlacementId)
        assertEquals(AdMobIds.TEST.nativeId, provider.resolveNativeRequest(request).admobAdUnitId)
        assertEquals("inline-t", provider.resolveNativeRequest(request).topOnPlacementId)
        assertEquals(AdMobIds.TEST.nativeId, AdMobProviderConfig(AdMobIds.TEST).resolveNativeRequest(request, true).admobAdUnitId)
    }

    @Test fun `single cached source wins and broken quotes are not fabricated`() {
        val request = ResolvedNativeRequest(position = "fallback", admobAdUnitId = "a", topOnPlacementId = "t")
        val only = Ad(AdPlatform.TOPON, null)
        assertSame(only, selectCachedNative(request, { if (it.platform == AdPlatform.TOPON) only else null },
            { _, _ -> fail() })!!.second)
    }

    @Test fun `reservation spans delivery and only real impression produces dismissed once`() {
        val h = Harness()
        try {
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            assertEquals("another_full_screen_ad_showing", FullScreenShowGate.reserve(FullScreenShowAttempt()))
            val sink = Sink()
            h.session.deliver(sink)
            assertSame(h.ad, sink.ad)
            assertTrue(h.results.isEmpty())
            h.session.onStateChanged(NativeState.Loaded)
            assertTrue(h.results.isEmpty()) // Render completion is not an impression or dismissal.
            // The confirmed event is observed after NativeCardController replaces the SDK callback.
            val events = NativeSlot(h.session.request, 1, { 1 }, AdEventListener {
                if (it.name == AdEventName.IMPRESSION) h.session.impression.set(true)
            }, AdRevenueListener.NONE).attempt({ 0 }, recordLoadEvents = false)
            events.impression("test", "id")
            h.session.complete()
            h.session.complete()
            assertEquals(listOf(AdShowResult.Dismissed), h.results)
            assertTrue(h.session.impression.get())
            assertFalse(FullScreenShowGate.isAnyAdShowing)
        } finally { h.close() }
    }

    @Test fun `one reserved object cannot be delivered twice and no impression stays failure`() {
        val h = Harness()
        try {
            val first = Sink()
            h.session.deliver(first)
            val second = Sink()
            h.session.deliver(second)
            assertSame(h.ad, first.ad)
            assertNull(second.ad)
            assertEquals("native_ad_unavailable", second.failure)
            h.session.complete()
            assertTrue(h.results.single() is AdShowResult.Failed)
        } finally { h.close() }
    }

    @Test fun `scene invalidation and expired cache release without delivery`() {
        for (expired in listOf(false, true)) {
            val h = Harness()
            try {
                if (expired) h.ad.valid = false else h.scene = false
                val sink = Sink()
                h.session.deliver(sink)
                assertNull(sink.ad)
                assertTrue(h.ad.destroyed)
                assertEquals(if (expired) "native_ad_unavailable" else "scene_invalid", sink.failure)
                h.session.finish(sink.failure!!)
                assertEquals(1, h.results.size)
                assertFalse(FullScreenShowGate.isAnyAdShowing)
            } finally { h.close() }
        }
    }

    @Test fun `SDK close or hidden card ends the single presentation without reloading`() {
        val h = Harness()
        try {
            h.session.onStateChanged(NativeState.Idle)
            assertTrue(h.results.isEmpty()) // Initial Idle is not a dismissal.
            h.session.deliver(Sink())
            h.session.impression.set(true)
            h.session.onStateChanged(NativeState.Loaded)
            h.session.onStateChanged(NativeState.Idle)
            h.session.onStateChanged(NativeState.Idle)
            assertEquals(listOf(AdShowResult.Dismissed), h.results)
            assertFalse(FullScreenShowGate.isAnyAdShowing)
        } finally { h.close() }
    }

    @Test fun `cancel before handoff disposes inventory and callback is cleared`() {
        val h = Harness()
        try {
            h.session.finish("opportunity_cancelled")
            h.session.finish("opportunity_cancelled")
            assertTrue(h.ad.destroyed)
            assertEquals(listOf(AdShowResult.Failed("opportunity_cancelled")), h.results)
            assertFalse(FullScreenShowGate.isAnyAdShowing)
        } finally { h.close() }
    }

    private class Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val ad = Ad(AdPlatform.ADMOB, .001)
        val results = mutableListOf<AdShowResult>()
        var scene = true
        private val attempt = FullScreenShowAttempt().also {
            assertNull(FullScreenShowGate.reserve(it))
            assertNull(FullScreenShowGate.commit(it))
        }
        val session = NativeFullScreenSession(
            ResolvedNativeRequest(AdPlatform.ADMOB, "test", "fallback"),
            NativeLayout.Custom { error("unused layout") }, ad, attempt, controller.get(), { scene },
            results::add, availability = { NativeAvailability(ready = true) },
        )
        fun close() { session.complete(); controller.pause().stop().destroy() }
    }

    private class Ad(override val platform: AdPlatform, override val bidPriceUsd: Double?) : NativeAdHandle {
        var listener: NativeCallbacks? = null
        var valid = true
        var destroyed = false
        override val adSource = "test"
        override val responseId = "id"
        override val isTemplate = false
        override val isValid get() = valid
        override val expiresAtMillis: Long? = Long.MAX_VALUE
        override fun setCallbacks(callbacks: NativeCallbacks?) { listener = callbacks }
        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("not rendered")
        override fun destroy() { destroyed = true }
    }

    private class Sink : NativeCallbacks {
        var ad: NativeAdHandle? = null
        var failure: String? = null
        var impressions = 0
        override fun loaded(ad: NativeAdHandle) { this.ad = ad }
        override fun failed(reason: String, errorCode: String?) { failure = reason }
        override fun impression(adSource: String?, responseId: String?) { impressions++ }
        override fun clicked(adSource: String?, responseId: String?) = Unit
        override fun closed() = Unit
        override fun overlayOpened() = Unit
        override fun overlayClosed() = Unit
        override fun paid(revenue: NativeRevenue) = Unit
    }
}
