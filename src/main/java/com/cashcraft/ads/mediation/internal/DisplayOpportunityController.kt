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
    private var isReady: (() -> Boolean)?,
    private var ensureLoaded: (() -> Unit)?,
    private var show: ((FullScreenShowAttempt, (AdRewardResult) -> Unit) -> Unit)?,
    private var onCleanup: (() -> Unit)?,
    private var onResult: ((AdRewardResult) -> Unit)?,
) {
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
        attempt.handoffGuard = {
            if (nowMillis() - startedAtMillis >= timeoutMillis) "wait_timeout"
            else precondition?.invoke()
        }
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

    fun cancel(reason: String = "opportunity_cancelled") {
        if (state == State.WAITING) fail(reason)
    }

    private fun environmentFailure(): String? {
        if (nowMillis() - startedAtMillis >= timeoutMillis) return "wait_timeout"
        precondition?.invoke()?.takeUnless { it == "sdk_initializing" }?.let { return it }
        val valid = try { sceneValid?.invoke() == true } catch (_: Exception) {
            return "scene_validation_failed"
        }
        if (state != State.WAITING) return "opportunity_cancelled"
        if (!valid) return "scene_invalid"
        // Include time spent inside a host predicate in the original deadline.
        if (nowMillis() - startedAtMillis >= timeoutMillis) return "wait_timeout"
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
        if (!loadEnsured && precondition?.invoke() == null) {
            loadEnsured = true
            ensureLoaded?.invoke()
        }
        if (state != State.WAITING) return
        if (!attempting && reason == null && isReady?.invoke() == true) {
            if (state != State.WAITING) return
            attempting = true
            show?.invoke(attempt) { finish(it) }
        }
        if (state == State.WAITING) {
            val remaining = timeoutMillis - (nowMillis() - startedAtMillis)
            if (remaining <= 0) fail("wait_timeout") else schedule(check, minOf(100L, remaining))
        }
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
        isReady = null
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
