package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdRewardResult
import com.cashcraft.ads.mediation.AdShowResult

/** Scheduler and environment are functions so deadline and reentrancy checks run in plain JUnit. */
internal class DisplayOpportunityController(
    private val startedAtMillis: Long,
    private val timeoutMillis: Long,
    private val nowMillis: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private var precondition: (() -> String?)?,
    private var sceneValid: (() -> Boolean)?,
    private var hostFailure: (() -> String?)?,
    private var loadSnapshot: (() -> LoadSnapshot)?,
    private var ensureLoaded: (() -> Unit)?,
    private var show: ((FullScreenShowAttempt, (AdRewardResult) -> Unit) -> Unit)?,
    private var onCleanup: (() -> Unit)?,
    private var onResult: ((AdRewardResult) -> Unit)?,
    private val showWhenEmpty: Boolean = false,
    private val preferAvailableAfterResume: Boolean = true,
    private val trace: (String) -> Unit = {},
    private var onLoadingChanged: ((Boolean) -> Unit)? = null,
    private val preferCachedImmediately: Boolean = false,
    policy: AdPolicyAttempt? = null,
    private val excludeWaitingTime: () -> Boolean = { false },
) {
    data class LoadSnapshot(val ready: Boolean, val settled: Boolean)

    private enum class State { WAITING, SDK_SHOW, FINISHED }
    private var state = State.WAITING
    private var started = false
    private var loadEnsured = false
    private var attempting = false
    private var loading = false
    private var pausedAtMillis: Long? = null
    private var pausedMillis = 0L
    private var preferAvailableOnResume = false
    private var sessionId: String? = null
    private var excludedAtMillis: Long? = null
    private var excludedMillis = 0L
    val attempt = FullScreenShowAttempt(isWaitingOpportunity = true)
    private val check = Runnable { check() }

    init {
        attempt.policy = policy
        attempt.guard = ::finalFailure
        attempt.handoffGuard = { precondition?.invoke() }
        attempt.onCommitted = {
            state = State.SDK_SHOW
            trace("已提交展示，等待曝光或结束回调。")
            cleanWaiting()
        }
    }

    fun start() {
        if (started || state != State.WAITING) return
        started = true
        if (timeoutMillis < 0) return fail("invalid_timeout")
        attempt.policy?.check()?.let {
            if (it is com.cashcraft.ads.mediation.AdPolicyCheckResult.Blocked) return fail(it.reason.code)
        }
        precondition?.invoke()?.takeUnless { it == "sdk_initializing" }?.let { return fail(it) }
        FullScreenShowGate.reserve(attempt)?.let { return fail(it) }
        check()
    }

    fun sessionStarted(session: AdShowSession) {
        sessionId = session.sessionId
    }

    fun cancel(reason: String = "opportunity_cancelled") = fail(reason)

    /** Pause the opportunity, not the shared SDK load or cache. Main thread only. */
    fun pause() {
        if (state != State.WAITING || pausedAtMillis != null) return
        pausedAtMillis = nowMillis()
        unschedule(check)
        trace("暂停等待，已等待 ${elapsedMillis()} 毫秒（不含后台时间）。")
    }

    fun resume() {
        if (state != State.WAITING) return
        val pausedAt = pausedAtMillis ?: return
        pausedMillis += nowMillis() - pausedAt
        pausedAtMillis = null
        preferAvailableOnResume = preferAvailableAfterResume
        trace("恢复等待，已等待 ${elapsedMillis()} 毫秒（不含后台时间）。")
        if (started) check()
    }

    /** Wake after an SDK callback without reentering its load/show stack. */
    fun inventoryChanged() {
        if (!started || state != State.WAITING || pausedAtMillis != null || attempting) return
        unschedule(check)
        schedule(check, 0L)
    }

    fun elapsedMillis(): Long {
        val foregroundMillis = (pausedAtMillis ?: nowMillis()) - startedAtMillis - pausedMillis
        if (timeoutMillis > 0 && excludeWaitingTime()) {
            if (excludedAtMillis == null) excludedAtMillis = foregroundMillis
        } else {
            excludedAtMillis?.let { excludedMillis += foregroundMillis - it }
            excludedAtMillis = null
        }
        return ((excludedAtMillis ?: foregroundMillis) - excludedMillis).coerceAtLeast(0)
    }

    private fun environmentFailure(): String? {
        attempt.policy?.check()?.let {
            if (it is com.cashcraft.ads.mediation.AdPolicyCheckResult.Blocked) return it.reason.code
        }
        precondition?.invoke()?.takeUnless { it == "sdk_initializing" }?.let { return it }
        val valid = try { sceneValid?.invoke() == true } catch (_: Exception) {
            return "scene_validation_failed"
        }
        if (state != State.WAITING) return "opportunity_cancelled"
        if (!valid) return "scene_invalid"
        return hostFailure?.invoke() ?: precondition?.invoke()
    }

    private fun finalFailure(): String? {
        if (state != State.WAITING) return "opportunity_cancelled"
        if (pausedAtMillis != null) return "activity_not_resumed"
        return environmentFailure()
    }

    private fun check() {
        unschedule(check)
        if (state != State.WAITING || pausedAtMillis != null) return
        val reason = environmentFailure()
        if (state != State.WAITING || pausedAtMillis != null) return
        if (reason != null && reason !in TRANSIENT_REASONS) return fail(reason)
        if (!attempting) setLoading(true)
        // A host callback can synchronously cancel or leave the scene.
        if (state != State.WAITING || pausedAtMillis != null) return
        if (!loadEnsured && elapsedMillis() < timeoutMillis && precondition?.invoke() == null) {
            loadEnsured = true
            ensureLoaded?.invoke()
        }
        if (state != State.WAITING || pausedAtMillis != null) return
        val expired = elapsedMillis() >= timeoutMillis
        val (ready, settled) = loadSnapshot?.invoke() ?: return
        val useAvailable = preferAvailableOnResume || (ready && preferCachedImmediately)
        if (reason == null) preferAvailableOnResume = false
        if (!attempting && reason == null && (ready || showWhenEmpty) && (settled || expired || useAvailable)) {
            if (state != State.WAITING) return
            // The deadline limits waiting for bidders, not preparation of the selected ad.
            // Provider guards still recheck cancellation, scene, host, consent and ad validity.
            attempting = true
            trace("等待结束：${if (expired) "已到等待时限" else if (settled) "候选结果已齐" else "恢复后使用现有缓存"}；${if (ready) "已有可用广告" else "暂无可用广告"}；已等待 ${elapsedMillis()} 毫秒（不含后台时间）。")
            setLoading(false)
            if (state != State.WAITING || pausedAtMillis != null) return
            show?.invoke(attempt) { finish(it) }
        }
        if (state != State.WAITING || pausedAtMillis != null) return
        if (showWhenEmpty && attempting) return
        if (expired) return fail("wait_timeout")
        if (settled && !ready) return fail("ad_load_failed")
        val remaining = timeoutMillis - elapsedMillis()
        if (remaining <= 0) return fail("wait_timeout")
        schedule(check, minOf(100L, remaining))
    }

    private fun fail(reason: String) {
        if (state != State.WAITING) return
        // An already-created session still needs show_fail after an event listener cancels.
        finish(AdRewardResult(false, AdShowResult.Failed(reason), sessionId), reason)
    }

    private fun finish(result: AdRewardResult, cancelledReason: String? = null) {
        if (state == State.FINISHED) return
        state = State.FINISHED
        cancelledReason?.let(attempt::invalidate)
        FullScreenShowGate.release(attempt)
        attempt.guard = null
        attempt.handoffGuard = null
        attempt.onCommitted = null
        val callback = onResult
        onResult = null
        cleanWaiting()
        val resolved = attempt.policy?.result(result.showResult) ?: result.showResult
        attempt.policy?.complete()
        runCatching { callback?.invoke(result.copy(showResult = resolved)) }
    }

    private fun setLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        runCatching { onLoadingChanged?.invoke(value) }
    }

    private fun cleanWaiting() {
        unschedule(check)
        setLoading(false)
        onLoadingChanged = null
        precondition = null
        sceneValid = null
        hostFailure = null
        loadSnapshot = null
        ensureLoaded = null
        show = null
        val cleanup = onCleanup
        onCleanup = null
        cleanup?.invoke()
    }

    private companion object {
        val TRANSIENT_REASONS = setOf(
            "sdk_initializing", "activity_awaiting_first_resume", "activity_window_not_attached",
            "activity_window_not_focused",
        )
    }
}
