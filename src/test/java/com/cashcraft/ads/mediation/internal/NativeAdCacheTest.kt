package com.cashcraft.ads.mediation.internal

import org.junit.Assert.*
import org.junit.Test

class NativeAdCacheTest {
    private var now = 0L
    private val destroyed = mutableListOf<String>()
    private val invalid = mutableSetOf<String>()
    private val cache = NativeAdCache({ now }, { ad: String -> destroyed += ad }, { it !in invalid })

    @Test fun `parallel preload attempts share the in-flight request`() {
        val request = cache.beginLoad()!!
        assertNull(cache.beginLoad())
        assertTrue(cache.loaded(request, "ad"))
        assertNull(cache.beginLoad())
        assertEquals("ad", cache.take())
        assertNotNull(cache.beginLoad())
    }

    @Test fun `taking transfers ownership once without destroying the displayed ad`() {
        cache.loaded(cache.beginLoad()!!, "ad")
        assertTrue(cache.hasFreshValue)
        assertEquals("ad", cache.take())
        assertNull(cache.take())
        assertFalse(cache.hasFreshValue)
        assertTrue(destroyed.isEmpty())
    }

    @Test fun `expired cache is unavailable and destroyed exactly once`() {
        cache.loaded(cache.beginLoad()!!, "expired")
        now = NativeAdCache.MAX_AGE_MILLIS - 1
        assertEquals("expired", cache.peek())
        now++
        assertFalse(cache.hasFreshValue)
        assertNull(cache.peek())
        assertNull(cache.take())
        assertEquals(listOf("expired"), destroyed)
        assertNotNull(cache.beginLoad())
    }

    @Test fun `provider-invalid cache is destroyed even before time expiry`() {
        cache.loaded(cache.beginLoad()!!, "invalid")
        invalid += "invalid"
        assertNull(cache.take())
        assertEquals(listOf("invalid"), destroyed)
    }

    @Test fun `late load after timeout cannot replace a newer cache`() {
        val old = cache.beginLoad()!!
        assertTrue(cache.failed(old))
        val current = cache.beginLoad()!!
        assertTrue(cache.loaded(current, "current"))
        assertFalse(cache.loaded(old, "late"))
        assertFalse(cache.failed(old))
        assertEquals("current", cache.take())
        assertEquals(listOf("late"), destroyed)
    }

    @Test fun `late result during retry does not terminate the new request`() {
        val old = cache.beginLoad()!!
        cache.failed(old)
        val retry = cache.beginLoad()!!
        cache.loaded(old, "late")
        assertEquals(retry, cache.loadingToken)
        assertTrue(cache.loaded(retry, "retry"))
        assertEquals("retry", cache.take())
    }
}
