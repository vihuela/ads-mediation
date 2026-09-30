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
