package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.internal.nativeads.NativePositionRegistry
import org.junit.Assert.*
import org.junit.Test

class NativePositionRegistryTest {
    @Test fun `same position rejects second owner without stealing first object`() {
        val positions = NativePositionRegistry<Any>()
        val first = Any()
        val connection = Any()
        val entry = positions.claim("home", first, connection)!!
        assertNull(positions.claim("home", Any(), Any()))
        assertFalse(positions.attach(entry, Any()))
        assertTrue(positions.owns(entry, connection))
        assertSame(first, positions["home"]!!.value)
        assertNotNull(positions.claim("details", Any(), Any()))
    }

    @Test fun `new connection reuses record and late old cleanup cannot affect it`() {
        val positions = NativePositionRegistry<Any>()
        val old = Any()
        val record = Any()
        val entry = positions.claim("home", record, old)!!
        assertTrue(positions.detach(entry, old))
        val current = Any()
        assertTrue(positions.attach(entry, current))
        assertSame(record, positions["home"]!!.value)
        assertFalse(positions.detach(entry, old))
        assertTrue(positions.owns(entry, current))
        assertTrue(positions.remove(entry))
        val replacement = positions.claim("home", Any(), Any())!!
        assertFalse(positions.remove(entry))
        assertFalse(positions.attach(entry, old))
        assertSame(replacement, positions["home"])
    }
}
