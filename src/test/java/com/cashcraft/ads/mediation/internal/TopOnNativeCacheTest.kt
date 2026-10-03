package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.internal.nativeads.findTopOnNativeCache
import com.cashcraft.ads.mediation.internal.nativeads.takeTopOnNativeCache
import org.junit.Assert.*
import org.junit.Test

class TopOnNativeCacheTest {
    @Test fun `foreign head does not block owned inventory or become its candidate`() {
        val foreign = info()
        val oldConsent = info(0L, "current")
        val otherSession = info(1L, "other")
        val owned = info(1L, "current")
        val caches = listOf(foreign, oldConsent, otherSession, owned)

        assertSame(owned, findTopOnNativeCache(caches, 1L, "current") { it })
        assertEquals(listOf(foreign, oldConsent, otherSession, owned), caches)
        assertNull(findTopOnNativeCache(caches.dropLast(1), 1L, "current") { it })
        assertNull(findTopOnNativeCache(caches, 2L, "current") { it })
        assertNull(findTopOnNativeCache<Map<String, Any>>(null, 1L, "current") { it })
    }

    @Test fun `single load skips unmarked and stale ads without requiring an inventory session`() {
        val current = info(1L)
        assertSame(current, findTopOnNativeCache(listOf(info(), info(0L), current), 1L) { it })
        assertNull(findTopOnNativeCache(listOf(info(), info(0L)), 1L) { it })
    }

    @Test fun `taking releases foreign SDK head before delivering owned inventory`() {
        val foreign = info()
        val owned = info(1L, "current")
        val next = info(1L, "current")
        val queue = mutableListOf(foreign, owned, next)
        val events = mutableListOf<String>()

        val taken = takeTopOnNativeCache(
            maxAttempts = queue.size,
            isCurrent = { true },
            poll = { events += "poll"; queue.removeAt(0) },
            accept = { findTopOnNativeCache(listOf(it), 1L, "current") { extra -> extra } != null },
            discard = { assertSame(foreign, it); events += "discard" },
        )

        assertSame(owned, taken)
        assertEquals(listOf("poll", "discard", "poll"), events)
        assertEquals(listOf(next), queue)
    }

    @Test fun `taking respects requested attempts and never polls beyond four`() {
        for (maxAttempts in listOf(0, 1, 2, 4, 10)) {
            var polls = 0
            val discarded = mutableListOf<Int>()
            val taken = takeTopOnNativeCache(
                maxAttempts = maxAttempts,
                isCurrent = { true },
                poll = { ++polls },
                accept = { it == 5 },
                discard = { discarded += it },
            )

            assertNull(taken)
            assertEquals(minOf(maxAttempts, 4), polls)
            assertEquals((1..polls).toList(), discarded)
        }
    }

    @Test fun `null poll stops immediately even with attempts remaining`() {
        for (foreignFirst in listOf(false, true)) {
            var polls = 0
            val discarded = mutableListOf<Int>()
            val taken = takeTopOnNativeCache(
                maxAttempts = 4,
                isCurrent = { true },
                poll = {
                    polls++
                    when {
                        foreignFirst && polls == 1 -> 1
                        polls == (if (foreignFirst) 2 else 1) -> null
                        else -> 2
                    }
                },
                accept = { it == 2 },
                discard = { discarded += it },
            )

            assertNull(taken)
            assertEquals(if (foreignFirst) 2 else 1, polls)
            assertEquals(if (foreignFirst) listOf(1) else emptyList<Int>(), discarded)
        }
    }

    @Test fun `invalid permission prevents first poll and another poll after discard`() {
        for (initiallyCurrent in listOf(false, true)) {
            var current = initiallyCurrent
            var polls = 0
            val discarded = mutableListOf<Int>()
            val taken = takeTopOnNativeCache(
                maxAttempts = 4,
                isCurrent = { current },
                poll = { ++polls },
                accept = { false },
                discard = { discarded += it; current = false },
            )

            assertNull(taken)
            assertEquals(if (initiallyCurrent) 1 else 0, polls)
            assertEquals(if (initiallyCurrent) listOf(1) else emptyList<Int>(), discarded)
        }
    }

    @Test fun `permission revoked during poll releases owned object without delivery or acceptance`() {
        val owned = info(1L, "current")
        var current = true
        var polls = 0
        var accepts = 0
        val discarded = mutableListOf<Map<String, Any>>()
        val taken = takeTopOnNativeCache(
            maxAttempts = 4,
            isCurrent = { current },
            poll = { polls++; current = false; owned },
            accept = { accepts++; true },
            discard = { discarded += it },
        )

        assertNull(taken)
        assertEquals(1, polls)
        assertEquals(0, accepts)
        assertEquals(1, discarded.size)
        assertSame(owned, discarded.single())
    }

    @Test fun `acceptance or post poll permission exception releases object and rethrows original`() {
        for (permissionThrows in listOf(false, true)) {
            val owned = info(1L, "current")
            val failure = IllegalStateException("validation failed")
            var checks = 0
            var polls = 0
            var accepts = 0
            val discarded = mutableListOf<Map<String, Any>>()
            val result = runCatching {
                takeTopOnNativeCache(
                    maxAttempts = 4,
                    isCurrent = {
                        checks++
                        if (permissionThrows && checks == 2) throw failure
                        true
                    },
                    poll = { polls++; owned },
                    accept = { accepts++; throw failure },
                    discard = { discarded += it },
                )
            }

            assertSame(failure, result.exceptionOrNull())
            assertEquals(if (permissionThrows) 0 else 1, accepts)
            assertEquals(1, polls)
            assertEquals(1, discarded.size)
            assertSame(owned, discarded.single())
        }
    }

    private fun info(generation: Long? = null, session: String? = null) =
        mutableMapOf<String, Any>().apply {
            generation?.let { put("cashcraft_native_consent_generation", it) }
            session?.let { put("cashcraft_native_inventory_session", it) }
        }
}
