package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.view.View
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.nativeads.*
import org.junit.Assert.*
import org.junit.Test

class NativeCardControllerTest {
    @Test fun `transient failures retry at two four eight seconds then stop until explicit retry`() {
        val h = Host().start()
        for ((index, delay) in listOf(2_000L, 4_000L, 8_000L).withIndex()) {
            h.loads.last().failed("native_bid_timeout")
            repeat(5) { h.controller.refresh() }
            assertEquals(1, h.tasks.size)
            h.advance(delay - 1)
            assertEquals(index + 1, h.loads.size)
            h.advance(1)
            assertEquals(index + 2, h.loads.size)
        }
        h.loads.last().failed("no_fill")
        h.advance(60_000)
        h.controller.refresh()
        assertEquals(4, h.loads.size)
        assertTrue(h.tasks.isEmpty())
        h.controller.retry()
        h.loads.last().failed("native_load_timeout")
        h.advance(2_000)
        assertEquals(6, h.loads.size)
        assertEquals(1, h.events.count { it.name == AdEventName.POSITION })
        val slotId = h.events.single { it.name == AdEventName.POSITION }.slotId
        assertTrue(h.events.filter { !it.isLoadEvent }.all { it.analyticsParameters()["ad_session_id"] == slotId })
        assertEquals(5, h.events.filter { it.name == AdEventName.SHOW_FAIL }.map { it.sessionId }.distinct().size)
    }

    @Test fun `automatic retry keeps one position and both current and late material revenues`() {
        val h = Host().start()
        val old = h.loads.single()
        old.failed("no_fill")
        val oldSession = h.events.single { it.name == AdEventName.SHOW_FAIL }.sessionId
        h.advance(2_000)
        val current = h.loads.last()
        current.loaded(Ad())
        val revenue = NativeRevenue(7, "USD", "test", "same-response", "exact")
        repeat(2) { current.paid(revenue) }
        repeat(2) { old.paid(revenue) }
        assertEquals(2, h.revenues.size)
        assertEquals(oldSession, h.revenues.last().sessionId)
        assertEquals(2, h.revenues.map { it.sessionId }.distinct().size)
        assertEquals(2, h.revenues.map { it.eventId }.distinct().size)
        val position = h.events.single { it.name == AdEventName.POSITION }
        assertEquals(listOf(position.slotId), h.events.filter { !it.isLoadEvent }
            .map { it.analyticsParameters()["ad_session_id"] }.distinct())
        assertEquals(h.revenues.map { it.sessionId },
            h.events.filter { it.name == AdEventName.IMPRESSION }.map { it.sessionId })
    }

    @Test fun `hidden destroyed and stale retry callbacks cannot start or replace a live retry`() {
        val h = Host().start()
        h.loads.last().failed("native_load_failed")
        val stale = h.tasks.keys.single()
        h.display = false
        h.controller.refresh()
        assertTrue(h.tasks.isEmpty())
        h.advance(10_000)
        h.display = true
        h.controller.refresh()
        stale.run()
        h.advance(2_000)
        assertEquals(2, h.loads.size)
        h.loads.last().loaded(Ad())
        h.advance(60_000)
        assertEquals(2, h.loads.size)
        h.controller.update(false, true)
        h.controller.update(true, true)
        h.loads.last().failed("native_load_failed")
        h.controller.destroy()
        h.advance(60_000)
        assertTrue(h.tasks.isEmpty())
        assertEquals(3, h.loads.size)
    }

    @Test fun `initial flags and repeated notifications never create extra loads`() {
        val h = Host()
        h.controller.update(false, true)
        h.controller.update(true, false)
        assertEquals(0, h.loads.size)
        h.controller.update(true, true)
        repeat(5) { h.controller.refresh(); h.controller.update(true, true) }
        assertEquals(1, h.loads.size)
        assertEquals(1, h.events.count { it.name == AdEventName.POSITION })
    }

    @Test fun `inactive cycle and late callback cannot change the next cycle`() {
        val h = Host().start()
        val old = h.loads.single()
        h.controller.update(false, true)
        h.controller.update(true, true)
        val staleAd = Ad()
        old.loaded(staleAd)
        assertEquals(1, staleAd.releases)
        assertEquals(NativeState.Loading, h.controller.state)
        val current = Ad()
        h.loads.last().loaded(current)
        assertEquals(NativeState.Loaded, h.controller.state)
        assertEquals(1, h.renders)
        assertEquals(2, h.events.count { it.name == AdEventName.POSITION })
        assertEquals(listOf("cancelled", "filled"), h.events.filter { it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) }.map { it.result })
    }

    @Test fun `destroy is irreversible and clears the callbacks reaching the page`() {
        val h = Host().start()
        h.controller.destroy()
        h.controller.destroy()
        h.controller.update(true, true)
        h.controller.retry()
        val ad = Ad()
        h.loads.single().loaded(ad)
        h.loads.single().failed("late")
        assertEquals(1, ad.releases)
        assertEquals(NativeState.Destroyed, h.controller.state)
        assertEquals(1, h.loads.size)
        assertEquals(1, h.events.count { it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) })
    }

    @Test fun `retry only works for an eligible failed cycle and is not queued`() {
        val h = Host().start()
        h.loads.last().failed("no_fill", "3")
        h.display = false
        h.controller.retry()
        h.display = true
        h.controller.refresh()
        assertEquals(1, h.loads.size)
        h.controller.retry()
        h.controller.retry()
        assertEquals(2, h.loads.size)
        assertEquals(2, h.events.filter { it.name == AdEventName.LOAD }.map { it.requestId }.distinct().size)
    }

    @Test fun `waiting and failed selected provider never loads on aggregate readiness`() {
        val h = Host()
        h.gate = NativeAvailability()
        h.start()
        assertTrue(h.loads.isEmpty())
        h.gate = NativeAvailability(failure = "native_platform_initialization_failed")
        h.controller.refresh()
        assertEquals(NativeState.Failed("native_platform_initialization_failed"), h.controller.state)
        assertTrue(h.loads.isEmpty())
        val allowed = Host()
        allowed.gate = NativeAvailability()
        allowed.start()
        allowed.gate = NativeAvailability(ready = true)
        allowed.controller.refresh()
        assertEquals(1, allowed.loads.size)
    }

    @Test fun `hidden media is released and resumes in the same slot without refreshing while visible`() {
        val h = Host().start()
        val ad = Ad()
        h.loads.single().loaded(ad)
        repeat(4) { h.controller.refresh() }
        assertEquals(1, h.loads.size)
        h.display = false
        h.controller.refresh()
        assertEquals(1, ad.releases)
        h.display = true
        h.controller.refresh()
        assertEquals(2, h.loads.size)
        assertEquals(1, h.events.count { it.name == AdEventName.POSITION })
    }

    @Test fun `destroy reentered from loading notification prevents the SDK call`() {
        val h = Host()
        h.onState = { if (it == NativeState.Loading) h.controller.destroy() }
        h.start()
        assertTrue(h.loads.isEmpty())
        assertEquals(NativeState.Destroyed, h.controller.state)
    }

    @Test fun `permission revoked by load result callback prevents mounting`() {
        val h = Host().start()
        h.onEvent = { if (it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL)) h.gate = NativeAvailability(failure = "consent_not_obtained") }
        val ad = Ad()
        h.loads.single().loaded(ad)
        assertEquals(0, h.renders)
        assertEquals(1, ad.releases)
    }

    @Test fun `render error releases resources and requires explicit retry`() {
        val h = Host().start()
        h.renderError = "unsupported_native_render_mode"
        val ad = Ad()
        h.loads.single().loaded(ad)
        h.controller.refresh()
        assertEquals(1, ad.releases)
        assertEquals(NativeState.Failed("unsupported_native_render_mode"), h.controller.state)
        assertEquals(1, h.loads.size)
        assertEquals(1, h.events.count { it.name == AdEventName.SHOW_FAIL })
        assertEquals("filled", h.events.single { it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) }.result)
        assertTrue(h.tasks.isEmpty()) // Unsupported layouts cannot be repaired by requesting more ads.
    }

    @Test fun `unexpected render exception reports a stable failure`() {
        val h = Host().start()
        h.renderError = "provider implementation detail"
        h.loads.single().loaded(Ad())
        assertEquals(NativeState.Failed("native_render_failed"), h.controller.state)
    }

    @Test fun `expired waiting object is discarded and a new qualified request starts`() {
        val h = Host().start()
        val ad = Ad(expiry = 50)
        h.now = 50
        h.loads.single().loaded(ad)
        assertEquals(1, ad.releases)
        assertEquals(0, h.renders)
        assertEquals(2, h.loads.size)
    }

    @Test fun `template close before exposure ends opportunity as cancelled and never dismisses`() {
        val h = Host().start()
        val callbacks = h.loads.single()
        callbacks.loaded(Ad())
        callbacks.closed()
        callbacks.closed()
        callbacks.impression("test", "response")
        callbacks.paid(NativeRevenue(0, "USD", "test", "response", "exact"))
        assertEquals(1, h.events.count { it.name == AdEventName.SHOW_FAIL })
        val failed = h.events.single { it.name == AdEventName.SHOW_FAIL }
        assertEquals("cancelled", failed.reason)
        assertEquals(h.events.first { it.name == AdEventName.POSITION }.sessionId, failed.sessionId)
        assertTrue(h.events.none { it.name == AdEventName.DISMISS })
        assertEquals(1, h.events.count { it.name == AdEventName.IMPRESSION })
        assertEquals(failed.sessionId, h.revenues.single().sessionId)
    }

    @Test fun `confirmed template close never resurrects in the same cycle`() {
        val h = Host().start()
        val callbacks = h.loads.single()
        callbacks.loaded(Ad())
        callbacks.impression("test", "ad-a")
        callbacks.closed()
        repeat(3) { h.controller.refresh(); h.controller.retry(); h.controller.update(true, true) }
        assertEquals(1, h.loads.size)
        assertEquals(1, h.events.count { it.name == AdEventName.DISMISS })
        assertEquals(0, h.events.count { it.name == AdEventName.SHOW_FAIL })
    }

    @Test fun `paid survives disposal and throwing listeners without being attributed to B`() {
        val h = Host().start()
        val a = h.loads.single()
        val requestA = h.events.single { it.name == AdEventName.LOAD }.requestId
        h.controller.update(false, true)
        h.controller.update(true, true)
        h.onEvent = { if (it.name == AdEventName.IMPRESSION) error("host event failed") }
        a.paid(NativeRevenue(0, "USD", "test", "ad-a", "PRECISE"))
        a.paid(NativeRevenue(0, "USD", "test", "ad-a", "PRECISE"))
        assertEquals(1, h.revenues.size)
        assertEquals(0L, h.revenues.single().valueMicros)
        assertEquals(requestA, h.events.single { it.name == AdEventName.IMPRESSION }.requestId)
        assertEquals(NativeState.Loading, h.controller.state)
    }

    @Test fun `impression deduplication does not swallow valid repeat clicks or create fake close`() {
        val h = Host().start()
        val callbacks = h.loads.single()
        callbacks.loaded(Ad())
        repeat(2) { callbacks.impression("test", "id"); callbacks.clicked("test", "id") }
        callbacks.overlayOpened()
        callbacks.overlayClosed()
        assertEquals(0, h.events.count { it.name == AdEventName.IMPRESSION })
        repeat(2) { callbacks.paid(NativeRevenue(7, "USD", "test", "id", "exact")) }
        h.controller.destroy()
        assertEquals(1, h.events.count { it.name == AdEventName.IMPRESSION })
        assertEquals(2, h.events.count { it.name == AdEventName.CLICK })
        assertEquals(0, h.events.count { it.name == AdEventName.DISMISS || it.name == AdEventName.SHOW_FAIL })
        assertTrue(h.events.all { it.mediationMode == AdMediationMode.ADMOB })
        assertTrue(h.events.all { it.position == "home" })
    }

    @Test fun `independent slots do not release or complete each other`() {
        val a = Host().start()
        val b = Host(position = "details").start()
        a.controller.destroy()
        val ad = Ad()
        b.loads.single().loaded(ad)
        assertEquals(NativeState.Loaded, b.controller.state)
        assertEquals(0, ad.releases)
        assertNotEquals(a.events.first().slotId, b.events.first().slotId)
    }

    @Test fun `cancel result reentry cannot overwrite or duplicate the new cycle`() {
        val h = Host().start()
        var restarted = false
        h.onEvent = {
            if (!restarted && it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) && it.result == "cancelled") {
                restarted = true
                h.display = true
                h.controller.update(false, true)
                h.controller.update(true, true)
            }
        }
        h.display = false
        h.controller.refresh()
        assertEquals(NativeState.Loading, h.controller.state)
        h.controller.refresh()
        assertEquals(2, h.loads.size)
        h.loads.last().loaded(Ad())
        assertEquals(NativeState.Loaded, h.controller.state)
    }

    @Test fun `failure event can retry immediately without being overwritten by old failure`() {
        val h = Host().start()
        h.onEvent = { if (it.name == AdEventName.SHOW_FAIL) h.controller.retry() }
        h.loads.single().failed("no_fill")
        assertEquals(2, h.loads.size)
        assertEquals(NativeState.Loading, h.controller.state)
    }

    @Test fun `paid before impression validates amount and deduplicates missing response identity`() {
        val h = Host().start()
        val callbacks = h.loads.single()
        callbacks.loaded(Ad())
        callbacks.paid(NativeRevenue(-1, "USD", null, null, null))
        callbacks.paid(NativeRevenue(1, "", null, null, null))
        repeat(2) { callbacks.paid(NativeRevenue(0, "USD", null, null, null)) }
        assertEquals(1, h.revenues.size)
        assertEquals(1, h.events.count { it.name == AdEventName.IMPRESSION })
        callbacks.impression(null, null)
        assertEquals(1, h.events.count { it.name == AdEventName.IMPRESSION })
        assertEquals(h.revenues.single().sessionId, h.events.single { it.name == AdEventName.IMPRESSION }.sessionId)
    }

    @Test fun `TopOn revenue keeps actual mode and repeated failed attempts each terminate once`() {
        val h = Host(AdPlatform.TOPON).start()
        repeat(2) {
            h.loads.last().failed("no_fill")
            h.controller.retry()
        }
        h.loads.last().loaded(Ad())
        h.loads.last().paid(NativeRevenue(1, "USD", "test", "show-a", "exact", topOnAdInfo = Any()))
        assertEquals(2, h.events.count { it.name == AdEventName.SHOW_FAIL })
        assertEquals(3, h.events.count { it.name in setOf(AdEventName.LOADED, AdEventName.LOAD_FAIL) })
        assertEquals(1, h.revenues.size)
        assertTrue(h.events.all { it.mediationMode == AdMediationMode.TOPON })
        assertEquals(AdMediationMode.TOPON, h.revenues.single().mediationMode)
    }

    @Test fun `retention preserves object render and event identity without new demand`() {
        val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
        val ad = Ad(expiry = 50, retainable = true)
        h.loads.single().loaded(ad)
        val requestId = h.events.single { it.name == AdEventName.LOAD }.requestId
        h.loads.single().impression("test", "response")
        h.display = false
        h.controller.refresh()
        h.controller.update(false, false)
        repeat(4) { h.controller.refresh() }
        assertEquals(NativeState.Loaded, h.controller.state)
        assertEquals(1, ad.pauses)
        assertEquals(1, h.cancellations)
        assertEquals(0, ad.releases)
        assertEquals(0, h.removes)
        // 已绑定对象不受未展示 TTL 影响，恢复依来源语义决定。
        h.now = 100
        h.display = true
        h.controller.update(true, true)
        repeat(4) { h.controller.refresh() }
        assertEquals(1, ad.resumes)
        assertEquals(1, h.renders)
        assertEquals(1, h.loads.size)
        ad.listener!!.paid(NativeRevenue(1, "USD", "test", "response", "PRECISE"))
        assertEquals(requestId, h.events.single { it.name == AdEventName.IMPRESSION }.requestId)
        assertEquals(1, h.events.count { it.name == AdEventName.POSITION })
        h.controller.destroy()
        h.controller.destroy()
        assertEquals(1, ad.releases)
        assertEquals(1, h.removes)
    }

    @Test fun `consumed inventory invalidity does not override bound source retention semantics`() {
        for ((active, visible) in listOf(false to true, true to false)) {
            val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
            val ad = Ad(retainable = true)
            h.loads.single().loaded(ad)
            // 模拟库存消费后 isValid=false；已绑定对象仍由来源支持暂停和恢复。
            ad.valid = false
            h.controller.update(active, visible)
            assertTrue(h.controller.isRetained)
            assertEquals(1, ad.pauses)
            assertEquals(1, h.cancellations)
            assertEquals(0, ad.releases)
            h.controller.update(true, true)
            assertEquals(NativeState.Loaded, h.controller.state)
            assertFalse(h.controller.isRetained)
            assertEquals(1, ad.resumes)
            assertEquals(1, h.renders)
            assertEquals(1, h.loads.size)
            assertEquals(0, ad.releases)
            assertTrue(h.fallbacks.isEmpty())
            h.controller.destroy()
            assertEquals(1, ad.releases)
        }
    }

    @Test fun `unverified or throwing pause releases instead of claiming retention`() {
        for (throws in listOf(false, true)) {
            val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
            val ad = Ad().also { it.pauseThrows = throws }
            h.loads.single().loaded(ad)
            h.controller.update(false, true)
            assertEquals(1, ad.releases)
            assertEquals(NativeState.Idle, h.controller.state)
            assertEquals(listOf("native_retention_pause_unsupported"), h.fallbacks)
            h.controller.update(true, true)
            assertEquals(2, h.loads.size)
            h.controller.update(false, true)
            assertEquals("native_inactive", h.events.last { it.name == AdEventName.SHOW_FAIL }.reason)
        }
    }

    @Test fun `failed resume releases and starts one new eligible acquisition`() {
        val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
        val ad = Ad(retainable = true)
        h.loads.single().loaded(ad)
        h.controller.update(false, true)
        ad.resumeAllowed = false
        h.controller.update(true, true)
        repeat(3) { h.controller.refresh() }
        assertEquals(1, ad.releases)
        assertEquals(2, h.loads.size)
        assertEquals(listOf("native_retention_resume_unsupported"), h.fallbacks)
    }

    @Test fun `retention waiting cancellation invalidates late delivery before next request`() {
        val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
        val old = h.loads.single()
        h.controller.update(false, false)
        assertEquals(1, h.cancellations)
        h.controller.update(true, true)
        val stale = Ad(retainable = true)
        old.loaded(stale)
        assertEquals(1, stale.releases)
        assertEquals(0, stale.pauses)
        assertEquals(NativeState.Loading, h.controller.state)
    }

    @Test fun `retained consent withdrawal releases old binding`() {
        val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
        val ad = Ad(retainable = true)
        h.loads.single().loaded(ad)
        h.controller.update(false, true)
        h.gate = NativeAvailability(failure = "consent_not_obtained")
        h.controller.refresh()
        assertEquals(1, ad.releases)
        assertEquals(NativeState.Failed("consent_not_obtained"), h.controller.state)
        assertEquals(1, h.loads.size)
    }

    @Test fun `synchronous loaded and hidden callback still cancels returned operation`() {
        val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE)
        val ad = Ad(retainable = true)
        h.duringLoad = { it.loaded(ad) }
        h.onState = { if (it == NativeState.Loaded) h.controller.update(false, false) }
        h.start()
        assertEquals(NativeState.Loaded, h.controller.state)
        assertEquals(1, h.cancellations)
        assertEquals(1, ad.pauses)
        assertEquals(0, ad.releases)
    }

    @Test fun `hidden retained clicks are suppressed while original late paid survives final cleanup`() {
        val h = Host(policy = NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE).start()
        val ad = Ad(retainable = true)
        h.loads.single().loaded(ad)
        val callbacks = ad.listener!!
        h.controller.update(false, false)
        callbacks.clicked("test", "response")
        assertEquals(0, h.events.count { it.name == AdEventName.CLICK })
        h.controller.destroy()
        callbacks.paid(NativeRevenue(0, "USD", "test", "response", "PRECISE"))
        assertEquals(1, h.revenues.size)
        assertEquals(NativeState.Destroyed, h.controller.state)
    }

    private class Ad(private val expiry: Long? = null, var retainable: Boolean = false) : NativeAdHandle {
        var releases = 0
        var pauses = 0
        var resumes = 0
        var valid = true
        var resumeAllowed = true
        var pauseThrows = false
        var listener: NativeCallbacks? = null
        override val isValid get() = valid
        override fun setCallbacks(callbacks: NativeCallbacks?) { this.listener = callbacks }
        override fun pauseForRetention(): Boolean { pauses++; if (pauseThrows) error("pause failed"); return retainable }
        override fun resumeAfterRetention(): Boolean { resumes++; return retainable && resumeAllowed }
        override val adSource = "test"
        override val responseId = "response"
        override val isTemplate = false
        override val expiresAtMillis get() = expiry
        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("not used")
        override fun destroy() { releases++ }
    }

    private class Host(platform: AdPlatform = AdPlatform.ADMOB,
        policy: NativeRetentionPolicy = NativeRetentionPolicy.DESTROY_ON_HIDE, position: String = "home") {
        var gate = NativeAvailability(ready = true)
        var display = true
        var now = 0L
        val tasks = linkedMapOf<Runnable, Long>()
        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                val next = tasks.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
                now = next.value
                tasks.remove(next.key)
                next.key.run()
            }
            now = end
        }
        var renders = 0
        var cancellations = 0
        var removes = 0
        var duringLoad: ((NativeCallbacks) -> Unit)? = null
        val fallbacks = mutableListOf<String>()
        var renderError: String? = null
        val loads = mutableListOf<NativeCallbacks>()
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        var onState: (NativeState) -> Unit = {}
        var onEvent: (AdEvent) -> Unit = {}
        val controller = NativeCardController(
            availability = { gate }, canDisplay = { display },
            newSlot = { NativeSlot(ResolvedNativeRequest(platform, "native-id", position), 1, { 1 },
                AdEventListener { events += it; onEvent(it) }, AdRevenueListener { revenues += it }) },
            load = { loads += it; duringLoad?.invoke(it); NativeLoad { cancellations++ } },
            render = { _, current -> renderError?.let { error(it) }; if (current()) renders++ }, removeView = { removes++ }, clock = { now },
            dispatch = { it() }, interaction = { _, _ -> }, onStateChanged = { onState(it) },
            retentionPolicy = policy, onRetentionFallback = { fallbacks += it },
            schedule = { action, delay -> tasks[action] = now + delay }, unschedule = { tasks.remove(it) },
        )
        fun start(): Host { controller.update(true, true); return this }
    }
}
