package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.view.View
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.nativeads.*
import org.junit.Assert.*
import org.junit.Test

class NativeAuctionTest {
    @Test fun `selection reads current actual object price once and ignores duplicate completion`() {
        val h = Host().start()
        val google = Ad(AdPlatform.ADMOB, 100.0)
        val topon = Ad(AdPlatform.TOPON, 2.0)
        h.loaded(google)
        google.bidPriceUsd = 1.0
        h.loaded(topon)
        h.loaded(topon)
        h.listeners.getValue(AdPlatform.ADMOB).failed("no_fill")
        assertSame(topon, h.rendered)
        val bid = h.events.single { it.name == AdEventName.BID_RESULT }
        assertEquals(1.0, bid.admobValue!!, 0.0)
        assertEquals(2.0, bid.topOnValue!!, 0.0)
        assertEquals(1, google.releases)
        assertEquals(0, topon.releases)
    }

    @Test fun `inventory acquisition emits no page network events but keeps winner and revenue identity`() {
        val h = Host(recordLoadEvents = false).start()
        val ad = Ad(AdPlatform.TOPON, 2.0)
        h.loaded(ad)
        h.loaded(Ad(AdPlatform.ADMOB, 1.0))
        ad.listener!!.impression("Pangle", "inventory-response")
        ad.listener!!.paid(revenue("inventory-response"))
        assertFalse(h.events.any { it.name == AdEventName.LOAD_REQUEST || it.name == AdEventName.LOAD_RESULT })
        val bid = h.events.single { it.name == AdEventName.BID_RESULT }
        val impression = h.events.single { it.name == AdEventName.IMPRESSION }
        val paid = h.events.single { it.name == AdEventName.PAID }
        assertEquals(AdPlatform.TOPON, bid.winnerPlatform)
        assertEquals(impression.requestId, paid.requestId)
        assertEquals(impression.sessionId, paid.sessionId)
        assertEquals("topon-id", paid.adUnitId)
        assertEquals(0L, h.revenues.single().valueMicros)
    }

    @Test fun `winner keeps inventory demand while displayed and hide releases it`() {
        val h = Host().start()
        h.loaded(Ad(AdPlatform.ADMOB, 2.0))
        h.loaded(Ad(AdPlatform.TOPON, 1.0))
        assertEquals(NativeState.Loaded, h.controller.state)
        assertEquals(1, h.cancelCount)
        h.controller.update(true, false)
        assertEquals(2, h.cancelCount)
    }

    @Test fun `one page request compares real objects and attributes all winner events to TopOn`() {
        val h = Host().start()
        val google = Ad(AdPlatform.ADMOB, 0.002)
        val topon = Ad(AdPlatform.TOPON, 0.005)
        h.loaded(google)
        assertNull(h.rendered)
        h.loaded(topon)
        assertSame(topon, h.rendered)
        assertEquals(1, google.releases)
        val bid = h.events.single { it.name == AdEventName.BID_RESULT }
        assertEquals(true, bid.admobAvailable)
        assertEquals(true, bid.topOnAvailable)
        assertEquals(AdPlatform.TOPON, bid.winnerPlatform)
        assertEquals(0.005, bid.winningValue!!, 0.0)
        h.listeners.getValue(AdPlatform.ADMOB).impression("loser", "wrong")
        h.listeners.getValue(AdPlatform.TOPON).impression("Pangle", "topon-show")
        h.listeners.getValue(AdPlatform.TOPON).clicked("Pangle", "topon-show")
        h.listeners.getValue(AdPlatform.TOPON).paid(revenue())
        val impression = h.events.single { it.name == AdEventName.IMPRESSION }
        assertEquals("topon-id", impression.adUnitId)
        assertEquals(AdPlatform.TOPON, impression.platform)
        assertEquals(AdMediationMode.BIDDING, impression.mediationMode)
        assertEquals(impression.requestId, h.events.single {
            it.name == AdEventName.LOAD_REQUEST && it.platform == AdPlatform.TOPON
        }.requestId)
        assertEquals(2, h.events.filter { it.name == AdEventName.LOAD_REQUEST }.map { it.requestId }.distinct().size)
        assertEquals(2, h.events.count { it.name == AdEventName.LOAD_RESULT })
        h.controller.destroy()
        assertEquals(1, topon.releases)
        // Legitimate late revenue remains linked to the original selected ad, never to a new page.
        h.listeners.getValue(AdPlatform.TOPON).paid(revenue("late-paid"))
        assertEquals(2, h.revenues.size)
        assertTrue(h.revenues.all { it.platform == AdPlatform.TOPON && it.placementId == "topon-id" })
    }

    @Test fun `only the displayed winner suppresses app open and late close does not poison the next return`() {
        for (platform in listOf(AdPlatform.ADMOB, AdPlatform.TOPON)) {
            val h = Host().start()
            val interactions = NativeInteractionState { h.now }
            h.onInteraction = interactions::interact
            val winner = Ad(platform, 2.0)
            val loser = Ad(if (platform == AdPlatform.ADMOB) AdPlatform.TOPON else AdPlatform.ADMOB, 1.0)
            h.loaded(loser)
            val loserCallbacks = h.listeners.getValue(loser.platform)
            loserCallbacks.clicked("loser", "ignored")
            loserCallbacks.overlayOpened()
            assertFalse(interactions.blocked())
            h.loaded(winner)
            loserCallbacks.clicked("loser", "ignored")
            loserCallbacks.overlayOpened()
            assertFalse(interactions.blocked())

            val displayedCallbacks = checkNotNull(winner.listener)
            displayedCallbacks.clicked("winner", "shown")
            displayedCallbacks.overlayOpened()
            assertTrue(interactions.blocked())
            interactions.background()
            h.controller.destroy()
            displayedCallbacks.overlayClosed()
            h.now = 3_600_000
            interactions.foreground()
            assertTrue(interactions.blocked())
            assertEquals(1, h.events.count { it.name == AdEventName.CLICK })
            assertEquals(0, h.events.count { it.name == AdEventName.DISMISS })

            interactions.background()
            interactions.foreground()
            displayedCallbacks.clicked("winner", "stale")
            displayedCallbacks.overlayOpened()
            displayedCallbacks.overlayClosed()
            loserCallbacks.overlayClosed()
            assertFalse(interactions.blocked())
            assertEquals(1, h.events.count { it.name == AdEventName.CLICK })
        }
    }

    @Test fun `buffered winner revenue survives page destruction during its first impression`() {
        val h = Host().start()
        val winner = Ad(AdPlatform.TOPON, 2.0)
        val paid = revenue("winner-paid")
        h.loaded(winner)
        h.listeners.getValue(AdPlatform.TOPON).paid(paid)
        h.onRender = { it.listener!!.impression("Pangle", "winner-paid") }
        h.onEvent = { if (it.name == AdEventName.IMPRESSION) h.controller.destroy() }

        h.loaded(Ad(AdPlatform.ADMOB, 1.0))

        assertEquals(NativeState.Destroyed, h.controller.state)
        assertNull(h.rendered)
        assertEquals(1, winner.releases)
        val impression = h.events.single { it.name == AdEventName.IMPRESSION }
        val event = h.events.single { it.name == AdEventName.PAID }
        assertEquals(impression.sessionId, event.sessionId)
        assertEquals(impression.requestId, event.requestId)
        assertEquals(0L, h.revenues.single().valueMicros)
        h.listeners.getValue(AdPlatform.TOPON).paid(paid)
        assertEquals(1, h.events.count { it.name == AdEventName.PAID })
        assertEquals(1, h.revenues.size)
        assertEquals(0, h.events.count { it.name == AdEventName.SHOW_FAIL })
    }

    @Test fun `deadline uses loaded unknown-price candidate and destroys late arrivals`() {
        val h = Host().start()
        val available = Ad(AdPlatform.TOPON, null)
        h.loaded(available)
        h.expire()
        assertSame(available, h.rendered)
        val bid = h.events.single { it.name == AdEventName.BID_RESULT }
        assertEquals(false, bid.admobAvailable)
        assertEquals(true, bid.topOnAvailable)
        assertEquals(false, bid.topOnPriceAvailable)
        assertNull(bid.winningValue)
        val late = Ad(AdPlatform.ADMOB, 100.0)
        h.loaded(late)
        assertEquals(1, late.releases)
        assertSame(available, h.rendered)
        assertEquals(1, h.events.count { it.name == AdEventName.BID_RESULT })
    }

    @Test fun `failure does not beat a filled zero price and ties are deterministic`() {
        val failed = Host().start()
        failed.listeners.getValue(AdPlatform.ADMOB).failed("no_fill")
        val filled = Ad(AdPlatform.TOPON, 0.0)
        failed.loaded(filled)
        assertSame(filled, failed.rendered)
        listOf(0.0, null).forEach { price ->
            val tie = Host().start()
            val google = Ad(AdPlatform.ADMOB, price)
            tie.loaded(Ad(AdPlatform.TOPON, price))
            tie.loaded(google)
            assertSame(google, tie.rendered)
        }
    }

    @Test fun `cancel and retry isolate timers callbacks and owned candidates`() {
        val h = Host().start()
        val oldListeners = h.listeners.toMap()
        val pending = Ad(AdPlatform.ADMOB, 2.0)
        h.loaded(pending)
        h.controller.update(false, true)
        assertEquals(1, pending.releases)
        assertNull(h.timer)
        h.controller.update(true, true)
        val stale = Ad(AdPlatform.TOPON, 5.0)
        oldListeners.getValue(AdPlatform.TOPON).loaded(stale)
        assertEquals(1, stale.releases)
        assertNull(h.rendered)
        h.listeners.getValue(AdPlatform.ADMOB).failed("no_fill")
        h.listeners.getValue(AdPlatform.TOPON).failed("no_fill")
        assertEquals(NativeState.Failed("no_fill"), h.controller.state)
        h.controller.retry()
        assertEquals(6, h.loadCount)
    }

    @Test fun `synchronous SDK completions do not finish before the other platform starts`() {
        val h = Host()
        h.synchronous = true
        h.start()
        assertEquals(2, h.loadCount)
        assertEquals(AdPlatform.TOPON, h.rendered?.platform)
        assertNull(h.timer)
        assertEquals(1, h.cancelCount)
        h.controller.destroy()
        assertEquals(2, h.cancelCount)
    }

    @Test fun `initializing competitor joins the same bounded auction once ready`() {
        val h = Host()
        h.gates[AdPlatform.TOPON] = NativeAvailability()
        h.start()
        h.loaded(Ad(AdPlatform.ADMOB, 1.0))
        assertEquals(1, h.loadCount)
        assertNull(h.rendered)
        h.gates[AdPlatform.TOPON] = NativeAvailability(ready = true)
        h.observer?.invoke()
        h.loaded(Ad(AdPlatform.TOPON, 2.0))
        assertEquals(2, h.loadCount)
        assertEquals(AdPlatform.TOPON, h.rendered?.platform)
        assertNull(h.observer)
    }

    @Test fun `no fill timeout expired inventory and unavailable providers settle without rendering`() {
        val timeout = Host().start()
        timeout.expire()
        assertEquals(NativeState.Failed("native_bid_timeout"), timeout.controller.state)
        assertEquals(2, timeout.events.count { it.name == AdEventName.LOAD_RESULT })
        val expired = Host().start()
        val ad = Ad(AdPlatform.ADMOB, 10.0, 0L)
        expired.loaded(ad)
        expired.listeners.getValue(AdPlatform.TOPON).failed("no_fill")
        assertEquals(1, ad.releases)
        assertNull(expired.rendered)
        val unavailable = Host()
        unavailable.gates.keys.toList().forEach { unavailable.gates[it] = NativeAvailability(failure = "not_configured") }
        unavailable.start()
        assertEquals(0, unavailable.loadCount)
        assertTrue(unavailable.controller.state is NativeState.Failed)
        assertNull(unavailable.rendered)
    }

    @Test fun `reentry from bid event cannot attach an ad to a destroyed page`() {
        val h = Host()
        h.onEvent = { if (it.name == AdEventName.BID_RESULT) h.controller.destroy() }
        h.start()
        val first = Ad(AdPlatform.ADMOB, 1.0)
        val second = Ad(AdPlatform.TOPON, 2.0)
        h.loaded(first)
        h.loaded(second)
        assertNull(h.rendered)
        assertEquals(1, first.releases)
        assertEquals(1, second.releases)
        assertEquals(NativeState.Destroyed, h.controller.state)
    }

    @Test fun `destroy from candidate load request prevents both SDK loads`() {
        val h = Host()
        h.onEvent = { if (it.name == AdEventName.LOAD_REQUEST) h.controller.destroy() }
        h.start()
        assertEquals(0, h.loadCount)
        assertEquals(NativeState.Destroyed, h.controller.state)
        assertNull(h.timer)
    }

    @Test fun `business request can select both sources or use the existing single-platform form`() {
        val request = ResolvedNativeRequest(position = "home", admobAdUnitId = "google", topOnPlacementId = "topon")
        assertNull(request.failureReason())
        assertTrue(request.isBidding)
        assertEquals(listOf("google", "topon"), request.candidates().map { it.adUnitId })
        assertNull(ResolvedNativeRequest(AdPlatform.TOPON, "topon", "home").failureReason())
        assertFalse(ResolvedNativeRequest(position = "home", topOnPlacementId = "topon").isBidding)
        assertNotNull(request.copy(admobAdUnitId = " ").failureReason())
        assertNotNull(request.copy(platform = AdPlatform.ADMOB, adUnitId = "google").failureReason())
        assertNotNull(request.copy(bidTimeoutMillis = 0).failureReason())
    }

    @Test fun `unrenderable template cannot displace a usable self-rendered candidate`() {
        val h = Host(allowTemplate = false).start()
        val template = Ad(AdPlatform.TOPON, 100.0, isTemplate = true)
        val google = Ad(AdPlatform.ADMOB, 1.0)
        h.loaded(template)
        h.loaded(google)
        assertSame(google, h.rendered)
        assertEquals(1, template.releases)
        assertEquals(false, h.events.single { it.name == AdEventName.BID_RESULT }.topOnAvailable)
    }

    @Test fun `loser survives previous page destruction and is exclusively rebound to the next auction`() {
        val cache = NativeCandidateCache({ 0 }, { _, _ -> }, {})
        val first = Host(cache = cache, position = "first").start()
        val google = Ad(AdPlatform.ADMOB, 1.0)
        first.loaded(google)
        first.loaded(Ad(AdPlatform.TOPON, 2.0))
        first.controller.destroy()
        assertEquals(0, google.releases)
        assertNull(google.listener)
        val second = Host(cache = cache, position = "second").start()
        assertEquals(1, second.loadCount) // Only TopOn needs a network load.
        val simultaneous = Host(cache = cache).start()
        assertEquals(2, simultaneous.loadCount) // No shared ad between cards.
        second.listeners.getValue(AdPlatform.TOPON).failed("no_fill")
        assertSame(google, second.rendered)
        google.listener!!.impression("cached-google", "original-response")
        google.listener!!.paid(NativeRevenue(0, "USD", "cached-google", "original-response", "exact"))
        assertFalse(first.events.any { it.name == AdEventName.IMPRESSION })
        assertEquals("second", second.events.single { it.name == AdEventName.IMPRESSION }.position)
        assertEquals(1, second.revenues.size)
        second.controller.destroy()
        simultaneous.controller.destroy()
        cache.clear()
        assertEquals(1, google.releases)
    }

    @Test fun `late candidate from before consent revocation cannot refill restored inventory`() {
        val cache = NativeCandidateCache({ 0 }, { _, _ -> }, {})
        val oldPage = Host(cache = cache).start()
        oldPage.controller.destroy()
        cache.clear()

        val restoredPage = Host(cache = cache).start()
        val stale = Ad(AdPlatform.ADMOB, 1.0)
        oldPage.loaded(stale)
        assertEquals(1, stale.releases)
        assertNull(cache.take(oldPage.request.candidates().first { it.platform == AdPlatform.ADMOB }, 320, true))

        val fresh = Ad(AdPlatform.TOPON, 2.0)
        restoredPage.loaded(fresh)
        restoredPage.listeners.getValue(AdPlatform.ADMOB).failed("no_fill")
        assertSame(fresh, restoredPage.rendered)
        restoredPage.controller.destroy()
        assertEquals(1, fresh.releases)
    }

    @Test fun `shared inventory enforces SDK validity expiry template dimensions and bounded ownership`() {
        var now = 0L
        var timer: Runnable? = null
        val cache = NativeCandidateCache({ now }, { action, _ -> timer = action }, { timer = null })
        val request = ResolvedNativeRequest(AdPlatform.TOPON, "topon", "home", 2f)
        val template = Ad(AdPlatform.TOPON, 1.0, isTemplate = true)
        cache.put(request, 320, template)
        assertNull(cache.take(request, 640, true))
        assertNull(cache.take(request, 320, false))
        assertSame(template, cache.take(request, 320, true))
        assertNull(cache.take(request, 320, true))
        cache.put(request, 320, template)
        template.isValid = false
        assertNull(cache.take(request, 320, true))
        assertEquals(1, template.releases)
        val expired = Ad(AdPlatform.ADMOB, 1.0, expiresAtMillis = 50)
        cache.put(ResolvedNativeRequest(AdPlatform.ADMOB, "google", "home"), 320, expired)
        now = 50
        timer!!.run()
        assertEquals(1, expired.releases)
        assertNull(timer)
        val ads = (1..5).map { Ad(AdPlatform.TOPON, 1.0) }
        ads.forEachIndexed { index, ad -> cache.put(request.copy(adUnitId = "$index"), 320, ad) }
        assertEquals(1, ads.first().releases)
        cache.clear()
        assertTrue(ads.all { it.releases == 1 })
    }

    @Test fun `reentry and key changes retain the first TopOn retention deadline`() {
        var now = 0L
        val cache = NativeCandidateCache({ now }, { _, _ -> }, {})
        val first = ResolvedNativeRequest(AdPlatform.TOPON, "first", "home")
        val second = first.copy(adUnitId = "second")
        val ad = Ad(AdPlatform.TOPON, 1.0)
        cache.put(first, 320, ad)
        now = 3_000_000L
        assertSame(ad, cache.take(first, 320, true))
        cache.put(second, 320, ad)
        cache.put(first, 320, ad)
        assertNull(cache.take(second, 320, true))
        now = 3_600_000L
        assertNull(cache.take(first, 320, true))
        assertEquals(1, ad.releases)

        val unknown = Ad(AdPlatform.ADMOB, 1.0, expiresAtMillis = null)
        val google = ResolvedNativeRequest(AdPlatform.ADMOB, "google", "home")
        cache.put(google, 320, unknown)
        assertNull(cache.take(google, 320, true))
        assertEquals(1, unknown.releases)
    }

    @Test fun `cancelled page leaves usable unrendered candidates for a later request`() {
        val cache = NativeCandidateCache({ 0 }, { _, _ -> }, {})
        val h = Host(cache = cache).start()
        val available = Ad(AdPlatform.ADMOB, 1.0)
        h.loaded(available)
        h.controller.destroy()
        assertEquals(0, available.releases)
        assertNull(available.listener)
        assertFalse(h.events.any { it.name == AdEventName.BID_RESULT })
        val candidate = h.request.candidates().first { it.platform == AdPlatform.ADMOB }
        assertSame(available, cache.take(candidate, 320, true))
        assertNull(cache.take(candidate, 320, true))
        available.destroy()
    }

    @Test fun `duplicate success after transfer cannot reclaim the next page object`() {
        val cache = NativeCandidateCache({ 0 }, { _, _ -> }, {})
        val first = Host(cache = cache).start()
        val ad = Ad(AdPlatform.ADMOB, 1.0)
        first.loaded(ad)
        val old = first.listeners.getValue(AdPlatform.ADMOB)
        first.controller.destroy()
        val request = first.request.candidates().first { it.platform == AdPlatform.ADMOB }
        assertSame(ad, cache.take(request, 320, true))
        val newListener = object : NativeCallbacks by old {}
        ad.setCallbacks(newListener)
        repeat(2) { old.loaded(ad) }
        assertSame(newListener, ad.listener)
        assertNull(cache.take(request, 320, true))
        assertEquals(0, ad.releases)
    }

    @Test fun `cached TopOn candidate taken just before expiry expires while waiting for other platform settling`() {
        var now = 0L
        val cache = NativeCandidateCache({ now }, { _, _ -> }, {})
        val request = ResolvedNativeRequest(position = "home", admobAdUnitId = "google-id", topOnPlacementId = "topon-id")
        val candidate = request.candidates().first { it.platform == AdPlatform.TOPON }
        val topon = Ad(AdPlatform.TOPON, 2.0)
        cache.put(candidate, 320, topon)

        now = 3_599_000L
        val h = Host(cache = cache, now = now).start()
        assertEquals(1, h.loadCount)

        now = 3_600_000L
        h.now = now
        h.listeners.getValue(AdPlatform.ADMOB).failed("no_fill")

        assertEquals(1, topon.releases)
        assertNull(h.rendered)
        assertTrue(h.controller.state is NativeState.Failed)
    }

    @Test fun `cached TopOn candidate expiring during auction lets competitor win instead`() {
        var now = 0L
        val cache = NativeCandidateCache({ now }, { _, _ -> }, {})
        val request = ResolvedNativeRequest(position = "home", admobAdUnitId = "google-id", topOnPlacementId = "topon-id")
        val candidate = request.candidates().first { it.platform == AdPlatform.TOPON }
        val topon = Ad(AdPlatform.TOPON, 2.0)
        cache.put(candidate, 320, topon)

        now = 3_599_000L
        val h = Host(cache = cache, now = now).start()
        now = 3_601_000L
        h.now = now
        val google = Ad(AdPlatform.ADMOB, 1.0, expiresAtMillis = 10_000_000L)
        h.loaded(google)

        assertEquals(1, topon.releases)
        assertSame(google, h.rendered)
        assertEquals(AdPlatform.ADMOB, h.events.single { it.name == AdEventName.BID_RESULT }.winnerPlatform)
    }


    @Test fun `expiry destruction can clear inventory without accepting an old generation candidate`() {
        var now = 0L
        val cache = NativeCandidateCache({ now }, { _, _ -> }, {})
        val request = ResolvedNativeRequest(AdPlatform.ADMOB, "google", "home")
        val expired = Ad(AdPlatform.ADMOB, 1.0, expiresAtMillis = 50)
        val other = Ad(AdPlatform.ADMOB, 1.0)
        cache.put(request, 320, expired)
        cache.put(request.copy(adUnitId = "other"), 320, other)
        expired.onDestroy = cache::clear
        val generation = cache.generation
        now = 50
        val incoming = Ad(AdPlatform.ADMOB, 2.0)

        assertFalse(cache.put(request, 320, incoming, generation))

        assertEquals(generation + 1, cache.generation)
        assertEquals(1, expired.releases)
        assertEquals(1, other.releases)
        assertEquals(1, incoming.releases)
        assertNull(cache.take(request, 320, true))
        assertNull(cache.take(request.copy(adUnitId = "other"), 320, true))
    }

    @Test fun `replacement destruction cannot move incoming candidate across inventory generation`() {
        val cache = NativeCandidateCache({ 0 }, { _, _ -> }, {})
        val request = ResolvedNativeRequest(AdPlatform.ADMOB, "google", "home")
        val old = Ad(AdPlatform.ADMOB, 1.0)
        cache.put(request, 320, old)
        old.onDestroy = cache::clear
        val generation = cache.generation
        val incoming = Ad(AdPlatform.ADMOB, 2.0)

        assertFalse(cache.put(request, 320, incoming, generation))

        assertEquals(generation + 1, cache.generation)
        assertEquals(1, old.releases)
        assertEquals(1, incoming.releases)
        assertNull(cache.take(request, 320, true))
    }

    private class Ad(override val platform: AdPlatform, override var bidPriceUsd: Double?,
        override var expiresAtMillis: Long? = if (platform == AdPlatform.ADMOB) 3_600_000L else null, override val isTemplate: Boolean = false) : NativeAdHandle {
        var releases = 0
        var listener: NativeCallbacks? = null
        override var isValid = true
        override val canCache: Boolean get() = isValid && releases == 0
        override fun setCallbacks(callbacks: NativeCallbacks?) { listener = callbacks }
        override fun bindDeadline(deadline: Long) {
            val current = expiresAtMillis
            if (current == null || deadline < current) {
                expiresAtMillis = deadline
            }
        }
        override val adSource = platform.name
        override val responseId = platform.name
        override fun render(activity: Activity, binding: NativeLayoutBinding?, widthPx: Int): View = error("unused")
        var onDestroy: () -> Unit = {}
        override fun destroy() { releases++; onDestroy() }
    }

    private class Host(private val allowTemplate: Boolean = true, private val cache: NativeCandidateCache? = null,
        position: String = "home", var now: Long = 0L, recordLoadEvents: Boolean = true) {
        val request = ResolvedNativeRequest(position = position, admobAdUnitId = "google-id", topOnPlacementId = "topon-id")
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        var onEvent: (AdEvent) -> Unit = {}
        var onRender: (Ad) -> Unit = {}
        var onInteraction: (String, NativeInteraction) -> Unit = { _, _ -> }
        val listeners = mutableMapOf<AdPlatform, NativeCallbacks>()
        val gates = AdPlatform.entries.associateWith { NativeAvailability(ready = true) }.toMutableMap()
        var rendered: NativeAdHandle? = null
        var timer: Runnable? = null
        var observer: (() -> Unit)? = null
        var loadCount = 0
        var cancelCount = 0
        var synchronous = false
        val controller = NativeCardController(
            availability = { NativeAvailability(ready = true) }, canDisplay = { true },
            newSlot = { NativeSlot(request, 1, { 1 }, { events += it; onEvent(it) }, { revenues += it }) },
            load = { callback ->
                val inventoryGeneration = cache?.generation ?: 0L
                NativeAuction(request, callback, { gates.getValue(it) },
                startLoad = { candidate, listener ->
                    val platform = requireNotNull(candidate.platform)
                    listeners[platform] = listener
                    val cached = cache?.take(candidate, 320, allowTemplate)
                    if (cached != null) {
                        cached.setCallbacks(listener)
                        listener.loaded(cached)
                    } else {
                        loadCount++
                        if (synchronous) listener.loaded(Ad(platform, if (platform == AdPlatform.ADMOB) 1.0 else 2.0))
                    }
                    NativeLoad { cancelCount++ }
                }, subscribe = { observer = it; AutoCloseable { observer = null } }, dispatch = { it() },
                schedule = { action, _ -> timer = action }, unschedule = { if (timer === it) timer = null },
                clock = { now }, allowTemplate = allowTemplate,
                retain = { candidate, ad ->
                    if (cache == null) ad.destroy() else cache.put(candidate, 320, ad, inventoryGeneration)
                },
            ).also { it.start() } },
            render = { ad, _ -> rendered = ad; onRender(ad as Ad) }, removeView = { rendered = null }, clock = { now },
            dispatch = { it() }, interaction = { id, action -> onInteraction(id, action) }, onStateChanged = {},
            recordLoadEvents = recordLoadEvents,
        )
        fun start() = apply { controller.update(true, true) }
        fun loaded(ad: Ad) {
            val listener = listeners.getValue(ad.platform)
            ad.setCallbacks(listener)
            listener.loaded(ad)
        }
        fun expire() = checkNotNull(timer).run()
    }

    private fun revenue(id: String = "paid") = NativeRevenue(0, "USD", "Pangle", id, "exact", topOnAdInfo = Any())
}
