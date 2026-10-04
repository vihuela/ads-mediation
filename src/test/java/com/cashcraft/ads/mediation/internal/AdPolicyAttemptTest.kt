package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdBlockInfo
import com.cashcraft.ads.mediation.AdBlockReason
import com.cashcraft.ads.mediation.AdFrequencyPolicy
import com.cashcraft.ads.mediation.AdMainType
import com.cashcraft.ads.mediation.AdMainTypeQuota
import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.AdPolicy
import com.cashcraft.ads.mediation.AdPolicyCheckResult
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdPolicyAttemptTest {
    private val previous = listOf("config", "policyChecker", "policyUpdatePosted").associateWith {
        ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it)
    }
    private var wall = ZonedDateTime.parse("2026-10-04T12:00:00+08:00[Asia/Taipei]").toInstant().toEpochMilli()
    private lateinit var store: AdUsageStore
    private lateinit var checker: AdPolicyChecker
    private val blocks = mutableListOf<AdBlockInfo>()

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("cashcraft_ads_usage", 0).edit().clear().commit()
        store = AdUsageStore(context, wall - 120_000L)
        checker = AdPolicyChecker(store, { wall }, { wall }).apply {
            policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, mainTypeQuotas = emptyMap()))
        }
        ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", checker)
        ReflectionHelpers.setStaticField(Ads::class.java, "policyUpdatePosted", true)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST), onAdBlocked = { blocks += it }, autoShowAppOpen = false,
        ))
        ShadowLog.clear()
    }

    @After fun restore() {
        previous.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
    }

    @Test fun `fallback opportunity keeps open entry identity despite inter or native positions`() {
        val attempt = AdPolicyAttempt(AdPolicyRequest("IV_open_fallback", fullscreen = true, mainType = AdMainType.OPEN))
        assertEquals(AdPolicyCheckResult.Passed, attempt.reserve())
        assertEquals(AdPolicyCheckResult.Passed, attempt.check())
        assertEquals(AdPolicyCheckResult.Passed, attempt.reserve()) // Candidate/provider handoff reuses the opportunity.
        repeat(3) { attempt.impression() }
        repeat(2) { attempt.click() }
        attempt.complete()
        assertEquals(AdUsageStore.DailyUsage(1, 2), store.dailyUsage(wall, AdMainType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(wall, AdMainType.INTER))
        assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(wall, AdMainType.NATIVE_FULLSCREEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(wall))
    }

    @Test fun `late full native callbacks after release keep original inter identity across page change`() {
        val old = AdPolicyAttempt(AdPolicyRequest("NA_New_Guide_Full", fullscreen = true, mainType = AdMainType.INTER))
        old.reserve()
        old.complete()
        val current = AdPolicyAttempt(AdPolicyRequest("current_page", mainType = AdMainType.NATIVE))
        current.reserve()
        current.impression()
        wall += 86_400_000L
        old.impression()
        old.impression()
        repeat(3) { old.click() }
        assertEquals(AdUsageStore.DailyUsage(1, 3), store.dailyUsage(wall, AdMainType.INTER))
        assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(wall, AdMainType.NATIVE))
        assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(wall, AdMainType.NATIVE_FULLSCREEN))
        assertEquals(wall, store.lastFullscreenCloseMillis)
        current.complete()
    }

    @Test fun `already impressed banner remains accepted at own quota and counts every click`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true,
            mainTypeQuotas = mapOf(AdMainType.BANNER to AdMainTypeQuota(true, 1, 2))))
        val banner = AdPolicyAttempt(AdPolicyRequest("banner", mainType = AdMainType.BANNER))
        assertEquals(AdPolicyCheckResult.Passed, banner.reserve())
        repeat(3) { banner.impression() } // SDK refreshes share this host display cycle.
        repeat(3) { banner.click() }
        assertEquals(AdPolicyCheckResult.Passed, banner.check())
        banner.complete()
        banner.click() // Every real callback still counts after page release.
        assertEquals(AdUsageStore.DailyUsage(1, 4), store.dailyUsage(wall, AdMainType.BANNER))
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_CLICK_LIMIT),
            AdPolicyAttempt(AdPolicyRequest("next_banner", mainType = AdMainType.BANNER)).reserve())
    }

    @Test fun `missing type cannot be inferred from position and reports and logs block once`() {
        val attempt = AdPolicyAttempt(AdPolicyRequest("IV_save"))
        val denied = AdPolicyCheckResult.Blocked(AdBlockReason.INVALID_MAIN_TYPE)
        assertEquals(denied, attempt.check())
        assertEquals(denied, attempt.reserve())
        assertEquals(denied, attempt.check())
        attempt.complete()
        attempt.complete()
        assertEquals(listOf(AdBlockInfo("IV_save", AdBlockReason.INVALID_MAIN_TYPE)), blocks)
        val logs = ShadowLog.getLogsForTag("AdsPolicy")
        assertEquals(1, logs.size)
        assertTrue(logs.single().msg.contains("mainType=missing"))
        assertTrue(logs.single().msg.contains("policy_version=2"))
        assertTrue(logs.single().msg.contains("opportunity_id="))
    }

    @Test fun `concurrent callback delivery deduplicates only impressions`() {
        val attempt = AdPolicyAttempt(AdPolicyRequest("native", mainType = AdMainType.NATIVE))
        attempt.reserve()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { pool.submit(Callable {
                check(start.await(5, TimeUnit.SECONDS))
                attempt.impression()
                attempt.click()
            }) }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(AdUsageStore.DailyUsage(1, 8), store.dailyUsage(wall, AdMainType.NATIVE))
        } finally { start.countDown(); pool.shutdownNow(); attempt.complete() }
    }
}
