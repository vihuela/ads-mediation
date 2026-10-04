package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdSceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [ShadowMMKV::class])
class AdUsageStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = ZonedDateTime.parse("2026-10-04T12:00:00+08:00[Asia/Taipei]").toInstant().toEpochMilli()
    private lateinit var store: AdUsageStore

    @Before fun setUp() {
        ShadowMMKV.reset()
        store = AdUsageStore(context, now - 120_000L)
    }

    @Test fun `typed reads are pure and do not activate or migrate usage`() {
        store.impression(now)
        store.click(now)
        val prefs = ShadowMMKV.values
        val before = prefs.toMap()
        AdSceneType.entries.forEach {
            assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(now, it))
        }
        assertNull(store.sceneTypeUsageEnabledAtMillis)
        assertEquals(before, prefs)
    }

    @Test fun `activation is persisted once across reconstructed stores and typed callbacks`() {
        store.enableSceneTypeUsage(now)
        store.enableSceneTypeUsage(now + 1_000L)
        store = AdUsageStore(context, now + 2_000L)
        store.enableSceneTypeUsage(now + 3_000L)
        store.click(now + 4_000L, AdSceneType.INTER)
        assertEquals(now, store.sceneTypeUsageEnabledAtMillis)
        assertEquals(now - 120_000L, store.firstLaunchTimeMillis)
    }

    @Test fun `typed first callback activates without changing any legacy key`() {
        store.impression(now)
        repeat(2) { store.click(now) }
        store.fullscreenClosed(now - 1_000L)
        val prefs = ShadowMMKV.values
        val legacy = prefs.toMap()
        store.impression(now, AdSceneType.OPEN)
        repeat(3) { store.click(now, AdSceneType.INTER) }
        legacy.forEach { (key, value) -> assertEquals(value, prefs[key]) }
        assertEquals(now, store.sceneTypeUsageEnabledAtMillis)
        assertEquals(AdUsageStore.DailyUsage(1, 2), store.dailyUsage(now))
        assertEquals(AdUsageStore.DailyUsage(1, 0), store.dailyUsage(now, AdSceneType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 3), store.dailyUsage(now, AdSceneType.INTER))
    }

    @Test fun `v2 local date keys preserve earlier date counts through midnight and rollback`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"))
            val beforeMidnight = ZonedDateTime.parse("2026-10-04T23:59:59+08:00[Asia/Taipei]")
                .toInstant().toEpochMilli()
            store.impression(beforeMidnight, AdSceneType.OPEN)
            store.click(beforeMidnight, AdSceneType.OPEN)
            val afterMidnight = beforeMidnight + 1_000L
            assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(afterMidnight, AdSceneType.OPEN))
            store.impression(afterMidnight, AdSceneType.OPEN)
            store.click(afterMidnight, AdSceneType.INTER)
            store = AdUsageStore(context, afterMidnight)
            assertEquals(AdUsageStore.DailyUsage(1, 0), store.dailyUsage(afterMidnight, AdSceneType.OPEN))
            assertEquals(AdUsageStore.DailyUsage(0, 1), store.dailyUsage(afterMidnight, AdSceneType.INTER))
            assertEquals(AdUsageStore.DailyUsage(1, 1), store.dailyUsage(beforeMidnight, AdSceneType.OPEN))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun `concurrent real clicks from store instances are all counted`() {
        val other = AdUsageStore(context, now + 1_000L)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..4).map { number -> pool.submit(Callable {
                check(start.await(5, TimeUnit.SECONDS))
                val target = if (number % 2 == 0) store else other
                repeat(20) { target.click(now, AdSceneType.INTER) }
            }) }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(AdUsageStore.DailyUsage(0, 80), store.dailyUsage(now, AdSceneType.INTER))
            assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(now, AdSceneType.OPEN))
            assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(now))
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
