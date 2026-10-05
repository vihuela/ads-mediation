package com.cashcraft.ads.mediation.internal

import android.os.SystemClock
import com.cashcraft.ads.mediation.AdBlockReason
import com.cashcraft.ads.mediation.AdSceneType
import com.cashcraft.ads.mediation.AdPolicy
import com.cashcraft.ads.mediation.AdPolicyCheckResult
import java.util.Collections

/** userInitiated is set only by the host's explicitly requested rewarded flow. */
internal data class AdPolicyRequest(
    val position: String? = null,
    val fullscreen: Boolean = false,
    val userInitiated: Boolean = false,
    val sceneType: AdSceneType? = null,
)

/** Own one checker for the Ads lifetime; reservation IDs must identify distinct impressions. */
internal class AdPolicyChecker(
    private val usage: AdUsageStore,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedClockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private val pending = mutableMapOf<String, AdSceneType?>()
    private val impressed = mutableSetOf<String>()
    private var snapshot = AdPolicy()

    // On restart, translate the persisted wall age to a monotonic anchor once. A wall
    // rollback starts at age zero, then elapsed time advances so the block is bounded.
    private val persistedClose = usage.lastFullscreenCloseMillis
    private var closeAnchorElapsed: Long? = persistedClose?.let { elapsedClockMillis() }
    private var closeAgeAtAnchor: Long = persistedClose?.let { age(wallClockMillis(), it) } ?: 0L

    var policy: AdPolicy
        get() = synchronized(lock) { snapshot }
        set(value) = synchronized(lock) {
            snapshot = value.copy(
                platforms = Collections.unmodifiableMap(HashMap(value.platforms)),
                positions = Collections.unmodifiableMap(HashMap(value.positions)),
                frequency = value.frequency.copy(sceneQuotas = value.frequency.sceneQuotas?.let { quotas ->
                    Collections.unmodifiableMap(quotas.mapValues { (_, quota) -> quota.copy() })
                }),
            )
            if (snapshot.frequency.sceneQuotas != null) usage.enableSceneTypeUsage(wallClockMillis())
        }

    /** Pure eligibility query: no quota consumption, callbacks, logs, or persistence. */
    fun check(request: AdPolicyRequest, ownPendingId: String? = null): AdPolicyCheckResult = synchronized(lock) {
        checkLocked(request, ownPending = ownPendingId != null && ownPendingId in pending)
    }

    /** Check and occupy one show quota atomically, even while frequency checks are off. */
    fun reserve(id: String, request: AdPolicyRequest): AdPolicyCheckResult = synchronized(lock) {
        // Binding an already-reserved opportunity must observe policy updates, while
        // excluding its own pending show. Loading continues to include all reservations.
        if (id in pending && pending[id] != request.sceneType) return@synchronized blocked(AdBlockReason.INVALID_SCENE_TYPE)
        checkLocked(request, ownPending = id in pending).also {
            if (it == AdPolicyCheckResult.Passed && id !in impressed) pending[id] = request.sceneType
        }
    }

    fun release(id: String) {
        synchronized(lock) { pending.remove(id) }
    }

    /** Late/unsolicited actual impressions still count, including after release or midnight. */
    fun impression(id: String, sceneType: AdSceneType? = null) {
        synchronized(lock) {
            // V2 callbacks without ownership must not contaminate retained legacy history.
            if (snapshot.frequency.sceneQuotas != null && sceneType == null) return
            if (impressed.add(id)) {
                pending.remove(id)
                usage.impression(wallClockMillis(), if (snapshot.frequency.sceneQuotas != null) sceneType else null)
            }
        }
    }

    /** Every actual click callback counts; session-level click deduplication is incorrect. */
    fun click(sceneType: AdSceneType? = null) {
        synchronized(lock) {
            if (snapshot.frequency.sceneQuotas != null && sceneType == null) return
            usage.click(wallClockMillis(), if (snapshot.frequency.sceneQuotas != null) sceneType else null)
        }
    }

    fun fullscreenClosed() {
        synchronized(lock) {
            usage.fullscreenClosed(wallClockMillis())
            closeAnchorElapsed = elapsedClockMillis()
            closeAgeAtAnchor = 0L
        }
    }

    fun checkLoad(): AdPolicyCheckResult = synchronized(lock) { checkLocked(null) }

    private fun checkLocked(request: AdPolicyRequest?, ownPending: Boolean = false): AdPolicyCheckResult {
        val current = snapshot
        if (!current.enabled) return blocked(AdBlockReason.GLOBAL_DISABLED)
        if (request?.position?.let { current.positions[it] == false } == true) {
            return blocked(AdBlockReason.POSITION_DISABLED)
        }
        val frequency = current.frequency
        val quotas = frequency.sceneQuotas
        if (quotas != null && request != null && request.sceneType == null) {
            return blocked(AdBlockReason.INVALID_SCENE_TYPE)
        }
        if (!frequency.enabled) return AdPolicyCheckResult.Passed
        val now = wallClockMillis()
        if (request != null && !request.userInitiated) {
            if (age(now, usage.firstLaunchTimeMillis) < millis(frequency.newUserDelaySeconds)) {
                return blocked(AdBlockReason.NEW_USER_PROTECTION)
            }
            // Open entries bypass the gap, but their real close still updates the shared anchor.
            if (request.fullscreen && request.sceneType != AdSceneType.OPEN) closeAnchorElapsed?.let { anchor ->
                val elapsed = age(elapsedClockMillis(), anchor)
                val gap = millis(frequency.fullscreenGapSeconds)
                if (closeAgeAtAnchor < gap && elapsed < gap - closeAgeAtAnchor) {
                    return blocked(AdBlockReason.FULLSCREEN_GAP)
                }
            }
        }
        if (quotas != null) {
            // Shared preload stock has no entry identity and cannot use a format's quota.
            val sceneType = request?.sceneType ?: return AdPolicyCheckResult.Passed
            val quota = quotas[sceneType] ?: return AdPolicyCheckResult.Passed
            if (!quota.enabled) return AdPolicyCheckResult.Passed
            val daily = usage.dailyUsage(now, sceneType)
            val pendingShows = pending.values.count { it == sceneType }.toLong() - if (ownPending) 1L else 0L
            // Missing enabled thresholds fail closed; a malformed host policy is not unlimited.
            val maxClicks = quota.dailyMaxClicks ?: 0L
            if (daily.clicks >= maxClicks) return blocked(AdBlockReason.DAILY_CLICK_LIMIT)
            val maxShows = quota.dailyMaxShows ?: 0L
            if (daily.shows >= maxShows || pendingShows >= maxShows - daily.shows) {
                return blocked(AdBlockReason.DAILY_SHOW_LIMIT)
            }
            return AdPolicyCheckResult.Passed
        }
        val daily = usage.dailyUsage(now)
        val pendingShows = pending.size.toLong() - if (ownPending) 1L else 0L
        // Subtract instead of adding pending to avoid overflow at Long.MAX_VALUE.
        if (daily.shows >= frequency.dailyMaxShows ||
            pendingShows >= frequency.dailyMaxShows - daily.shows) {
            return blocked(AdBlockReason.DAILY_SHOW_LIMIT)
        }
        if (daily.clicks >= frequency.dailyMaxClicks) return blocked(AdBlockReason.DAILY_CLICK_LIMIT)
        return AdPolicyCheckResult.Passed
    }

    /** Called only for the first terminal block of an attempt, never for pure eligibility polls. */
    fun blockedDiagnostic(id: String, request: AdPolicyRequest, reason: AdBlockReason): String = synchronized(lock) {
        val frequency = snapshot.frequency
        val v2 = frequency.sceneQuotas != null
        val sceneType = request.sceneType
        val daily = if (v2 && sceneType == null) AdUsageStore.DailyUsage(0, 0)
            else usage.dailyUsage(wallClockMillis(), if (v2) sceneType else null)
        val quota = sceneType?.let { frequency.sceneQuotas?.get(it) }
        val pendingShows = if (v2) pending.values.count { it == sceneType } else pending.size
        "ad_policy_blocked policy_version=${if (v2) 2 else 1} sceneType=${sceneType?.configKey ?: "missing"}" +
            " opportunity_id=${id.oneLine()} position=${request.position?.oneLine()} reason=${reason.code}" +
            " shows=${daily.shows} clicks=${daily.clicks} pending=$pendingShows" +
            " own_pending=${id in pending} frequency_enabled=${frequency.enabled}" +
            " quota_enabled=${if (v2) quota?.enabled ?: false else frequency.enabled}" +
            " show_threshold=${if (v2) quota?.dailyMaxShows else frequency.dailyMaxShows}" +
            " click_threshold=${if (v2) quota?.dailyMaxClicks else frequency.dailyMaxClicks}"
    }

    private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ')

    private fun blocked(reason: AdBlockReason) = AdPolicyCheckResult.Blocked(reason)

    private fun millis(seconds: Long): Long = when {
        seconds <= 0L -> 0L
        seconds > Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
        else -> seconds * 1_000L
    }

    private fun age(now: Long, then: Long): Long = when {
        now < then -> 0L
        now - then < 0L -> Long.MAX_VALUE
        else -> now - then
    }
}
