package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.app.Application
import org.junit.Before
import org.junit.After
import org.robolectric.util.ReflectionHelpers
import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FullScreenShowGateTest {
    @Before
    @After
    fun resetLifecycle() {
        val application = ReflectionHelpers.getStaticField<Application?>(AdLifecycleMonitor::class.java, "installedApplication")
        val callbacks = ReflectionHelpers.getStaticField<Application.ActivityLifecycleCallbacks>(AdLifecycleMonitor::class.java, "callbacks")
        application?.unregisterActivityLifecycleCallbacks(callbacks)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "installedApplication", null)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "resumedActivity", WeakReference<Activity>(null))
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", false)
        ReflectionHelpers.getStaticField<MutableSet<*>>(AdLifecycleMonitor::class.java, "startedActivities").clear()
    }

    @Test
    fun `providers share one owner and stale completion cannot release another show`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val admobSession = Any()
        val topOnSession = Any()
        AdLifecycleMonitor.install(host.get().application, host.get())
        try {
            assertNull(AdLifecycleMonitor.activityShowFailureReason(host.get()))
            assertNull(FullScreenShowGate.tryAcquire(host.get(), null, admobSession))
            assertEquals("another_full_screen_ad_showing", FullScreenShowGate.tryAcquire(host.get(), null, topOnSession))
            FullScreenShowGate.release(topOnSession)
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            FullScreenShowGate.release(admobSession)
            assertNull(FullScreenShowGate.tryAcquire(host.get(), null, topOnSession))
            FullScreenShowGate.release(admobSession)
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            FullScreenShowGate.release(topOnSession)
            assertFalse(FullScreenShowGate.isAnyAdShowing)
        } finally {
            FullScreenShowGate.release(admobSession)
            FullScreenShowGate.release(topOnSession)
            host.pause().stop().destroy()
        }
    }

    @Test
    fun `provider or host failure never acquires the shared lock`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val attempt = Any()
        AdLifecycleMonitor.install(host.get().application, host.get())
        try {
            assertEquals("sdk_initializing", FullScreenShowGate.tryAcquire(host.get(), "sdk_initializing", attempt))
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            host.pause().stop()
            assertNotNull(FullScreenShowGate.tryAcquire(host.get(), null, attempt))
            assertFalse(FullScreenShowGate.isAnyAdShowing)
        } finally {
            FullScreenShowGate.release(attempt)
            host.destroy()
        }
    }
}
