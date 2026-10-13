package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.app.Application
import org.junit.Before
import org.junit.After
import org.robolectric.util.ReflectionHelpers
import java.lang.ref.WeakReference
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdLifecycleMonitorTest {
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
    fun `late installation and repeated resume preserve foreground accounting`() {
        val previous = Robolectric.buildActivity(Activity::class.java).setup().pause()
        val application = previous.get().application
        AdLifecycleMonitor.install(application)
        val current = Robolectric.buildActivity(Activity::class.java).setup()

        previous.stop().destroy()
        assertTrue(AdLifecycleMonitor.isAppInForeground)
        assertSame(current.get(), AdLifecycleMonitor.currentActivity)

        AdLifecycleMonitor.install(application, current.get())
        current.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertFalse(AdLifecycleMonitor.isAppInForeground)
        current.resume()
        assertTrue(AdLifecycleMonitor.isAppInForeground)

        current.pause().stop().destroy()
        assertFalse(AdLifecycleMonitor.isAppInForeground)
    }
}
