package com.cashcraft.ads.mediation.internal

import android.app.Activity
import com.cashcraft.ads.mediation.AdPolicyCheckResult

/** All entry points share one owner, including the time spent waiting for an ad. */
internal object FullScreenShowGate {
    private var owner: FullScreenShowAttempt? = null
    private var showing = false

    val isAnyAdShowing: Boolean
        @Synchronized get() = showing

    fun tryAcquire(
        activity: Activity,
        providerFailureReason: String?,
        attempt: FullScreenShowAttempt,
    ): String? = attempt.failureReason()
        ?: providerFailureReason
        ?: AdLifecycleMonitor.activityShowFailureReason(activity)
        ?: reserve(attempt)

    fun commit(
        activity: Activity,
        providerFailureReason: String?,
        attempt: FullScreenShowAttempt,
        finalCheck: () -> String? = { null },
    ): String? = tryAcquire(activity, providerFailureReason, attempt)
        ?: finalCheck()
        ?: commit(attempt)

    @Synchronized
    fun reserve(attempt: FullScreenShowAttempt): String? = when {
        owner === attempt -> null
        owner != null -> if (showing) "another_full_screen_ad_showing" else "request_in_progress"
        else -> { owner = attempt; null }
    }

    @Synchronized
    fun commit(attempt: FullScreenShowAttempt): String? {
        attempt.handoffFailure()?.let { return it }
        if (owner !== attempt) return "opportunity_cancelled"
        attempt.policy?.reserve()?.let { if (it is AdPolicyCheckResult.Blocked) return it.reason.code }
        showing = true
        attempt.committed()
        return null
    }

    @Synchronized
    fun release(attempt: FullScreenShowAttempt) {
        if (owner !== attempt) return
        owner = null
        showing = false
    }
}

/** Carries a waiting owner's final guard into the provider's actual SDK call. Main thread only. */
internal class FullScreenShowAttempt(val isWaitingOpportunity: Boolean = false) {
    var policy: AdPolicyAttempt? = null
    var guard: (() -> String?)? = null
    var handoffGuard: (() -> String?)? = null
    var onCommitted: (() -> Unit)? = null
    var onAborted: (() -> Unit)? = null
    var isCommitted = false
        private set
    private var invalidReason: String? = null
    private var completed = false

    fun failureReason(): String? {
        invalidReason?.let { return it }
        policy?.check()?.let { if (it is AdPolicyCheckResult.Blocked) return it.reason.code }
        val reason = guard?.invoke()
        // A host predicate may synchronously cancel this attempt.
        return invalidReason ?: reason
    }

    fun handoffFailure(): String? = invalidReason ?: handoffGuard?.invoke()

    fun invalidate(reason: String) {
        if (isCommitted || invalidReason != null) return
        invalidReason = reason
        val cleanup = onAborted
        onAborted = null
        cleanup?.invoke()
    }

    fun committed() {
        isCommitted = true
        guard = null
        handoffGuard = null
        onAborted = null
        val callback = onCommitted
        onCommitted = null
        callback?.invoke()
    }

    fun complete(): Boolean {
        if (completed) return false
        completed = true
        guard = null
        handoffGuard = null
        onCommitted = null
        val cleanup = onAborted
        onAborted = null
        if (!isCommitted) cleanup?.invoke()
        FullScreenShowGate.release(this)
        policy?.complete()
        return true
    }
}
