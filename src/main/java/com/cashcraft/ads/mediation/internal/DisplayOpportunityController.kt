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
) {
    data class LoadSnapshot(val ready: Boolean, val settled: Boolean)

    private enum class State { WAITING, SDK_SHOW, FINISHED }
    private var state = State.WAITING
    private var started = false
    private var loadEnsured = false
    private var attempting = false
    private var sessionId: String? = null
    val attempt = FullScreenShowAttempt(isWaitingOpportunity = true)
    private val check = Runnable { check() }

    init {
        attempt.guard = ::finalFailure
        attempt.handoffGuard = { precondition?.invoke() }
        attempt.onCommitted = {
            state = State.SDK_SHOW
            cleanWaiting()
        }
    }

    fun start() {
        if (started || state != State.WAITING) return
        started = true
        if (timeoutMillis <= 0) return fail("invalid_timeout")
        precondition?.invoke()?.takeUnless { it == "sdk_initializing" }?.let { return fail(it) }
        FullScreenShowGate.reserve(attempt)?.let { return fail(it) }
        check()
    }

    fun sessionStarted(session: AdShowSession) {
        sessionId = session.sessionId
    }

    fun cancel(reason: String = "opportunity_cancelled") = fail(reason)

    private fun environmentFailure(): String? {
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
        return environmentFailure()
    }

    private fun check() {
        if (state != State.WAITING) return
        val reason = environmentFailure()
        if (state != State.WAITING) return
        if (reason != null && reason !in TRANSIENT_REASONS) return fail(reason)
        if (!loadEnsured && nowMillis() - startedAtMillis < timeoutMillis && precondition?.invoke() == null) {
            loadEnsured = true
            ensureLoaded?.invoke()
        }
        if (state != State.WAITING) return
        val expired = nowMillis() - startedAtMillis >= timeoutMillis
        val (ready, settled) = loadSnapshot?.invoke() ?: return
        if (!attempting && reason == null && ready && (settled || expired)) {
            if (state != State.WAITING) return
            // The deadline limits waiting for bidders, not preparation of the selected ad.
            // Provider guards still recheck cancellation, scene, host, consent and ad validity.
            attempting = true
            show?.invoke(attempt) { finish(it) }
        }
        if (state != State.WAITING) return
        if (expired) return fail("wait_timeout")
        if (settled && !ready) return fail("ad_load_failed")
        val remaining = timeoutMillis - (nowMillis() - startedAtMillis)
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
        runCatching { callback?.invoke(result) }
    }

    private fun cleanWaiting() {
        unschedule(check)
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
