package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.*
import org.junit.Test

class NativeAdHandleTest {
    @Test fun `off-main repeated disposal releases once after dispatch`() {
        val queue = mutableListOf<() -> Unit>()
        val handle = NativeAdHandleImpl(AdPlatform.ADMOB, "session") { queue += it }
        var released = 0
        handle.cleanup = { released++ }
        handle.destroy()
        handle.destroy()
        assertFalse(handle.isDestroyed)
        assertEquals(0, released)
        queue.toList().forEach { it() }
        assertTrue(handle.isDestroyed)
        assertEquals(1, released)
        assertNull(handle.cleanup)
    }

    @Test fun `disposing one card does not release another card`() {
        val first = NativeAdHandleImpl(AdPlatform.ADMOB, "first") { it() }
        val second = NativeAdHandleImpl(AdPlatform.TOPON, "second") { it() }
        var secondReleased = false
        second.cleanup = { secondReleased = true }
        first.destroy()
        assertTrue(first.isDestroyed)
        assertFalse(second.isDestroyed)
        assertFalse(secondReleased)
        second.destroy()
        assertTrue(secondReleased)
    }

    @Test fun `cleanup may re-enter destroy without duplicate release`() {
        val handle = NativeAdHandleImpl(AdPlatform.ADMOB, "session") { it() }
        var released = 0
        handle.cleanup = { released++; handle.destroy() }
        handle.destroy()
        assertEquals(1, released)
    }
}
