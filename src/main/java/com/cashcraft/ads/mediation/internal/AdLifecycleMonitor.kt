package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/** Single source of truth for host activity and foreground state. */
internal object AdLifecycleMonitor {
    interface Listener {
        fun onActivityResumed(activity: Activity) = Unit
        fun onActivityPaused(activity: Activity) = Unit
        fun onActivityDestroyed(activity: Activity) = Unit
        fun onAppEnteredForeground(activity: Activity) = Unit
        fun onAppEnteredBackground() = Unit
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var installedApplication: Application? = null
    private var resumedActivity = WeakReference<Activity>(null)
    private var startedActivityCount = 0

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    val currentActivity: Activity?
        get() = resumedActivity.get()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivity = WeakReference(activity)
            if (!isAppInForeground) enterForeground(activity)
            listeners.forEach { it.onActivityResumed(activity) }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumedActivity.get() === activity) resumedActivity.clear()
            listeners.forEach { it.onActivityPaused(activity) }
            mainHandler.postDelayed(::markBackgroundIfActivityRemainsPaused, PAUSE_GRACE_MILLIS)
        }

        override fun onActivityStarted(activity: Activity) {
            startedActivityCount++
            if (startedActivityCount == 1) enterForeground(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0) enterBackground()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumedActivity.get() === activity) resumedActivity.clear()
            listeners.forEach { it.onActivityDestroyed(activity) }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    fun install(application: Application, initialActivity: Activity? = null) {
        check(installedApplication == null || installedApplication === application) {
            "Ad lifecycle monitor is already installed on another Application"
        }
        if (installedApplication == null) {
            installedApplication = application
            application.registerActivityLifecycleCallbacks(callbacks)
        }
        initialActivity?.takeUnless { it.isFinishing || it.isDestroyed }?.let(::seed)
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Stable manual-show preconditions shared by every mediation provider. */
    fun activityShowFailureReason(activity: Activity): String? = when {
        activity.isFinishing || activity.isDestroyed -> "activity_not_available"
        !isAppInForeground -> "app_not_in_foreground"
        currentActivity !== activity -> "activity_not_resumed"
        !activity.window.decorView.isAttachedToWindow -> "activity_window_not_attached"
        !activity.hasWindowFocus() -> "activity_window_not_focused"
        else -> null
    }

    private fun seed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        startedActivityCount = startedActivityCount.coerceAtLeast(1)
        isAppInForeground = true
    }

    private fun enterForeground(activity: Activity) {
        if (isAppInForeground) return
        isAppInForeground = true
        listeners.forEach { it.onAppEnteredForeground(activity) }
    }

    private fun enterBackground() {
        if (!isAppInForeground) return
        isAppInForeground = false
        listeners.forEach(Listener::onAppEnteredBackground)
    }

    private fun markBackgroundIfActivityRemainsPaused() {
        if (
            isAppInForeground &&
            currentActivity == null &&
            !FullScreenShowGate.isAnyAdShowing
        ) {
            startedActivityCount = 0
            enterBackground()
        }
    }

    private const val PAUSE_GRACE_MILLIS = 100L
}
