package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdBlockReason
import com.cashcraft.ads.mediation.AdFrequencyPolicy
import com.cashcraft.ads.mediation.AdMainType
import com.cashcraft.ads.mediation.AdMainTypeQuota
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
@Config(manifest = Config.NONE, sdk = [28])
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
        RuntimeEnvironment.getApplication().getSharedPreferences("cashcraft_ads_usage", 0).edit().clear().commit()
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
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 1, 1),
            AdMainType.INTER to AdMainTypeQuota(true, 1, 1))
        assertPassed(checker.reserve("cold-open", typed(AdMainType.OPEN, "cold")))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("hot-open", typed(AdMainType.OPEN, "hot")))
        assertPassed(checker.reserve("save-inter", typed(AdMainType.INTER, "save")))
        checker.impression("cold-open", AdMainType.OPEN)
        checker.click(AdMainType.OPEN)
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall, AdMainType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdMainType.INTER))
        checker.release("save-inter")
        assertPassed(checker.check(typed(AdMainType.INTER, "back")))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdMainType.OPEN)))
    }

    @Test
    fun `v2 empty or partial map never falls back to legacy global limits`() {
        checker.impression("old")
        checker.click()
        checker.policy = typedPolicy().copy(frequency = AdFrequencyPolicy(true,
            dailyMaxShows = 0, dailyMaxClicks = 0, mainTypeQuotas = emptyMap()))
        AdMainType.entries.forEach { mainType ->
            assertPassed(checker.reserve(mainType.configKey, typed(mainType)))
            checker.impression(mainType.configKey, mainType)
            checker.click(mainType)
            assertPassed(checker.check(typed(mainType)))
            assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall, mainType))
        }
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall))
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 0, 0))
        assertPassed(checker.check(typed(AdMainType.OPEN)))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdMainType.INTER)))
    }

    @Test
    fun `v2 missing main type fails even with frequency off while shared loading skips counts`() {
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 0, 0))
        assertBlocked(AdBlockReason.INVALID_MAIN_TYPE, checker.check(inline))
        assertBlocked(AdBlockReason.INVALID_MAIN_TYPE, checker.reserve("missing", auto))
        assertPassed(checker.checkLoad())
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(enabled = false))
        assertBlocked(AdBlockReason.INVALID_MAIN_TYPE, checker.check(inline))
        checker.policy = checker.policy.copy(enabled = false)
        assertBlocked(AdBlockReason.GLOBAL_DISABLED, checker.checkLoad())
    }

    @Test
    fun `v2 zero click threshold wins over zero show threshold`() {
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 0, 0))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.reserve("zero", typed(AdMainType.INTER)))
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 0, 3))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("zero", typed(AdMainType.INTER)))
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 20, 0))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdMainType.INTER)))
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(false, 0, 0))
        assertPassed(checker.reserve("zero", typed(AdMainType.INTER)))
    }

    @Test
    fun `v2 malformed enabled thresholds fail closed without global fallback`() {
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 5, null))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdMainType.OPEN)))
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, null, 2))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.OPEN)))
    }

    @Test
    fun `v2 switches attribution and threshold updates retain actual callbacks and pending`() {
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(false, 0, 0))
        checker.impression("quota-off", AdMainType.INTER)
        checker.click(AdMainType.INTER)
        assertPassed(checker.reserve("off-pending", typed(AdMainType.INTER)))
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 2, 2))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.INTER)))
        checker.release("off-pending")
        assertPassed(checker.check(typed(AdMainType.INTER)))
        checker.policy = checker.policy.copy(frequency = checker.policy.frequency.copy(enabled = false))
        checker.impression("frequency-off", AdMainType.INTER)
        checker.click(AdMainType.INTER)
        assertPassed(checker.reserve("frequency-off-pending", typed(AdMainType.INTER)))
        checker.policy = checker.policy.copy(enabled = false)
        checker.impression("global-off", AdMainType.INTER)
        checker.click(AdMainType.INTER)
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 20, 3))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdMainType.INTER)))
        assertEquals(AdUsageStore.DailyUsage(3, 3), usage.dailyUsage(wall, AdMainType.INTER))
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 4, 4))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.INTER)))
        checker.release("frequency-off-pending")
        assertPassed(checker.check(typed(AdMainType.INTER)))
    }

    @Test
    fun `v2 same pending opportunity rechecks own type without consuming other type pending`() {
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 1, 2),
            AdMainType.INTER to AdMainTypeQuota(true, 1, 2))
        assertPassed(checker.reserve("open", typed(AdMainType.OPEN)))
        assertPassed(checker.reserve("inter", typed(AdMainType.INTER)))
        assertPassed(checker.reserve("open", typed(AdMainType.OPEN)))
        assertBlocked(AdBlockReason.INVALID_MAIN_TYPE, checker.reserve("open", typed(AdMainType.INTER)))
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 0, 2))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("open", typed(AdMainType.OPEN)))
        checker.release("open")
        checker.release("open")
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 1, 2))
        assertPassed(checker.reserve("replacement", typed(AdMainType.OPEN)))
    }

    @Test
    fun `v2 concurrent reservations permit one per type at last show quota`() {
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 1, 10),
            AdMainType.INTER to AdMainTypeQuota(true, 1, 10))
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { id -> pool.submit(Callable {
                val type = if (id % 2 == 0) AdMainType.OPEN else AdMainType.INTER
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                type to checker.reserve("typed-concurrent-$id", typed(type))
            }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            listOf(AdMainType.OPEN, AdMainType.INTER).forEach { type ->
                val typedResults = results.filter { it.first == type }.map { it.second }
                assertEquals(1, typedResults.count { it == AdPolicyCheckResult.Passed })
                assertEquals(3, typedResults.count { it == AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT) })
            }
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `v2 pending survives local midnight while late actual callbacks use callback date`() {
        checker.policy = typedPolicy(AdMainType.OPEN to AdMainTypeQuota(true, 1, 10))
        assertPassed(checker.reserve("midnight-open", typed(AdMainType.OPEN)))
        val yesterday = wall
        wall += 86_400_000L
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.OPEN)))
        checker.release("midnight-open")
        assertPassed(checker.check(typed(AdMainType.OPEN)))
        checker.impression("midnight-open", AdMainType.OPEN)
        checker.impression("midnight-open", AdMainType.OPEN)
        repeat(2) { checker.click(AdMainType.OPEN) }
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(yesterday, AdMainType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(1, 2), usage.dailyUsage(wall, AdMainType.OPEN))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.OPEN)))
        wall += 86_400_000L
        assertPassed(checker.check(typed(AdMainType.OPEN)))
        checker.impression("midnight-open", AdMainType.OPEN) // Same opportunity stays deduplicated across days.
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdMainType.OPEN))
    }

    @Test
    fun `v2 deep copies quota map without resetting usage or first enable time`() {
        val quotas = mutableMapOf(AdMainType.INTER to AdMainTypeQuota(true, 1, 3))
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, mainTypeQuotas = quotas))
        val enabledAt = usage.mainTypeUsageEnabledAtMillis
        quotas[AdMainType.INTER] = AdMainTypeQuota(false)
        quotas.clear()
        assertTrue(checker.policy.frequency.mainTypeQuotas!!.getValue(AdMainType.INTER).enabled)
        assertThrows(UnsupportedOperationException::class.java) {
            (checker.policy.frequency.mainTypeQuotas as MutableMap<AdMainType, AdMainTypeQuota>).clear()
        }
        checker.impression("stored-inter", AdMainType.INTER)
        wall += 1_000L
        usage = AdUsageStore(RuntimeEnvironment.getApplication(), wall)
        checker = newChecker()
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 1, 3))
        assertEquals(enabledAt, usage.mainTypeUsageEnabledAtMillis)
        assertEquals(AdUsageStore.DailyUsage(1, 0), usage.dailyUsage(wall, AdMainType.INTER))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.INTER)))
        assertPassed(checker.check(typed(AdMainType.OPEN)))
    }

    @Test
    fun `v2 keeps time conditions and rewarded exemptions independent of quotas`() {
        checker.policy = typedPolicy(AdMainType.REWARDED to AdMainTypeQuota(true, 5, 0)).copy(
            frequency = AdFrequencyPolicy(true, 120, 120,
                mainTypeQuotas = mapOf(AdMainType.REWARDED to AdMainTypeQuota(true, 5, 0))))
        checker.fullscreenClosed()
        assertBlocked(AdBlockReason.NEW_USER_PROTECTION, checker.check(typed(AdMainType.NATIVE)))
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT,
            checker.check(rewarded.copy(mainType = AdMainType.REWARDED)))
        wall += 120_000L
        assertPassed(checker.check(typed(AdMainType.NATIVE)))
        assertBlocked(AdBlockReason.FULLSCREEN_GAP, checker.check(auto.copy(mainType = AdMainType.OPEN)))
        assertPassed(checker.checkLoad())
    }

    @Test
    fun `v2 legacy migration preserves old counters date first launch and close`() {
        checker.impression("old")
        repeat(3) { checker.click() }
        checker.fullscreenClosed()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("cashcraft_ads_usage", 0)
        val oldValues = prefs.all.toMap()
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 1, 1))
        assertPassed(checker.check(typed(AdMainType.INTER)))
        checker.impression("new", AdMainType.OPEN)
        checker.click(AdMainType.OPEN)
        checker.impression("missing") // Broken v2 callback must not contaminate preserved legacy totals.
        checker.click()
        oldValues.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
        assertEquals(AdUsageStore.DailyUsage(1, 3), usage.dailyUsage(wall))
        assertEquals(AdUsageStore.DailyUsage(1, 1), usage.dailyUsage(wall, AdMainType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdMainType.INTER))
    }

    @Test
    fun `v2 blocked diagnostic includes identity counters pending thresholds and version`() {
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, 2, 3))
        checker.impression("actual", AdMainType.INTER)
        checker.click(AdMainType.INTER)
        checker.reserve("pending", typed(AdMainType.INTER, "save"))
        val message = checker.blockedDiagnostic("next", typed(AdMainType.INTER, "back"), AdBlockReason.DAILY_SHOW_LIMIT)
        listOf("policy_version=2", "mainType=inter", "opportunity_id=next", "position=back",
            "shows=1", "clicks=1", "pending=1", "show_threshold=2", "click_threshold=3").forEach {
            assertTrue("Missing $it in $message", message.contains(it))
        }
        val missing = checker.blockedDiagnostic("unknown", inline, AdBlockReason.INVALID_MAIN_TYPE)
        assertTrue(missing.contains("mainType=missing"))
        assertTrue(missing.contains("shows=0 clicks=0"))
    }

    @Test
    fun `v2 counters and pending arithmetic do not overflow large quotas`() {
        checker.policy = typedPolicy(AdMainType.INTER to AdMainTypeQuota(true, Long.MAX_VALUE, Long.MAX_VALUE))
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("cashcraft_ads_usage", 0)
        val day = java.time.Instant.ofEpochMilli(wall).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        prefs.edit().putLong("main_type_v2_${day}_inter_shows", Long.MAX_VALUE - 1).commit()
        assertPassed(checker.reserve("last", typed(AdMainType.INTER)))
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.reserve("overflow", typed(AdMainType.INTER)))
        checker.impression("last", AdMainType.INTER)
        checker.impression("unsolicited", AdMainType.INTER)
        assertEquals(Long.MAX_VALUE, usage.dailyUsage(wall, AdMainType.INTER).shows)
    }

    @Test
    fun `legacy typed requests still consume shared legacy quota and never activate v2 storage`() {
        checker.policy = limited(shows = 2, clicks = 2)
        assertPassed(checker.reserve("typed-open", typed(AdMainType.OPEN)))
        checker.impression("typed-open", AdMainType.OPEN)
        checker.click(AdMainType.OPEN)
        assertPassed(checker.reserve("typed-inter", typed(AdMainType.INTER)))
        checker.impression("typed-inter", AdMainType.INTER)
        checker.click(AdMainType.INTER)
        assertEquals(AdUsageStore.DailyUsage(2, 2), usage.dailyUsage(wall))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdMainType.OPEN))
        assertEquals(AdUsageStore.DailyUsage(0, 0), usage.dailyUsage(wall, AdMainType.INTER))
        assertEquals(null, usage.mainTypeUsageEnabledAtMillis)
        assertBlocked(AdBlockReason.DAILY_SHOW_LIMIT, checker.check(typed(AdMainType.NATIVE)))
        checker.policy = limited(shows = 3, clicks = 2)
        assertBlocked(AdBlockReason.DAILY_CLICK_LIMIT, checker.check(typed(AdMainType.BANNER)))
    }

    @Test
    fun `frequency policy preserves original five argument JVM constructor`() {
        val legacy = AdFrequencyPolicy::class.java.getConstructor(
            Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        ).newInstance(true, 120L, 60L, 20L, 3L)
        assertEquals(AdFrequencyPolicy(true, 120, 60, 20, 3), legacy)
        assertEquals(null, legacy.mainTypeQuotas)
    }

    private fun typed(mainType: AdMainType, position: String = "entry") =
        AdPolicyRequest(position, mainType = mainType)

    private fun typedPolicy(vararg quotas: Pair<AdMainType, AdMainTypeQuota>) =
        AdPolicy(frequency = AdFrequencyPolicy(enabled = true, mainTypeQuotas = mapOf(*quotas)))

    private fun newChecker() = AdPolicyChecker(usage, { wall }, { elapsed })

    private fun limited(shows: Long = Long.MAX_VALUE, clicks: Long = Long.MAX_VALUE) =
        AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = shows, dailyMaxClicks = clicks))

    private fun assertPassed(result: AdPolicyCheckResult) = assertEquals(AdPolicyCheckResult.Passed, result)

    private fun assertBlocked(reason: AdBlockReason, result: AdPolicyCheckResult) =
        assertEquals(AdPolicyCheckResult.Blocked(reason), result)
}
