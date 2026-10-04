package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.cashcraft.ads.mediation.internal.nativeads.NativeInteractions

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
    private val startedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val awaitingFirstResume = WeakHashMap<Activity, Boolean>()

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    val currentActivity: Activity?
        get() = resumedActivity.get()

    /** Allows a position-only request in onCreate to bind before the first resume. */
    val requestActivity: Activity?
        get() {
            val creating = awaitingFirstResume.entries.filter { it.value }
            return when (creating.size) {
                0 -> currentActivity
                1 -> creating.single().key
                else -> null // Never guess which new Activity owns a position-only request.
            }
        }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            awaitingFirstResume[activity] = false
            startedActivities.add(activity)
            resumedActivity = WeakReference(activity)
            if (!isAppInForeground) enterForeground(activity)
            listeners.forEach { it.onActivityResumed(activity) }
        }

        override fun onActivityPaused(activity: Activity) {
            awaitingFirstResume[activity] = false
            if (resumedActivity.get() === activity) resumedActivity.clear()
            listeners.forEach { it.onActivityPaused(activity) }
            mainHandler.postDelayed(::markBackgroundIfActivityRemainsPaused, PAUSE_GRACE_MILLIS)
        }

        override fun onActivityStarted(activity: Activity) {
            startedActivities.add(activity)
            if (startedActivities.size == 1) enterForeground(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            awaitingFirstResume[activity] = false
            // Installation can happen after another Activity's onStart. Its later onStop
            // must not remove the foreground state of an Activity we did observe.
            if (startedActivities.remove(activity) && startedActivities.isEmpty()) enterBackground()
        }

        override fun onActivityDestroyed(activity: Activity) {
            awaitingFirstResume.remove(activity)
            startedActivities.remove(activity)
            if (resumedActivity.get() === activity) resumedActivity.clear()
            listeners.forEach { it.onActivityDestroyed(activity) }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            awaitingFirstResume[activity] = true
        }
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

    /** A request from onCreate can wait for its first resume without being moved to another host. */
    fun activityWaitFailureReason(activity: Activity): String? = when {
        activity.isFinishing || activity.isDestroyed -> "activity_not_available"
        currentActivity !== activity && awaitingFirstResume[activity] == true -> "activity_awaiting_first_resume"
        else -> activityShowFailureReason(activity)
    }


    private fun seed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        startedActivities.add(activity)
        isAppInForeground = true
    }

    private fun enterForeground(activity: Activity) {
        if (isAppInForeground) return
        isAppInForeground = true
        NativeInteractions.onForeground()
        listeners.forEach { it.onAppEnteredForeground(activity) }
    }

    private fun enterBackground() {
        if (!isAppInForeground) return
        isAppInForeground = false
        NativeInteractions.onBackground()
        listeners.forEach(Listener::onAppEnteredBackground)
    }

    private fun markBackgroundIfActivityRemainsPaused() {
        if (
            isAppInForeground &&
            currentActivity == null &&
            !FullScreenShowGate.isAnyAdShowing
        ) {
            // A paused Activity is still started until onStop; keep lifecycle accounting intact.
            enterBackground()
        }
    }

    private const val PAUSE_GRACE_MILLIS = 100L
}
