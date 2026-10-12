package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.internal.nativeads.*
import com.cashcraft.ads.mediation.internal.topon.TopOnLoadCooldown
import org.junit.Assert.*
import org.junit.Test

class NativeInventoryControlTest {
    @Test fun `last page release preserves ready inventory for a six minute return`() {
        val h = Host()
        val a = h.control.acquire(h.key)
        val b = h.control.acquire(h.key)
        h.complete(true)
        a.close()
        assertTrue(h.control.hasDemand(h.key))
        b.close()
        b.close()
        assertFalse(h.control.hasDemand(h.key))
        h.advance(360_000)
        assertTrue(h.closes.isEmpty())
        assertTrue(h.control.isReady(h.key))
        h.control.acquire(h.key)
        assertEquals(1, h.calls.size)
    }

    @Test fun `enabled placements are not evicted by unused inventory count`() {
        val h = Host()
        val keys = (1..6).map { h.key.copy(id = "$it") }
        keys.forEach { h.control.acquire(it).close(); h.complete(true) }
        h.advance(360_000)
        assertTrue(h.closes.isEmpty())
        keys.forEach { assertTrue(h.control.isReady(it)) }
        assertEquals(6, h.calls.size)
        h.control.clear()
        assertEquals(keys, h.closes)
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

    @Test fun `revoked permission cancels retries and stale results cannot revive sessions`() {
        val h = Host()
        h.control.acquire(h.key)
        val stale = h.calls.last().complete
        h.complete(false)
        h.control.updateEnvironment(permitted = false)
        stale(true)
        h.advance(10_000)
        assertEquals(1, h.calls.size)
        assertFalse(h.control.isReady(h.key))
        h.control.updateEnvironment(permitted = true)
        assertEquals(2, h.calls.size)
        val previous = h.calls.last().complete
        h.control.updateEnvironment(permitted = false)
        h.control.updateEnvironment(permitted = true)
        previous(true)
        assertFalse(h.control.isReady(h.key))
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
        assertEquals(3, h.calls.size)
        assertEquals(2, h.closes.size)
    }

    @Test fun `permission restoration restarts enabled inventory even without page demand`() {
        val h = Host()
        h.control.acquire(h.key).close()
        val active = h.key.copy(id = "active")
        h.control.acquire(active)
        h.control.updateEnvironment(false)
        h.control.updateEnvironment(true)
        assertEquals(listOf(h.key, active, h.key, active), h.calls.map { it.key })
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
        h.onClose = { h.control.updateEnvironment(false) }
        h.control.close(h.key)
        h.advance(10_000)
        assertEquals(1, h.closes.size)
        assertEquals(1, h.calls.size)
        assertEquals(1, h.cancels)
    }

    @Test fun `network recovery restarts exhausted TopOn inventory without page demand`() {
        val h = Host()
        h.control.acquire(h.key).close()
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
        h.control.updateEnvironment(false)
        assertEquals(listOf(h.key, other), h.closes)
        assertEquals(2, h.cancels)
        assertEquals(2, h.errors.size)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun `reacquiring unused key does not prepare twice and explicit close stops it`() {
        val h = Host()
        h.control.acquire(h.key).close()
        h.advance(299_999)
        val demand = h.control.acquire(h.key)
        h.advance(1)
        assertEquals(1, h.calls.size)
        assertTrue(h.closes.isEmpty())
        demand.close()
        h.advance(300_000)
        assertTrue(h.closes.isEmpty())
        h.control.close(h.key)
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
        h.control.updateEnvironment(setOf(AdPlatform.ADMOB))
        stale(true)
        assertEquals(listOf(h.key), h.closes)
        assertFalse(h.control.isReady(h.key))
        assertEquals(2, h.calls.size)
        h.control.updateEnvironment(AdPlatform.entries.toSet())
        assertEquals(listOf(h.key, other, h.key), h.calls.map { it.key })
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
    }

    @Test fun `clear removes stopped demand and old subscriptions cannot release new demand`() {
        val h = Host()
        val old = h.control.acquire(h.key)
        h.control.updateEnvironment(false)
        h.control.clear()
        h.control.updateEnvironment(true)
        assertEquals(1, h.calls.size)
        h.control.acquire(h.key)
        old.close()
        h.advance(300_000)
        assertEquals(2, h.calls.size)
        assertEquals(1, h.closes.size)
    }

    @Test fun `blocked demand still reserves its incompatible placement key`() {
        val h = Host()
        val key = h.key.copy(widthPx = 320, templateRatio = 2f)
        h.control.acquire(key)
        h.control.updateEnvironment(false)
        assertFalse(h.control.hasIncompatibleKey(key))
        assertTrue(h.control.hasIncompatibleKey(key.copy(widthPx = 640)))
        assertFalse(h.control.hasIncompatibleKey(key.copy(id = "other")))
        h.control.clear()
        assertFalse(h.control.hasIncompatibleKey(key.copy(widthPx = 640)))
    }

    @Test fun `TopOn ready callbacks cannot reset bounded take failure retries`() {
        val h = Host()
        h.control.acquire(h.key)
        h.complete(true)
        h.control.takeFailed(h.key)
        assertFalse(h.control.isReady(h.key))

        for (delay in listOf(2_000L, 4_000L, 8_000L)) {
            val count = h.calls.size
            assertEquals(listOf(h.now + delay), h.timers.values.toList())
            h.control.takeFailed(h.key)
            h.control.acquire(h.key)
            h.advance(delay - 1)
            assertEquals(count, h.calls.size)
            h.advance(1)
            assertEquals(count + 1, h.calls.size)
            h.complete(true)
            assertTrue(h.control.isReady(h.key))
            h.control.takeFailed(h.key)
            assertFalse(h.control.isReady(h.key))
        }

        assertEquals(4, h.calls.size)
        assertTrue(h.timers.isEmpty())
        h.control.acquire(h.key)
        h.control.takeFailed(h.key)
        h.advance(60_000)
        assertEquals(4, h.calls.size)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun `successful consumption restores full take failure retry budget`() {
        val h = Host()
        h.control.acquire(h.key)
        h.complete(true)
        for (delay in listOf(2_000L, 4_000L)) {
            h.control.takeFailed(h.key)
            h.advance(delay)
            h.complete(true)
        }
        assertEquals(3, h.calls.size)
        h.control.consumed(h.key)
        assertFalse(h.control.isReady(h.key))
        assertEquals(4, h.calls.size)
        h.complete(true)
        h.control.takeFailed(h.key)

        for (delay in listOf(2_000L, 4_000L, 8_000L)) {
            val count = h.calls.size
            h.advance(delay - 1)
            assertEquals(count, h.calls.size)
            h.advance(1)
            assertEquals(count + 1, h.calls.size)
            h.complete(true)
            h.control.takeFailed(h.key)
        }
        h.advance(60_000)
        assertEquals(7, h.calls.size)
        assertFalse(h.control.isReady(h.key))
        assertTrue(h.timers.isEmpty())
    }

    @Test fun `take failure ignores absent pending and AdMob inventory`() {
        for (platform in AdPlatform.entries) {
            val h = Host(platform)
            h.control.takeFailed(h.key)
            assertTrue(h.calls.isEmpty())
            assertTrue(h.timers.isEmpty())
            h.control.acquire(h.key)
            val timers = h.timers.toMap()
            h.control.takeFailed(h.key)
            assertEquals(1, h.calls.size)
            assertEquals(timers, h.timers)
            h.complete(true)
            h.control.takeFailed(h.key)
            assertEquals(platform == AdPlatform.ADMOB, h.control.isReady(h.key))
            assertEquals(1, h.calls.size)
            if (platform == AdPlatform.TOPON) {
                assertEquals(listOf(2_000L), h.timers.values.toList())
            } else {
                assertEquals(timers, h.timers)
            }
        }
    }

    @Test fun `explicit retry and environment reset restore take failure budget`() {
        val h = Host()
        h.control.acquire(h.key)
        repeat(4) { index ->
            h.complete(true)
            h.control.takeFailed(h.key)
            if (index < 3) h.advance(2_000L shl index)
        }
        h.control.retry(h.key)
        assertEquals(5, h.calls.size)
        h.complete(true)
        h.control.takeFailed(h.key)
        h.advance(1_999)
        assertEquals(5, h.calls.size)
        h.advance(1)
        assertEquals(6, h.calls.size)
        h.complete(true)
        h.control.takeFailed(h.key)
        h.control.updateEnvironment(false)
        assertTrue(h.timers.isEmpty())
        h.control.updateEnvironment(true)
        assertEquals(7, h.calls.size)
        h.complete(true)
        h.control.takeFailed(h.key)
        h.advance(1_999)
        assertEquals(7, h.calls.size)
        h.advance(1)
        assertEquals(8, h.calls.size)
    }

    @Test fun `SDK failure cooldown combines with backoff without using an extra retry`() {
        val h = Host(cooldownEnabled = true)
        h.control.acquire(h.key)
        for (delay in listOf(2_100L, 4_000L, 8_000L)) {
            h.complete(false)
            val count = h.calls.size
            repeat(3) { h.control.acquire(h.key); h.control.retry(h.key) }
            h.advance(delay - 1)
            assertEquals(count, h.calls.size)
            h.advance(1)
            assertEquals(count + 1, h.calls.size)
        }
        h.complete(false)
        h.advance(60_000)
        assertEquals(4, h.calls.size)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun `repeated permission changes retain placement cooldown`() {
        val h = Host(cooldownEnabled = true)
        h.control.acquire(h.key)
        h.complete(false)
        h.advance(300)
        h.control.updateEnvironment(false)
        h.control.updateEnvironment(true)
        h.advance(300)
        h.control.updateEnvironment(false)
        h.control.updateEnvironment(true)
        h.advance(1_499)
        assertEquals(1, h.calls.size)
        assertEquals(1, h.timers.size)
        h.advance(1)
        assertEquals(2, h.calls.size)
    }

    @Test fun `network recovery opens an exhausted round only after failure cooldown`() {
        val h = Host(cooldownEnabled = true)
        h.control.acquire(h.key)
        for (delay in listOf(2_100L, 4_000L, 8_000L)) {
            h.complete(false)
            h.advance(delay)
        }
        h.complete(false)
        repeat(3) { h.control.networkRecovered() }
        assertEquals(4, h.calls.size)
        assertEquals(1, h.timers.size)
        h.advance(2_099)
        assertEquals(4, h.calls.size)
        h.advance(1)
        assertEquals(5, h.calls.size)
    }

    @Test fun `closing and recreating a different size cannot bypass placement cooldown`() {
        val h = Host(cooldownEnabled = true)
        h.control.acquire(h.key)
        h.complete(false)
        h.control.close(h.key)
        val resized = h.key.copy(widthPx = 640, templateRatio = 2f)
        h.control.acquire(resized)
        val other = h.key.copy(id = "other")
        h.control.acquire(other)
        assertEquals(listOf(h.key, other), h.calls.map { it.key })
        h.advance(2_100)
        assertEquals(listOf(h.key, other, resized), h.calls.map { it.key })
    }

    @Test fun `closing or staying blocked cancels a pending cooldown load`() {
        for (close in listOf(true, false)) {
            val h = Host(cooldownEnabled = true)
            h.cooldown.failed(h.key.id)
            h.control.acquire(h.key)
            assertTrue(h.calls.isEmpty())
            if (close) h.control.close(h.key) else h.control.updateEnvironment(false)
            h.advance(10_000)
            assertTrue(h.calls.isEmpty())
            assertTrue(h.timers.isEmpty())
        }
    }

    @Test fun `idle prewarm retains its valid load and AdMob ignores TopOn cooldown`() {
        val h = Host(cooldownEnabled = true)
        h.cooldown.failed(h.key.id)
        h.control.acquire(h.key).close()
        val admob = h.key.copy(platform = AdPlatform.ADMOB)
        h.control.acquire(admob)
        assertEquals(listOf(admob), h.calls.map { it.key })
        h.advance(2_100)
        assertEquals(listOf(admob, h.key), h.calls.map { it.key })
    }

    @Test fun `take failure does not create SDK failure cooldown`() {
        val h = Host(cooldownEnabled = true)
        h.control.acquire(h.key)
        h.complete(true)
        h.control.takeFailed(h.key)
        h.advance(2_000)
        assertEquals(2, h.calls.size)
        assertEquals(0L, h.cooldown.remainingMillis(h.key.id))
    }

    @Test fun `unused inventory continues bounded retries and consumption refill`() {
        val h = Host()
        h.control.acquire(h.key).close()
        h.complete(false)
        h.advance(2_000)
        assertEquals(2, h.calls.size)
        h.complete(true)
        h.control.consumed(h.key)
        assertEquals(3, h.calls.size)
        repeat(4) { index ->
            h.complete(false)
            if (index < 3) h.advance(2_000L shl index)
        }
        h.advance(360_000)
        assertEquals(6, h.calls.size)
        assertTrue(h.timers.isEmpty())
        assertTrue(h.closes.isEmpty())
        h.control.retry(h.key)
        assertEquals(7, h.calls.size)
    }

    @Test fun `unused AdMob inventory expires and restarts while old callbacks stay invalid`() {
        val h = Host(AdPlatform.ADMOB)
        h.control.acquire(h.key).close()
        val first = h.calls.single()
        first.complete(true)
        h.advance(3_599_999)
        assertTrue(h.control.isReady(h.key))
        assertTrue(h.closes.isEmpty())
        h.advance(1)
        assertEquals(listOf(h.key), h.closes)
        assertEquals(2, h.calls.size)
        first.complete(true)
        assertFalse(h.control.isReady(h.key))
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
    }

    @Test fun `new template size replaces only unused incompatible inventory`() {
        val h = Host()
        val oldKey = h.key.copy(widthPx = 320, templateRatio = 2f)
        val newKey = oldKey.copy(widthPx = 640)
        val demand = h.control.acquire(oldKey)
        val stale = h.calls.single().complete
        assertTrue(h.control.hasIncompatibleKey(newKey))
        demand.close()
        assertFalse(h.control.hasIncompatibleKey(newKey))
        h.control.acquire(newKey)
        assertEquals(listOf(oldKey), h.closes)
        stale(true)
        assertFalse(h.control.isReady(oldKey))
        assertFalse(h.control.isReady(newKey))
        h.complete(true)
        assertTrue(h.control.isReady(newKey))
        demand.close()
        assertTrue(h.control.hasDemand(newKey))
    }

    @Test fun `AdMob failed starts clear expiry and exhaust a bounded round then allow explicit retry`() {
        for (throws in listOf(false, true)) {
            val h = Host(AdPlatform.ADMOB)
            h.startSucceeds = false
            if (throws) h.onPrepare = { error("SDK start failed") }
            h.control.acquire(h.key).close()
            val stale = h.calls.first().complete
            assertNull(h.control.deadline(h.key))
            for (delay in listOf(2_000L, 4_000L, 8_000L)) {
                val count = h.calls.size
                h.control.acquire(h.key).close()
                stale(true)
                assertFalse(h.control.isReady(h.key))
                h.advance(delay - 1)
                assertEquals(count, h.calls.size)
                h.advance(1)
                assertEquals(count + 1, h.calls.size)
            }
            assertEquals(4, h.closes.size)
            assertTrue(h.timers.isEmpty())
            h.advance(3_600_000)
            h.control.acquire(h.key).close()
            assertEquals(4, h.calls.size)
            h.startSucceeds = true
            h.onPrepare = {}
            h.control.retry(h.key)
            assertEquals(5, h.calls.size)
            h.complete(true)
            assertTrue(h.control.isReady(h.key))
            assertEquals(h.now + 3_600_000, h.control.deadline(h.key))
        }
    }

    @Test fun `AdMob started session load failure stays SDK owned without host restart`() {
        val h = Host(AdPlatform.ADMOB)
        h.control.acquire(h.key).close()
        repeat(5) {
            h.complete(false)
            h.advance(10_000)
            h.control.acquire(h.key).close()
            h.control.retry(h.key)
        }
        assertEquals(1, h.calls.size)
        assertTrue(h.closes.isEmpty())
        h.complete(true)
        assertTrue(h.control.isReady(h.key))
    }

    @Test fun `AdMob start cleanup reentry cannot schedule retries after close or permission revocation`() {
        for (close in listOf(false, true)) {
            val h = Host(AdPlatform.ADMOB)
            h.startSucceeds = false
            h.onClose = { if (close) h.control.close(h.key) else h.control.updateEnvironment(false) }
            h.control.acquire(h.key).close()
            h.advance(3_600_000)
            assertEquals(1, h.calls.size)
            assertEquals(1, h.closes.size)
            assertTrue(h.timers.isEmpty())
        }
    }

    private class Host(platform: AdPlatform = AdPlatform.TOPON, cooldownEnabled: Boolean = false) {
        data class Call(val key: NativeInventoryKey, val deadline: Long?, val complete: (Boolean) -> Unit)
        val key = NativeInventoryKey(platform, "placement")
        var now = 0L
        val cooldown = TopOnLoadCooldown { now }
        val calls = mutableListOf<Call>()
        val closes = mutableListOf<NativeInventoryKey>()
        val errors = mutableListOf<Throwable>()
        val timers = mutableMapOf<Runnable, Long>()
        var cancels = 0
        var startSucceeds = true
        var onPrepare: ((Boolean) -> Unit) -> Unit = {}
        var onClose: () -> Unit = {}
        val control = NativeInventoryControl(
            clock = { now },
            schedule = { action, delay -> timers[action] = now + delay },
            unschedule = { timers.remove(it) },
            prepare = { key, deadline, complete ->
                val result: (Boolean) -> Unit = { success ->
                    if (cooldownEnabled && key.platform == AdPlatform.TOPON && !success) cooldown.failed(key.id)
                    complete(success)
                }
                calls += Call(key, deadline, result)
                onPrepare(result)
                if (startSucceeds) NativeLoad { cancels++ } else null
            },
            closeSession = { closes += it; onClose() },
            reportError = { _, _, error -> errors += error },
            loadDelayMillis = { key ->
                if (cooldownEnabled && key.platform == AdPlatform.TOPON) cooldown.remainingMillis(key.id) else 0L
            },
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
