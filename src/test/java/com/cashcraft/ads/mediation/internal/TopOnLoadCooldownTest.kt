package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.internal.topon.TopOnLoadCooldown
import org.junit.Assert.assertEquals
import org.junit.Test

class TopOnLoadCooldownTest {
    @Test fun `only the failed placement waits until the monotonic deadline`() {
        var now = 10_000L
        val cooldown = TopOnLoadCooldown { now }
        assertEquals(0L, cooldown.remainingMillis("a"))
        cooldown.failed("a")
        assertEquals(2_100L, cooldown.remainingMillis("a"))
        assertEquals(0L, cooldown.remainingMillis("b"))
        now += 2_099L
        assertEquals(1L, cooldown.remainingMillis("a"))
        now++
        assertEquals(0L, cooldown.remainingMillis("a"))
    }

    @Test fun `checking does not renew cooldown but another actual failure does`() {
        var now = 0L
        val cooldown = TopOnLoadCooldown { now }
        cooldown.failed("a")
        now = 1_000L
        repeat(3) { assertEquals(1_100L, cooldown.remainingMillis("a")) }
        cooldown.failed("a")
        assertEquals(2_100L, cooldown.remainingMillis("a"))
        now = 2_100L
        cooldown.failed("b")
        assertEquals(1_000L, cooldown.remainingMillis("a"))
        assertEquals(2_100L, cooldown.remainingMillis("b"))
    }
}
