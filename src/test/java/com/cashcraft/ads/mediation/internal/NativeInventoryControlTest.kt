package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.internal.nativeads.*
import org.junit.Assert.*
import org.junit.Test

class NativeInventoryControlTest {
    @Test fun `shared demand merges preparation and last release starts idle window once`() {
        val h = Host()
        val a = h.control.acquire(h.key)
        val b = h.control.acquire(h.key)
        assertEquals(1, h.calls.size)
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
        a.close()
        assertTrue(h.control.hasDemand(h.key))
        h.advance(300_000)
        assertTrue(h.closes.isEmpty())
        b.close()
        assertFalse(h.control.hasDemand(h.key))
        h.advance(299_999)
        b.close()
        assertTrue(h.closes.isEmpty())
        h.advance(1)
        assertEquals(listOf(h.key), h.closes)
        assertFalse(h.control.isReady(h.key))
    }

    @Test fun `idle capacity evicts oldest idle key but never active demand`() {
        val h = Host()
        val active = h.control.acquire(h.key)
        val idle = (1..5).map { h.key.copy(id = "$it") }
        idle.forEach { h.control.acquire(it).close() }
        assertEquals(listOf(idle.first()), h.closes)
        assertEquals(6, h.calls.size)
        active.close()
        assertEquals(listOf(idle.first(), idle[1]), h.closes)
    }

    @Test fun `TopOn retries two four eight seconds and duplicate demand cannot reset exhaustion`() {
        val h = Host()
        h.control.acquire(h.key)
        h.complete(false)
        h.control.acquire(h.key)
        for (delay in listOf(2_000L, 4_000L, 8_000L)) {
            val count = h.calls.size
            h.advance(delay - 1)
            assertEquals(count, h.calls.size)
            h.advance(1)
            assertEquals(count + 1, h.calls.size)
            h.complete(false)
        }
        assertEquals(4, h.calls.size)
        h.control.acquire(h.key)
        h.advance(60_000)
        assertEquals(4, h.calls.size)
        h.control.retry(h.key)
        assertEquals(5, h.calls.size)
        h.control.retry(h.key)
        assertEquals(5, h.calls.size)
        h.complete(false)
        h.advance(2_000)
        assertEquals(6, h.calls.size)
    }

    @Test fun `consumption replenishes TopOn but AdMob leaves replenishment to SDK`() {
        for (platform in AdPlatform.entries) {
            val h = Host(platform)
            h.control.acquire(h.key)
            h.complete(true)
            h.control.consumed(h.key)
            assertFalse(h.control.isReady(h.key))
            assertEquals(if (platform == AdPlatform.TOPON) 2 else 1, h.calls.size)
            if (platform == AdPlatform.ADMOB) {
                h.complete(true)
                assertTrue(h.control.isReady(h.key))
            }
        }
    }

    @Test fun `background and consent cancel retries and stale results cannot revive sessions`() {
        val h = Host()
        h.control.acquire(h.key)
        val stale = h.calls.last().complete
        h.complete(false)
        h.control.updateEnvironment(foreground = false, permitted = true)
        stale(true)
        h.advance(10_000)
        assertEquals(1, h.calls.size)
        assertFalse(h.control.isReady(h.key))
        h.control.updateEnvironment(foreground = true, permitted = true)
        assertEquals(2, h.calls.size)
        val previous = h.calls.last().complete
        h.control.updateEnvironment(foreground = true, permitted = false)
        h.control.updateEnvironment(foreground = true, permitted = true)
        previous(true)
        assertFalse(h.control.isReady(h.key))
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
        assertEquals(3, h.calls.size)
        assertEquals(2, h.closes.size)
    }

    @Test fun `idle inventory is discarded in background and only active demand resumes`() {
        val h = Host()
        h.control.acquire(h.key).close()
        val active = h.key.copy(id = "active")
        h.control.acquire(active)
        h.control.updateEnvironment(false, true)
        h.control.updateEnvironment(true, true)
        assertEquals(listOf(h.key, active, active), h.calls.map { it.key })
    }

    @Test fun `AdMob session expires from start and replenishment does not renew deadline`() {
        val h = Host(AdPlatform.ADMOB)
        h.control.acquire(h.key)
        val first = h.calls.single()
        assertEquals(3_600_000L, first.deadline)
        h.advance(3_000_000)
        first.complete(true)
        h.control.consumed(h.key)
        first.complete(true)
        assertEquals(first.deadline, h.control.deadline(h.key))
        h.advance(600_000)
        assertEquals(2, h.calls.size)
        assertEquals(listOf(h.key), h.closes)
        assertEquals(7_200_000L, h.control.deadline(h.key))
        first.complete(true)
        assertFalse(h.control.isReady(h.key))
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
    }

    @Test fun `synchronous preparation and close reentry cannot retain stale operations`() {
        val h = Host()
        h.onPrepare = { it(false) }
        h.control.acquire(h.key)
        assertEquals(1, h.calls.size)
        h.onClose = { h.control.updateEnvironment(false, false) }
        h.control.close(h.key)
        h.advance(10_000)
        assertEquals(1, h.closes.size)
        assertEquals(1, h.calls.size)
        assertEquals(1, h.cancels)
    }

    @Test fun `network recovery starts only exhausted TopOn rounds with active demand`() {
        val h = Host()
        h.control.acquire(h.key)
        repeat(4) { index ->
            h.complete(false)
            if (index < 3) h.advance(2_000L shl index)
        }
        h.control.networkRecovered()
        assertEquals(5, h.calls.size)
        h.control.networkRecovered()
        assertEquals(5, h.calls.size)
    }

    @Test fun `old session candidate retains deadline after rotation and cache transfer`() {
        val h = Host(AdPlatform.ADMOB)
        h.control.acquire(h.key)
        val firstDeadline = checkNotNull(h.calls.single().deadline)
        val ad = object : NativeAdHandle {
            override val platform = AdPlatform.ADMOB
            override val expiresAtMillis = firstDeadline
            override val isTemplate = false
            override val canCache = true
            override val adSource: String? = null
            override val responseId: String? = null
            var destroyed = 0
            override fun render(activity: android.app.Activity,
                binding: com.cashcraft.ads.mediation.NativeLayoutBinding?, widthPx: Int): android.view.View = error("unused")
            override fun destroy() { destroyed++ }
        }
        val cache = NativeCandidateCache({ h.now }, { _, _ -> }, {})
        val request = com.cashcraft.ads.mediation.ResolvedNativeRequest(AdPlatform.ADMOB, "placement", "first")
        h.advance(3_000_000)
        assertTrue(cache.put(request, 320, ad))
        assertSame(ad, cache.take(request.copy(position = "second"), 320, true))
        assertTrue(cache.put(request, 320, ad))
        h.advance(600_000)
        assertEquals(7_200_000L, h.control.deadline(h.key))
        assertNull(cache.take(request, 320, true))
        assertEquals(firstDeadline, ad.expiresAtMillis)
        assertEquals(1, ad.destroyed)
    }

    @Test fun `close failure cannot prevent another active key from shutting down`() {
        val h = Host()
        h.control.acquire(h.key)
        val other = h.key.copy(id = "other")
        h.control.acquire(other)
        h.onClose = { error("close failed") }
        h.control.updateEnvironment(false, false)
        assertEquals(listOf(h.key, other), h.closes)
        assertEquals(2, h.cancels)
        assertEquals(2, h.errors.size)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun `reacquiring idle key cancels eviction without preparing it twice`() {
        val h = Host()
        h.control.acquire(h.key).close()
        h.advance(299_999)
        val demand = h.control.acquire(h.key)
        h.advance(1)
        assertEquals(1, h.calls.size)
        assertTrue(h.closes.isEmpty())
        demand.close()
        h.advance(300_000)
        assertEquals(listOf(h.key), h.closes)
    }

    @Test fun `closed old subscription cannot release new key demand`() {
        val h = Host()
        val old = h.control.acquire(h.key)
        h.control.close(h.key)
        h.control.acquire(h.key)
        old.close()
        h.advance(300_000)
        assertEquals(1, h.closes.size)
        assertEquals(2, h.calls.size)
    }

    @Test fun `compatible keys share independently of position but template dimensions remain distinct`() {
        val h = Host()
        val first = h.key.copy(widthPx = 320, templateRatio = 2f)
        val second = first.copy(widthPx = 640)
        h.control.acquire(first)
        h.control.acquire(first.copy())
        h.control.acquire(second)
        assertEquals(listOf(first, second), h.calls.map { it.key })
    }

    @Test fun `closing cancels retry and preparation exception follows the same bounded round`() {
        val h = Host()
        h.onPrepare = { error("prepare failed") }
        val demand = h.control.acquire(h.key)
        assertEquals(1, h.errors.size)
        h.advance(2_000)
        assertEquals(2, h.calls.size)
        assertEquals(2, h.errors.size)
        h.control.close(h.key)
        demand.close()
        h.advance(10_000)
        assertEquals(2, h.calls.size)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun `platform readiness stops only ineligible inventory and restores its demand`() {
        val h = Host()
        val other = h.key.copy(platform = AdPlatform.ADMOB)
        h.control.acquire(h.key)
        h.control.acquire(other)
        val stale = h.calls.first().complete
        h.control.updateEnvironment(true, setOf(AdPlatform.ADMOB))
        stale(true)
        assertEquals(listOf(h.key), h.closes)
        assertFalse(h.control.isReady(h.key))
        assertEquals(2, h.calls.size)
        h.control.updateEnvironment(true, AdPlatform.entries.toSet())
        assertEquals(listOf(h.key, other, h.key), h.calls.map { it.key })
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
    }

    @Test fun `clear removes stopped demand and old subscriptions cannot release new demand`() {
        val h = Host()
        val old = h.control.acquire(h.key)
        h.control.updateEnvironment(false, true)
        h.control.clear()
        h.control.updateEnvironment(true, true)
        assertEquals(1, h.calls.size)
        h.control.acquire(h.key)
        old.close()
        h.advance(300_000)
        assertEquals(2, h.calls.size)
        assertEquals(1, h.closes.size)
    }

    @Test fun `background demand still reserves its incompatible placement key`() {
        val h = Host()
        val key = h.key.copy(widthPx = 320, templateRatio = 2f)
        h.control.acquire(key)
        h.control.updateEnvironment(false, true)
        assertFalse(h.control.hasIncompatibleKey(key))
        assertTrue(h.control.hasIncompatibleKey(key.copy(widthPx = 640)))
        assertFalse(h.control.hasIncompatibleKey(key.copy(id = "other")))
        h.control.clear()
        assertFalse(h.control.hasIncompatibleKey(key.copy(widthPx = 640)))
    }

    private class Host(platform: AdPlatform = AdPlatform.TOPON) {
        data class Call(val key: NativeInventoryKey, val deadline: Long?, val complete: (Boolean) -> Unit)
        val key = NativeInventoryKey(platform, "placement")
        var now = 0L
        val calls = mutableListOf<Call>()
        val closes = mutableListOf<NativeInventoryKey>()
        val errors = mutableListOf<Throwable>()
        val timers = mutableMapOf<Runnable, Long>()
        var cancels = 0
        var onPrepare: ((Boolean) -> Unit) -> Unit = {}
        var onClose: () -> Unit = {}
        val control = NativeInventoryControl(
            clock = { now },
            schedule = { action, delay -> timers[action] = now + delay },
            unschedule = { timers.remove(it) },
            prepare = { key, deadline, complete ->
                calls += Call(key, deadline, complete)
                onPrepare(complete)
                NativeLoad { cancels++ }
            },
            closeSession = { closes += it; onClose() },
            reportError = { _, _, error -> errors += error },
        )
        fun complete(success: Boolean) = calls.last().complete(success)
        fun advance(millis: Long) {
            val target = now + millis
            while (true) {
                val next = timers.minByOrNull { it.value } ?: break
                if (next.value > target) break
                now = next.value
                timers.remove(next.key)
                next.key.run()
            }
            now = target
        }
    }
}
