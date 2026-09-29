package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.BiddingProviderConfig
import com.cashcraft.ads.mediation.TopOnIds
import com.cashcraft.ads.mediation.TopOnProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerProviderReadinessTest {
    private val admob = AdMobProviderConfig(AdMobIds.TEST)
    private val topon = TopOnProviderConfig(TopOnIds("app", "key", "open", "interstitial", "rewarded"))

    @Test
    fun `selected provider result is independent of aggregate bidding success`() {
        for (winner in AdPlatform.entries) {
            val other = AdPlatform.entries.first { it != winner }
            val gate = BannerProviderReadiness()
            gate.configure(BiddingProviderConfig(admob, topon))
            val changes = mutableListOf<BannerReadiness>()
            gate.observe(winner) { changes += gate.read(winner, true) }
            gate.completed(winner, true)
            assertEquals(listOf(BannerReadiness.INITIALIZING, BannerReadiness.READY), changes)
            assertEquals(BannerReadiness.INITIALIZING, gate.read(other, true))
            gate.completed(other, false)
            assertEquals(BannerReadiness.READY, gate.read(winner, true))
            assertEquals(BannerReadiness.FAILED, gate.read(other, true))
        }
    }

    @Test
    fun `unconfigured and failed providers never become eligible with consent`() {
        val gate = BannerProviderReadiness()
        assertEquals(BannerReadiness.NOT_INITIALIZED, gate.read(AdPlatform.ADMOB, false))
        gate.configure(admob)
        assertEquals(BannerReadiness.NOT_CONFIGURED, gate.read(AdPlatform.TOPON, true))
        assertEquals(BannerReadiness.CONSENT_REQUIRED, gate.read(AdPlatform.ADMOB, false))
        gate.failedPending()
        assertEquals(BannerReadiness.FAILED, gate.read(AdPlatform.ADMOB, true))
        gate.completed(AdPlatform.TOPON, true)
        assertEquals(BannerReadiness.NOT_CONFIGURED, gate.read(AdPlatform.TOPON, true))
    }

    @Test
    fun `existing privacy options recovery can start the previously blocked initialization`() {
        val gate = BannerProviderReadiness()
        gate.configure(admob)
        gate.failedPending()
        gate.started()
        assertEquals(BannerReadiness.INITIALIZING, gate.read(AdPlatform.ADMOB, true))
        assertEquals(BannerReadiness.NOT_CONFIGURED, gate.read(AdPlatform.TOPON, true))
        gate.completed(AdPlatform.ADMOB, true)
        assertEquals(BannerReadiness.READY, gate.read(AdPlatform.ADMOB, true))
    }

    @Test
    fun `ready provider still reads request time consent`() {
        val gate = BannerProviderReadiness()
        gate.configure(topon)
        gate.completed(AdPlatform.TOPON, true)
        assertEquals(BannerReadiness.CONSENT_REQUIRED, gate.read(AdPlatform.TOPON, false))
        assertEquals(BannerReadiness.READY, gate.read(AdPlatform.TOPON, true))
    }

    @Test
    fun `completion before subscription and during first callback cannot be missed`() {
        val gate = BannerProviderReadiness()
        gate.configure(BiddingProviderConfig(admob, topon))
        gate.completed(AdPlatform.ADMOB, true)
        val admobChanges = mutableListOf<BannerReadiness>()
        gate.observe(AdPlatform.ADMOB) { admobChanges += gate.read(AdPlatform.ADMOB, true) }
        assertEquals(listOf(BannerReadiness.READY), admobChanges)
        val toponChanges = mutableListOf<BannerReadiness>()
        gate.observe(AdPlatform.TOPON) {
            toponChanges += gate.read(AdPlatform.TOPON, true)
            gate.completed(AdPlatform.TOPON, true)
        }
        assertEquals(listOf(BannerReadiness.INITIALIZING, BannerReadiness.READY), toponChanges)
    }

    @Test
    fun `disposal is idempotent and callback failures do not strand other pages`() {
        val gate = BannerProviderReadiness()
        gate.configure(admob)
        gate.observe(AdPlatform.ADMOB) { error("host callback") }
        var calls = 0
        val callback = { calls++; Unit }
        val removeFirst = gate.observe(AdPlatform.ADMOB, callback)
        val removeSecond = gate.observe(AdPlatform.ADMOB, callback)
        assertEquals(2, calls)
        removeFirst()
        removeFirst()
        gate.completed(AdPlatform.ADMOB, true)
        assertEquals(3, calls)
        removeSecond()
        gate.completed(AdPlatform.ADMOB, true)
        assertEquals(3, calls)
    }

    @Test
    fun `an observer removed by an earlier callback is not invoked from a stale snapshot`() {
        val gate = BannerProviderReadiness()
        gate.configure(admob)
        var removeSecond: () -> Unit = {}
        gate.observe(AdPlatform.ADMOB) {
            if (gate.read(AdPlatform.ADMOB, true) == BannerReadiness.READY) removeSecond()
        }
        var secondCalls = 0
        removeSecond = gate.observe(AdPlatform.ADMOB) { secondCalls++ }
        gate.completed(AdPlatform.ADMOB, true)
        assertEquals(1, secondCalls)
    }
}
