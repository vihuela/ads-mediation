package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdBlockReason
import com.cashcraft.ads.mediation.AdFrequencyPolicy
import com.cashcraft.ads.mediation.AdSceneType
import com.cashcraft.ads.mediation.AdSceneQuota
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdPolicy
import com.cashcraft.ads.mediation.AdPolicyCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
class AdPolicyCheckerTest {
    private var wall = ZonedDateTime.parse("2026-10-04T12:00:00+08:00[Asia/Taipei]").toInstant().toEpochMilli()
    private var elapsed = 10_000L
    private lateinit var usage: AdUsageStore
    private lateinit var checker: AdPolicyChecker
    private val auto = AdPolicyRequest("home", fullscreen = true)
    private val inline = AdPolicyRequest("footer")
    private val rewarded = AdPolicyRequest("reward", fullscreen = true, userInitiated = true)

    @Before
    fun setUp() {
        ShadowMMKV.reset()
        usage = AdUsageStore(RuntimeEnvironment.getApplication(), wall)
        checker = newChecker()
    }

    @Test fun `new user protection covers inline ads while fullscreen interval does not`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, 120, 120))
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(inline))
        wall += 120_000
        elapsed += 120_000
        checker.fullscreenClosed()
        assertPassed(checker.check(inline))
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
    }

    @Test
    fun `defaults pass and checks do not consume quota`() {
        assertEquals(AdPolicy(), checker.policy)
        assertPassed(checker.check(auto))
        checker.policy = limited(shows = 1)
        repeat(3) { assertPassed(checker.check(auto)); assertPassed(checker.checkLoad()) }
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall))
        assertPassed(checker.reserve("one", auto))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(auto))
    }

    @Test
    fun `reasons follow global position new user gap shows clicks priority`() {
        checker.fullscreenClosed()
        checker.impression("actual")
        checker.click()
        val frequency = AdFrequencyPolicy(true, 60, 60, 1, 1)
        checker.policy = AdPolicy(false, positions = mapOf("home" to false), frequency = frequency)
        assertBlocked(AdBlockReason.GLOBAL_DISABLED, checker.check(auto))
        checker.policy = checker.policy.copy(enabled = true)
        assertBlocked(AdBlockReason.POSITION_DISABLED, checker.check(auto))
        checker.policy = checker.policy.copy(positions = emptyMap())
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(auto))
        wall += 60_000 // New-user wall age passes; monotonic close age remains zero.
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
        elapsed += 60_000
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(auto))
        checker.policy = checker.policy.copy(frequency = frequency.copy(dailyMaxShows = 2))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(auto))
        checker.policy = checker.policy.copy(frequency = frequency.copy(dailyMaxShows = 2, dailyMaxClicks = 2))
        assertPassed(checker.check(auto))
    }

    @Test
    fun `zero limits prohibit display and loading`() {
        checker.policy = limited(shows = 0)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(inline))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
        checker.policy = limited(clicks = 0)
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.reserve("zero", rewarded))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.checkLoad())
        checker.policy = limited(shows = 1)
        assertPassed(checker.reserve("zero", inline)) // A rejected reservation did not occupy quota.
    }

    @Test
    fun `switches off continue recording actual callbacks pending and close`() {
        checker.policy = limited(shows = 1, clicks = 1).copy(enabled = false)
        checker.impression("off")
        checker.click()
        checker.fullscreenClosed()
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(false, 0, 60, 1, 1))
        assertPassed(checker.reserve("pending", inline))
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(enabled = true))
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(inline))
        checker.release("pending")
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(dailyMaxShows = 2))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(inline))
    }

    @Test
    fun `pending alone survives switching frequency on`() {
        assertPassed(checker.reserve("off-pending", auto))
        checker.policy = limited(shows = 1)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
        checker.release("off-pending")
        assertPassed(checker.checkLoad())
    }

    @Test
    fun `same ID is idempotent and release restores only pending quota`() {
        checker.policy = limited(shows = 1)
        assertPassed(checker.reserve("one", auto))
        assertPassed(checker.reserve("one", auto))
        checker.release("one")
        checker.release("one")
        assertPassed(checker.reserve("two", auto))
        checker.impression("two")
        checker.impression("two")
        checker.release("two")
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("two", auto))
        assertEquals(AdUsageStore.DailyUsage(1, 0), usage.dailyUsage(wall))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
    }

    @Test
    fun `same pending ID rechecks current policy but excludes only its own show`() {
        checker.policy = limited(shows = 1)
        assertPassed(checker.reserve("bind", auto))
        assertPassed(checker.reserve("bind", auto))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
        checker.policy = checker.policy.copy(enabled = false)
        assertBlocked(AdBlockReason.GLOBAL_DISABLED, checker.reserve("bind", auto))
        checker.policy = checker.policy.copy(enabled = true, positions = mapOf("home" to false))
        assertBlocked(AdBlockReason.POSITION_DISABLED, checker.reserve("bind", auto))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, newUserDelaySeconds = 60))
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.reserve("bind", auto))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, fullscreenGapSeconds = 60))
        checker.fullscreenClosed()
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.reserve("bind", auto))
        checker.policy = limited(shows = 0)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("bind", auto))
        checker.policy = limited(shows = 1, clicks = 1)
        checker.click()
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.reserve("bind", auto))
        checker.policy = limited(shows = 2, clicks = 2)
        assertPassed(checker.reserve("other", inline))
        checker.policy = limited(shows = 1, clicks = 2)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("bind", auto))
        checker.release("other")
        assertPassed(checker.reserve("bind", auto))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
    }

    @Test
    fun `concurrent distinct reservations cannot oversubscribe one show`() {
        checker.policy = limited(shows = 1)
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map { id -> pool.submit(Callable {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                checker.reserve("concurrent-$id", inline)
            }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val values = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, values.count { it == AdPolicyCheckResult.Passed })
            assertEquals(7, values.count { it == AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT) })
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `pending spans midnight and impression is attributed to callback day`() {
        checker.policy = limited(shows = 1)
        assertPassed(checker.reserve("midnight", auto))
        wall += 86_400_000L
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("second", auto))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
        checker.impression("midnight")
        checker.impression("midnight")
        assertEquals(AdUsageStore.DailyUsage(1, 0), usage.dailyUsage(wall))
        checker = newChecker()
        checker.policy = limited(shows = 1)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
        wall += 86_400_000L
        assertPassed(checker.checkLoad())
    }

    @Test
    fun `release after midnight frees pending but a late impression still counts`() {
        checker.policy = limited(shows = 1)
        checker.reserve("late", inline)
        wall += 86_400_000L
        checker.release("late")
        assertPassed(checker.checkLoad())
        checker.impression("late")
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
    }

    @Test
    fun `multiple actual clicks count and reset on the local callback day`() {
        checker.policy = limited(clicks = 2)
        checker.click()
        assertPassed(checker.checkLoad())
        checker.click()
        assertEquals(AdUsageStore.DailyUsage(0, 2), usage.dailyUsage(wall))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.checkLoad())
        checker.click() // An already displayed ad may still be clicked after the threshold.
        assertEquals(3L, usage.dailyUsage(wall).clicks)
        wall += 86_400_000L
        assertPassed(checker.checkLoad())
        checker.click()
        assertEquals(AdUsageStore.DailyUsage(0, 1), usage.dailyUsage(wall))
    }

    @Test
    fun `rewarded exempts only new user and gap and its close affects automatic fullscreen`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, 120, 60, 1, 1))
        checker.fullscreenClosed()
        assertPassed(checker.check(rewarded))
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(inline))
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(auto))
        checker.policy = checker.policy.copy(positions = mapOf("reward" to false))
        assertBlocked(AdBlockReason.POSITION_DISABLED, checker.check(rewarded))
        checker.policy = checker.policy.copy(enabled = false)
        assertBlocked(AdBlockReason.GLOBAL_DISABLED, checker.check(rewarded))
        checker.policy = checker.policy.copy(enabled = true, positions = emptyMap())
        checker.impression("reward")
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(rewarded))
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(dailyMaxShows = 2))
        checker.click()
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(rewarded))
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(dailyMaxClicks = 2))
        wall += 120_000L
        elapsed += 120_000L
        checker.fullscreenClosed() // Real rewarded close has no exemption when recording.
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
        assertPassed(checker.check(rewarded))
    }

    @Test
    fun `load does not invent a position or apply new user and gap`() {
        checker.policy = AdPolicy(positions = mapOf("home" to false),
            frequency = AdFrequencyPolicy(true, 120, 120))
        checker.fullscreenClosed()
        assertPassed(checker.checkLoad())
        assertBlocked(AdBlockReason.POSITION_DISABLED, checker.check(auto))
        checker.policy = checker.policy.copy(enabled = false)
        assertBlocked(AdBlockReason.GLOBAL_DISABLED, checker.checkLoad())
    }

    @Test
    fun `policy update snapshots both maps and preserves counts and reservations`() {
        val platforms = mutableMapOf(AdPlatform.ADMOB to false)
        val positions = mutableMapOf("home" to false)
        checker.policy = AdPolicy(platforms = platforms, positions = positions)
        platforms[AdPlatform.ADMOB] = true
        positions["home"] = true
        assertFalse(checker.policy.platforms.getValue(AdPlatform.ADMOB))
        assertBlocked(AdBlockReason.POSITION_DISABLED, checker.check(auto))
        assertThrows(UnsupportedOperationException::class.java) {
            (checker.policy.positions as MutableMap<String, Boolean>)["home"] = true
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (checker.policy.platforms as MutableMap<AdPlatform, Boolean>)[AdPlatform.TOPON] = false
        }
        checker.policy = limited(shows = 3)
        checker.impression("already")
        checker.reserve("pending", inline)
        checker.policy = limited(shows = 2)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.checkLoad())
        checker.release("pending")
        assertPassed(checker.checkLoad())
        assertEquals(1L, usage.dailyUsage(wall).shows)
    }

    @Test
    fun `reconstructed store retains first launch counts and close without SDK init reset`() {
        val firstLaunch = usage.firstLaunchTimeMillis
        checker.impression("stored")
        checker.click()
        checker.fullscreenClosed()
        wall += 30_000L
        elapsed = 0L // Simulated process clock; no monotonic timestamp is persisted.
        usage = AdUsageStore(RuntimeEnvironment.getApplication(), wall)
        checker = newChecker()
        assertEquals(firstLaunch, usage.firstLaunchTimeMillis)
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, 60, 60))
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(auto))
        wall += 30_000L
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
        elapsed += 30_000L
        assertPassed(checker.check(auto))
    }

    @Test
    fun `wall jumps do not change in process close gap and rollback after restart is bounded`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, fullscreenGapSeconds = 60))
        checker.fullscreenClosed()
        val closeWall = wall
        wall += 86_400_000L
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
        elapsed += 60_000L
        wall = closeWall - 86_400_000L
        assertPassed(checker.check(auto))
        usage = AdUsageStore(RuntimeEnvironment.getApplication(), wall)
        checker = newChecker()
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, fullscreenGapSeconds = 60))
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
        elapsed += 60_000L
        assertPassed(checker.check(auto))
    }

    @Test
    fun `callback day uses local midnight rather than UTC or rolling 24 hours`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"))
            wall = ZonedDateTime.parse("2026-10-04T23:59:59+08:00[Asia/Taipei]").toInstant().toEpochMilli()
            checker.impression("today")
            checker.click()
            assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall))
            wall += 1_000L
            assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall))
            checker.impression("tomorrow")
            assertEquals(AdUsageStore.DailyUsage(1, 0), usage.dailyUsage(wall))
        } finally { TimeZone.setDefault(original) }
    }

    @Test
    fun `very large duration values do not overflow to a pass`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, newUserDelaySeconds = Long.MAX_VALUE))
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(auto))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, fullscreenGapSeconds = Long.MAX_VALUE))
        checker.fullscreenClosed()
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto))
    }

    @Test
    fun `v2 quotas and pending shows belong to entry type across positions`() {
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 1, 1),
            AdSceneType.INTER to AdSceneQuota(true, 1, 1))
        assertPassed(checker.reserve("cold-open", typed(AdSceneType.OPEN, "cold")))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("hot-open", typed(AdSceneType.OPEN, "hot")))
        assertPassed(checker.reserve("save-inter", typed(AdSceneType.INTER, "save")))
        checker.impression("cold-open", AdSceneType.OPEN)
        checker.click(AdSceneType.OPEN)
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall, AdSceneType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdSceneType.INTER))
        checker.release("save-inter")
        assertPassed(checker.check(typed(AdSceneType.INTER, "back")))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdSceneType.OPEN)))
    }

    @Test
    fun `v2 empty or partial map never falls back to legacy global limits`() {
        checker.impression("old")
        checker.click()
        checker.policy = typedPolicy().copy(frequency = AdFrequencyPolicy(true,
            dailyMaxShows = 0, dailyMaxClicks = 0, sceneQuotas = emptyMap()))
        AdSceneType.entries.forEach { sceneType ->
            assertPassed(checker.reserve(sceneType.configKey, typed(sceneType)))
            checker.impression(sceneType.configKey, sceneType)
            checker.click(sceneType)
            assertPassed(checker.check(typed(sceneType)))
            assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall, sceneType))
        }
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall))
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 0, 0))
        assertPassed(checker.check(typed(AdSceneType.OPEN)))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdSceneType.INTER)))
    }

    @Test
    fun `v2 missing main type fails even with frequency off while shared loading skips counts`() {
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 0, 0))
        assertBlocked(AdBlockReason.INVALID_SCENE_TYPE, checker.check(inline))
        assertBlocked(AdBlockReason.INVALID_SCENE_TYPE, checker.reserve("missing", auto))
        assertPassed(checker.checkLoad())
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(enabled = false))
        assertBlocked(AdBlockReason.INVALID_SCENE_TYPE, checker.check(inline))
        checker.policy = checker.policy.copy(enabled = false)
        assertBlocked(AdBlockReason.GLOBAL_DISABLED, checker.checkLoad())
    }

    @Test
    fun `v2 zero click threshold wins over zero show threshold`() {
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 0, 0))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.reserve("zero", typed(AdSceneType.INTER)))
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 0, 3))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("zero", typed(AdSceneType.INTER)))
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 20, 0))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdSceneType.INTER)))
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(false, 0, 0))
        assertPassed(checker.reserve("zero", typed(AdSceneType.INTER)))
    }

    @Test
    fun `v2 malformed enabled thresholds fail closed without global fallback`() {
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 5, null))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdSceneType.OPEN)))
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, null, 2))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.OPEN)))
    }

    @Test
    fun `v2 switches attribution and threshold updates retain actual callbacks and pending`() {
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(false, 0, 0))
        checker.impression("quota-off", AdSceneType.INTER)
        checker.click(AdSceneType.INTER)
        assertPassed(checker.reserve("off-pending", typed(AdSceneType.INTER)))
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 2, 2))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.INTER)))
        checker.release("off-pending")
        assertPassed(checker.check(typed(AdSceneType.INTER)))
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(enabled = false))
        checker.impression("frequency-off", AdSceneType.INTER)
        checker.click(AdSceneType.INTER)
        assertPassed(checker.reserve("frequency-off-pending", typed(AdSceneType.INTER)))
        checker.policy = checker.policy.copy(enabled = false)
        checker.impression("global-off", AdSceneType.INTER)
        checker.click(AdSceneType.INTER)
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 20, 3))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdSceneType.INTER)))
        assertEquals(AdUsageStore.DailyUsage(3, 3), usage.dailyUsage(wall, AdSceneType.INTER))
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 4, 4))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.INTER)))
        checker.release("frequency-off-pending")
        assertPassed(checker.check(typed(AdSceneType.INTER)))
    }

    @Test
    fun `v2 same pending opportunity rechecks own type without consuming other type pending`() {
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 1, 2),
            AdSceneType.INTER to AdSceneQuota(true, 1, 2))
        assertPassed(checker.reserve("open", typed(AdSceneType.OPEN)))
        assertPassed(checker.reserve("inter", typed(AdSceneType.INTER)))
        assertPassed(checker.reserve("open", typed(AdSceneType.OPEN)))
        assertBlocked(AdBlockReason.INVALID_SCENE_TYPE, checker.reserve("open", typed(AdSceneType.INTER)))
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 0, 2))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("open", typed(AdSceneType.OPEN)))
        checker.release("open")
        checker.release("open")
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 1, 2))
        assertPassed(checker.reserve("replacement", typed(AdSceneType.OPEN)))
    }

    @Test
    fun `v2 concurrent reservations permit one per type at last show quota`() {
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 1, 10),
            AdSceneType.INTER to AdSceneQuota(true, 1, 10))
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { id -> pool.submit(Callable {
                val type = if (id % 2 == 0) AdSceneType.OPEN else AdSceneType.INTER
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                type to checker.reserve("typed-concurrent-$id", typed(type))
            }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            listOf(AdSceneType.OPEN, AdSceneType.INTER).forEach { type ->
                val typedResults = results.filter { it.first == type }.map { it.second }
                assertEquals(1, typedResults.count { it == AdPolicyCheckResult.Passed })
                assertEquals(3, typedResults.count { it == AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT) })
            }
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `v2 pending survives local midnight while late actual callbacks use callback date`() {
        checker.policy = typedPolicy(AdSceneType.OPEN to AdSceneQuota(true, 1, 10))
        assertPassed(checker.reserve("midnight-open", typed(AdSceneType.OPEN)))
        val yesterday = wall
        wall += 86_400_000L
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.OPEN)))
        checker.release("midnight-open")
        assertPassed(checker.check(typed(AdSceneType.OPEN)))
        checker.impression("midnight-open", AdSceneType.OPEN)
        checker.impression("midnight-open", AdSceneType.OPEN)
        repeat(2) { checker.click(AdSceneType.OPEN) }
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(yesterday, AdSceneType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(1, 2), usage.dailyUsage(wall, AdSceneType.OPEN))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.OPEN)))
        wall += 86_400_000L
        assertPassed(checker.check(typed(AdSceneType.OPEN)))
        checker.impression("midnight-open", AdSceneType.OPEN) // Same opportunity stays deduplicated across days.
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdSceneType.OPEN))
    }

    @Test
    fun `v2 deep copies quota map without resetting usage or first enable time`() {
        val quotas = mutableMapOf(AdSceneType.INTER to AdSceneQuota(true, 1, 3))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, sceneQuotas = quotas))
        val enabledAt = usage.sceneTypeUsageEnabledAtMillis
        quotas[AdSceneType.INTER] = AdSceneQuota(false)
        quotas.clear()
        assertTrue(checker.policy.frequency.sceneQuotas!!.getValue(AdSceneType.INTER).enabled)
        assertThrows(UnsupportedOperationException::class.java) {
            (checker.policy.frequency.sceneQuotas as MutableMap<AdSceneType, AdSceneQuota>).clear()
        }
        checker.impression("stored-inter", AdSceneType.INTER)
        wall += 1_000L
        usage = AdUsageStore(RuntimeEnvironment.getApplication(), wall)
        checker = newChecker()
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 1, 3))
        assertEquals(enabledAt, usage.sceneTypeUsageEnabledAtMillis)
        assertEquals(AdUsageStore.DailyUsage(1, 0), usage.dailyUsage(wall, AdSceneType.INTER))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.INTER)))
        assertPassed(checker.check(typed(AdSceneType.OPEN)))
    }

    @Test
    fun `v2 keeps time conditions and rewarded exemptions independent of quotas`() {
        checker.policy = typedPolicy(AdSceneType.REWARDED to AdSceneQuota(true, 5, 0)).copy(
            frequency = AdFrequencyPolicy(true, 120, 120,
                sceneQuotas = mapOf(AdSceneType.REWARDED to AdSceneQuota(true, 5, 0))))
        checker.fullscreenClosed()
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(typed(AdSceneType.NATIVE)))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT,
            checker.check(rewarded.copy(sceneType = AdSceneType.REWARDED)))
        wall += 120_000L
        assertPassed(checker.check(typed(AdSceneType.NATIVE)))
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto.copy(sceneType = AdSceneType.OPEN)))
        assertPassed(checker.checkLoad())
    }

    @Test
    fun `v2 legacy migration preserves old counters date first launch and close`() {
        checker.impression("old")
        repeat(3) { checker.click() }
        checker.fullscreenClosed()
        val prefs = ShadowMMKV.values
        val oldValues = prefs.toMap()
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 1, 1))
        assertPassed(checker.check(typed(AdSceneType.INTER)))
        checker.impression("new", AdSceneType.OPEN)
        checker.click(AdSceneType.OPEN)
        checker.impression("missing") // Broken v2 callback must not contaminate preserved legacy totals.
        checker.click()
        oldValues.forEach { (key, value) -> assertEquals(value, prefs[key]) }
        assertEquals(AdUsageStore.DailyUsage(1, 3), usage.dailyUsage(wall))
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall, AdSceneType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdSceneType.INTER))
    }

    @Test
    fun `v2 blocked diagnostic includes identity counters pending thresholds and version`() {
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, 2, 3))
        checker.impression("actual", AdSceneType.INTER)
        checker.click(AdSceneType.INTER)
        checker.reserve("pending", typed(AdSceneType.INTER, "save"))
        val message = checker.blockedDiagnostic("next", typed(AdSceneType.INTER, "back"), AdBlockReason.DAILY_SHOW_LIMIT)
        listOf("policy_version=2", "sceneType=inter", "opportunity_id=next", "position=back",
            "shows=1", "clicks=1", "pending=1", "show_threshold=2", "click_threshold=3").forEach {
            assertTrue("Missing $it in $message", message.contains(it))
        }
        val missing = checker.blockedDiagnostic("unknown", inline, AdBlockReason.INVALID_SCENE_TYPE)
        assertTrue(missing.contains("sceneType=missing"))
        assertTrue(missing.contains("shows=0 clicks=0"))
    }

    @Test
    fun `v2 counters and pending arithmetic do not overflow large quotas`() {
        checker.policy = typedPolicy(AdSceneType.INTER to AdSceneQuota(true, Long.MAX_VALUE, Long.MAX_VALUE))
        val prefs = ShadowMMKV.values
        val day = java.time.Instant.ofEpochMilli(wall).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        prefs["scene_type_${day}_inter_shows"] = Long.MAX_VALUE - 1
        assertPassed(checker.reserve("last", typed(AdSceneType.INTER)))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("overflow", typed(AdSceneType.INTER)))
        checker.impression("last", AdSceneType.INTER)
        checker.impression("unsolicited", AdSceneType.INTER)
        assertEquals(Long.MAX_VALUE, usage.dailyUsage(wall, AdSceneType.INTER).shows)
    }

    @Test
    fun `legacy typed requests still consume shared legacy quota and never activate v2 storage`() {
        checker.policy = limited(shows = 2, clicks = 2)
        assertPassed(checker.reserve("typed-open", typed(AdSceneType.OPEN)))
        checker.impression("typed-open", AdSceneType.OPEN)
        checker.click(AdSceneType.OPEN)
        assertPassed(checker.reserve("typed-inter", typed(AdSceneType.INTER)))
        checker.impression("typed-inter", AdSceneType.INTER)
        checker.click(AdSceneType.INTER)
        assertEquals(AdUsageStore.DailyUsage(2, 2), usage.dailyUsage(wall))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdSceneType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdSceneType.INTER))
        assertEquals(null, usage.sceneTypeUsageEnabledAtMillis)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdSceneType.NATIVE)))
        checker.policy = limited(shows = 3, clicks = 2)
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdSceneType.BANNER)))
    }

    @Test
    fun `frequency policy preserves original five argument JVM constructor`() {
        val legacy = AdFrequencyPolicy::class.java.getConstructor(
            Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        ).newInstance(true, 120L, 60L, 20L, 3L)
        assertEquals(AdFrequencyPolicy(true, 120, 60, 20, 3), legacy)
        assertEquals(null, legacy.sceneQuotas)
    }

    private fun typed(sceneType: AdSceneType, position: String = "entry") =
        AdPolicyRequest(position, sceneType = sceneType)

    private fun typedPolicy(vararg quotas: Pair<AdSceneType, AdSceneQuota>) =
        AdPolicy(frequency = AdFrequencyPolicy(enabled = true, sceneQuotas = mapOf(*quotas)))

    private fun newChecker() = AdPolicyChecker(usage, { wall }, { elapsed })

    private fun limited(shows: Long = Long.MAX_VALUE, clicks: Long = Long.MAX_VALUE) =
        AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = shows, dailyMaxClicks = clicks))

    private fun assertPassed(result: AdPolicyCheckResult) = assertEquals(AdPolicyCheckResult.Passed, result)

    private fun assertBlocked(reason: AdBlockReason, result: AdPolicyCheckResult) =
        assertEquals(AdPolicyCheckResult.Blocked(reason), result)
}
