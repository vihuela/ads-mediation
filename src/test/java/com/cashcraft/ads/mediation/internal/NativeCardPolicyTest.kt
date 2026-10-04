package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.view.View
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.nativeads.*
import org.junit.Assert.*
import org.junit.Test

class NativeCardPolicyTest {
    @Test fun `actual impression queued before teardown still consumes usage once`() {
        val h = Host().start()
        h.loads.single().loaded(Ad())
        val delivery = h.loads.single()
        h.controller.destroy()
        delivery.impression("network", "response")
        delivery.impression("network", "response")
        assertEquals(1, h.policy.impressions)
        assertTrue(h.events.none { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `business block precedes provider resolution and reports once across lifecycle callbacks`() {
        val h = Host()
        h.policy.initialBlock = AdBlockReason.POSITION_DISABLED
        h.start()
        repeat(5) { h.controller.refresh(); h.controller.update(true, true) }
        assertEquals(NativeState.Blocked(AdBlockReason.POSITION_DISABLED), h.controller.state)
        assertEquals(0, h.availabilityCalls)
        assertTrue(h.loads.isEmpty())
        assertTrue(h.events.isEmpty())
        assertEquals(1, h.policy.completions)
        h.controller.destroy()
        assertEquals(1, h.policy.completions)
    }

    @Test fun `changed policy during loading cancels delivery without SHOW_FAIL`() {
        val h = Host().start()
        h.policy.initialBlock = AdBlockReason.GLOBAL_DISABLED
        h.controller.refresh()
        val staleAd = Ad()
        h.loads.single().loaded(staleAd)
        assertEquals(1, staleAd.releases)
        assertEquals(1, h.cancellations)
        assertEquals(0, h.renders)
        assertEquals(NativeState.Blocked(AdBlockReason.GLOBAL_DISABLED), h.controller.state)
        assertTrue(h.events.none { it.name == AdEventName.SHOW_FAIL })
        assertEquals(1, h.policy.completions)
    }

    @Test fun `policy change in loading state callback prevents SDK load`() {
        val h = Host()
        h.onState = { if (it == NativeState.Loading) h.policy.initialBlock = AdBlockReason.GLOBAL_DISABLED }
        h.start()
        assertTrue(h.loads.isEmpty())
        assertEquals(NativeState.Blocked(AdBlockReason.GLOBAL_DISABLED), h.controller.state)
        assertEquals(1, h.policy.completions)
        assertTrue(h.events.none { it.name == AdEventName.SHOW_FAIL })
    }

    @Test fun `daily quota exhausted by competing card prevents final bind`() {
        val h = Host().start()
        h.policy.bindBlock = AdBlockReason.DAILY_SHOW_LIMIT
        val ad = Ad()
        h.loads.single().loaded(ad)
        repeat(4) { h.controller.refresh() }
        assertEquals(NativeState.Blocked(AdBlockReason.DAILY_SHOW_LIMIT), h.controller.state)
        assertEquals(1, ad.releases)
        assertEquals(0, h.renders)
        assertEquals(1, h.policy.reservations)
        assertEquals(1, h.policy.completions)
        assertTrue(h.events.none { it.name == AdEventName.SHOW_FAIL || it.name == AdEventName.IMPRESSION })
    }

    @Test fun `policy changing inside layout factory is checked again before final attachment`() {
        val h = Host().start()
        h.beforeBind = { h.policy.bindBlock = AdBlockReason.POSITION_DISABLED }
        val ad = Ad()
        h.loads.single().loaded(ad)
        assertEquals(NativeState.Blocked(AdBlockReason.POSITION_DISABLED), h.controller.state)
        assertEquals(0, h.renders)
        assertEquals(1, ad.releases)
        assertEquals(1, h.policy.completions)
    }

    @Test fun `only actual impression consumes usage and own quota never releases retained ad`() {
        val h = Host().start()
        val ad = Ad()
        h.loads.single().loaded(ad)
        assertEquals(0, h.policy.impressions)
        h.loads.single().impression("network", "response")
        h.loads.single().impression("network", "response")
        assertEquals(1, h.policy.impressions)
        h.policy.initialBlock = AdBlockReason.DAILY_SHOW_LIMIT
        h.policy.bindBlock = AdBlockReason.DAILY_SHOW_LIMIT
        repeat(3) { h.controller.refresh() }
        h.controller.update(false, false)
        h.controller.update(true, true)
        assertEquals(NativeState.Loaded, h.controller.state)
        assertEquals(0, ad.releases)
        assertEquals(1, h.renders)
        assertEquals(1, h.loads.size)
        h.controller.destroy()
        assertEquals(1, h.policy.completions)
    }

    @Test fun `platform policy cannot release an exposed ad but privacy still can`() {
        val h = Host().start()
        val ad = Ad()
        h.loads.single().loaded(ad)
        h.loads.single().impression("network", "response")
        h.gate = NativeAvailability(failure = "native_platform_not_configured")
        h.canBind = false
        repeat(3) { h.controller.refresh() }
        h.controller.update(false, false)
        h.controller.update(true, true)
        assertEquals(NativeState.Loaded, h.controller.state)
        assertEquals(0, ad.releases)
        h.privacy = false
        h.controller.refresh()
        assertTrue(h.controller.state is NativeState.Failed)
        assertEquals(1, ad.releases)
    }

    @Test fun `platform disabled after selection cannot bind or emit SHOW_FAIL`() {
        val h = Host().start()
        h.canBind = false
        val ad = Ad()
        h.loads.single().loaded(ad)
        assertEquals(0, h.renders)
        assertEquals(1, ad.releases)
        assertEquals(0, h.policy.reservations)
        assertTrue(h.controller.state is NativeState.Failed)
        assertTrue(h.events.none { it.name == AdEventName.SHOW_FAIL })
    }

    @Test fun `new cycle keeps late actual exposure on the original policy attempt`() {
        val h = Host().start()
        val old = h.policy
        val oldCallback = h.loads.single()
        h.controller.update(false, true)
        h.policy = Policy()
        h.controller.update(true, true)
        oldCallback.impression("network", "old")
        assertEquals(1, old.impressions)
        assertEquals(0, h.policy.impressions)
        h.loads.last().loaded(Ad())
        h.loads.last().impression("network", "new")
        assertEquals(1, h.policy.impressions)
        assertEquals(1, old.completions)
    }

    @Test fun `blocked retries create a new opportunity instead of replaying terminal attempt`() {
        val h = Host()
        h.policy.initialBlock = AdBlockReason.DAILY_CLICK_LIMIT
        h.start()
        val old = h.policy
        h.policy = Policy()
        h.controller.retry()
        assertEquals(NativeState.Loading, h.controller.state)
        assertEquals(1, old.completions)
        assertEquals(1, h.loads.size)
    }

    @Test fun `released SDK click consumes usage without reviving page events`() {
        val h = Host().start()
        h.loads.single().loaded(Ad())
        val delivery = h.loads.single()
        delivery.clicked("network", "response")
        assertEquals(1, h.events.count { it.name == AdEventName.CLICK })
        assertEquals(1, h.policy.clicks) // The captured opportunity owns the live click.
        h.controller.destroy()
        delivery.clicked("network", "response")
        assertEquals(2, h.policy.clicks)
        assertEquals(1, h.events.count { it.name == AdEventName.CLICK })
    }

    @Test fun `old SDK click stays with old opportunity after a replacement cycle`() {
        val h = Host(NativeRetentionPolicy.DESTROY_ON_HIDE).start()
        h.loads.single().loaded(Ad())
        val old = h.policy
        val oldDelivery = h.loads.single()
        h.controller.update(false, true)
        h.policy = Policy()
        h.controller.update(true, true)
        oldDelivery.clicked("network", "old")
        assertEquals(1, old.clicks)
        assertEquals(0, h.policy.clicks)
        assertTrue(h.events.none { it.name == AdEventName.CLICK })
    }

    private class Policy : NativeCardPolicyAttempt {
        var initialBlock: AdBlockReason? = null
        var bindBlock: AdBlockReason? = null
        var reservations = 0
        var impressions = 0
        var clicks = 0
        var completions = 0
        override val hasImpression get() = impressions > 0
        override fun check() = initialBlock
        override fun reserve(): AdBlockReason? { reservations++; return bindBlock }
        override fun impression() { if (!hasImpression) impressions++ }
        override fun click() { clicks++ }
        override fun complete() { completions++ }
    }

    private class Ad : NativeAdHandle {
        var releases = 0
        override val isValid = true
        override val adSource = "network"
        override val responseId = "response"
        override val isTemplate = false
        override val expiresAtMillis: Long? = null
        override fun setCallbacks(callbacks: NativeCallbacks?) = Unit
        override fun pauseForRetention() = true
        override fun resumeAfterRetention() = true
        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("not used")
        override fun destroy() { releases++ }
    }

    private class Host(
        retention: NativeRetentionPolicy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE,
    ) {
        var policy = Policy()
        var availabilityCalls = 0
        var gate = NativeAvailability(ready = true)
        var canBind = true
        var privacy = true
        var cancellations = 0
        var renders = 0
        var beforeBind: () -> Unit = {}
        var onState: (NativeState) -> Unit = {}
        val loads = mutableListOf<NativeCallbacks>()
        val events = mutableListOf<AdEvent>()
        val controller = NativeCardController(
            availability = { availabilityCalls++; gate },
            canDisplay = { true },
            newSlot = { NativeSlot(ResolvedNativeRequest(AdPlatform.ADMOB, "unit", "home"), 1, { 1 },
                AdEventListener(events::add), AdRevenueListener.NONE) },
            load = { loads += it; NativeLoad { cancellations++ } },
            render = { _, current -> beforeBind(); if (current()) renders++ },
            removeView = {}, clock = { 0 }, dispatch = { it() }, interaction = { _, _ -> },
            onStateChanged = { onState(it) }, retentionPolicy = retention,
            policyAttemptFactory = { policy }, canBindAd = { canBind }, privacyAllowed = { privacy },
        )
        fun start(): Host { controller.update(true, true); return this }
    }
}
