package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Coordinates the foreground window used by standalone and bidding auto app-open flows. */
internal class AutoAppOpenController<T>(
    private val isEnabled: () -> Boolean,
    private val isProviderReady: () -> Boolean,
    private val providerFailureReason: () -> String?,
    private val isAdAvailable: () -> Boolean,
    private val shouldIgnoreActivity: (Activity) -> Boolean = { false },
    private val beginOpportunity: () -> T,
    private val show: (Activity, T) -> Unit,
    private val fail: (T, String) -> Unit,
    private val noAdFailureReason: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MILLIS,
) {
    private var foregroundStartedAtMillis = 0L
    private var attempted = false
    private var checkScheduled = false
    private var pendingOpportunity: T? = null

    private val lifecycleListener = object : AdLifecycleMonitor.Listener {
        override fun onActivityResumed(activity: Activity) {
            schedule()
        }

        override fun onAppEnteredForeground(activity: Activity) {
            foregroundStartedAtMillis = clock()
            attempted = false
            if (!shouldIgnoreActivity(activity)) beginOpportunityIfNeeded()
            schedule()
        }

        override fun onAppEnteredBackground() {
            finishOpportunity("app_backgrounded_before_show")
        }
    }

    init {
        AdLifecycleMonitor.addListener(lifecycleListener)
    }

    fun onProviderInitialized() {
        if (!AdLifecycleMonitor.isAppInForeground) return
        if (AdLifecycleMonitor.currentActivity?.let(shouldIgnoreActivity) == true) return
        foregroundStartedAtMillis = clock()
        attempted = false
        beginOpportunityIfNeeded()
        schedule(delayMillis = 0L)
    }

    fun onAdAvailable() {
        schedule(delayMillis = 0L)
    }

    fun finishOpportunity(reason: String) {
        if (!isEnabled() || !isProviderReady() || attempted) return
        val opportunity = pendingOpportunity ?: return
        attempted = true
        pendingOpportunity = null
        fail(opportunity, reason)
    }

    private fun beginOpportunityIfNeeded() {
        if (!isEnabled() || !isProviderReady() || attempted || pendingOpportunity != null) return
        pendingOpportunity = beginOpportunity()
    }

    private fun schedule(delayMillis: Long = checkIntervalMillis) {
        if (!isEnabled() || !isProviderReady()) return
        if (!AdLifecycleMonitor.isAppInForeground || attempted || checkScheduled) return
        if (pendingOpportunity == null) return
        checkScheduled = true
        handler.postDelayed(
            {
                checkScheduled = false
                checkAndShow()
                if (!attempted) schedule()
            },
            delayMillis,
        )
    }

    private fun checkAndShow() {
        if (!AdLifecycleMonitor.isAppInForeground || attempted) return
        val elapsed = clock() - foregroundStartedAtMillis
        val activity = AdLifecycleMonitor.currentActivity
        val activityAvailable = activity != null && !activity.isFinishing && !activity.isDestroyed
        val activityInteractive = activityAvailable &&
            AdLifecycleMonitor.activityShowFailureReason(checkNotNull(activity)) == null
        val adAvailable = isAdAvailable()
        when (
            decideAutoAppOpenCheck(
                elapsedMillis = elapsed,
                activityAvailable = activityAvailable,
                activityInteractive = activityInteractive,
                adAvailable = adAvailable,
                windowMillis = windowMillis,
            )
        ) {
            AutoAppOpenCheck.SHOW -> {
                val opportunity = pendingOpportunity ?: return
                attempted = true
                pendingOpportunity = null
                show(checkNotNull(activity), opportunity)
                return
            }
            AutoAppOpenCheck.WAIT -> return
            AutoAppOpenCheck.FAIL_ACTIVITY_UNAVAILABLE,
            AutoAppOpenCheck.FAIL_WINDOW_EXPIRED,
            -> Unit
        }
        val reason = providerFailureReason()
            ?: if (!activityAvailable) "activity_not_available"
            else if (!adAvailable) noAdFailureReason
            else "app_open_window_expired"
        finishOpportunity(reason)
    }

    internal companion object {
        const val DEFAULT_WINDOW_MILLIS = 7_000L
        const val DEFAULT_CHECK_INTERVAL_MILLIS = 100L
    }
}

internal enum class AutoAppOpenCheck {
    WAIT,
    SHOW,
    FAIL_ACTIVITY_UNAVAILABLE,
    FAIL_WINDOW_EXPIRED,
}

internal fun decideAutoAppOpenCheck(
    elapsedMillis: Long,
    activityAvailable: Boolean,
    activityInteractive: Boolean,
    adAvailable: Boolean,
    windowMillis: Long = AutoAppOpenController.DEFAULT_WINDOW_MILLIS,
): AutoAppOpenCheck = when {
    elapsedMillis > windowMillis && !activityAvailable ->
        AutoAppOpenCheck.FAIL_ACTIVITY_UNAVAILABLE
    elapsedMillis > windowMillis -> AutoAppOpenCheck.FAIL_WINDOW_EXPIRED
    activityInteractive && adAvailable -> AutoAppOpenCheck.SHOW
    else -> AutoAppOpenCheck.WAIT
}
