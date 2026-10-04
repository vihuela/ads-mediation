package com.cashcraft.ads.mediation.internal

import android.app.Activity
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
    @org.junit.Before
    @org.junit.After
    fun resetInstallation() {
        val type = AdLifecycleMonitor::class.java
        val application = org.robolectric.util.ReflectionHelpers.getStaticField<android.app.Application?>(type, "installedApplication")
        val callbacks = org.robolectric.util.ReflectionHelpers.getStaticField<android.app.Application.ActivityLifecycleCallbacks>(type, "callbacks")
        application?.unregisterActivityLifecycleCallbacks(callbacks)
        org.robolectric.util.ReflectionHelpers.setStaticField(type, "installedApplication", null)
    }

    @Test
    fun `onCreate request binds the new Activity even while another Activity remains resumed`() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        AdLifecycleMonitor.install(application)
        val old = Robolectric.buildActivity(Activity::class.java).setup()
        val new = Robolectric.buildActivity(Activity::class.java).create()
        try {
            assertSame(old.get(), AdLifecycleMonitor.currentActivity)
            assertSame(new.get(), AdLifecycleMonitor.requestActivity)
            new.start().resume()
            assertSame(new.get(), AdLifecycleMonitor.requestActivity)
        } finally { new.pause().stop().destroy(); old.pause().stop().destroy() }
    }

    @Test
    fun `ambiguous new Activity hosts fail instead of choosing an old foreground Activity`() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        AdLifecycleMonitor.install(application)
        val old = Robolectric.buildActivity(Activity::class.java).setup()
        val a = Robolectric.buildActivity(Activity::class.java).create()
        val b = Robolectric.buildActivity(Activity::class.java).create()
        try { org.junit.Assert.assertNull(AdLifecycleMonitor.requestActivity) }
        finally { a.destroy(); b.destroy(); old.pause().stop().destroy() }
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
        current.pause().resume()
        assertTrue(AdLifecycleMonitor.isAppInForeground)

        current.pause().stop().destroy()
        assertFalse(AdLifecycleMonitor.isAppInForeground)
    }
}
