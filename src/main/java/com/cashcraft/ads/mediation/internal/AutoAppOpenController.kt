package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cashcraft.ads.mediation.internal.nativeads.NativeInteractions

/** Coordinates the foreground window used by standalone and bidding auto app-open flows. */
internal class AutoAppOpenController<T>(
    private val isEnabled: () -> Boolean,
    private val isProviderReady: () -> Boolean,
    private val providerFailureReason: () -> String?,
    private val isAdAvailable: () -> Boolean,
    private val shouldIgnoreActivity: (Activity) -> Boolean =
        { isAdSdkActivityClassName(it.javaClass.name) },
    private val beginOpportunity: () -> T,
    private val show: (Activity, T) -> Unit,
    private val fail: (T, String) -> Unit,
    private val noAdFailureReason: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MILLIS,
    private val onQualified: (Activity, T) -> String? = { _, _ -> null },
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
            attempted = NativeInteractions.blocksAutoAppOpen()
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
        if (attempted) return
        val opportunity = pendingOpportunity ?: return
        attempted = true
        pendingOpportunity = null
        fail(opportunity, reason)
    }

    private fun beginOpportunityIfNeeded() {
        if (!isEnabled() || !isProviderReady() || attempted || pendingOpportunity != null) return
        if (NativeInteractions.blocksAutoAppOpen()) { attempted = true; return }
        pendingOpportunity = beginOpportunity()
    }

    private fun schedule(delayMillis: Long = checkIntervalMillis) {
        if (!isEnabled() || !isProviderReady()) {
            finishOpportunity("provider_not_ready")
            return
        }
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
        if (!isEnabled() || !isProviderReady()) { finishOpportunity("provider_not_ready"); return }
        if (NativeInteractions.blocksAutoAppOpen()) {
            finishOpportunity("native_interaction")
            return
        }
        val elapsed = clock() - foregroundStartedAtMillis
        val activity = AdLifecycleMonitor.currentActivity
        val activityAvailable = activity != null && !activity.isFinishing && !activity.isDestroyed &&
            !shouldIgnoreActivity(activity)
        val activityInteractive = activityAvailable &&
            AdLifecycleMonitor.activityShowFailureReason(checkNotNull(activity)) == null
        if (activityInteractive && elapsed <= windowMillis) {
            val opportunity = pendingOpportunity ?: return
            onQualified(checkNotNull(activity), opportunity)?.let { finishOpportunity(it); return }
            // Qualification callbacks may synchronously background or cancel this opportunity.
            if (attempted || pendingOpportunity !== opportunity || !AdLifecycleMonitor.isAppInForeground) return
        }
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

// 只限制自动机会；SDK落地页不是业务宿主，手动全屏资格仍由原show gate判断。
internal fun isAdSdkActivityClassName(name: String): Boolean =
    name.startsWith("com.google.android.libraries.ads.mobile.sdk.") ||
        name.startsWith("com.thinkup.") ||
        name.startsWith("com.bytedance.sdk.openadsdk.") ||
        name.startsWith("com.facebook.ads.") ||
        name.startsWith("com.smartdigimkt.sdk.") ||
        name.startsWith("com.mbridge.msdk.")

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
