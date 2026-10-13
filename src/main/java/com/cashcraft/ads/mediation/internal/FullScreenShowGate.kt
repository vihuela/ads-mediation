package com.cashcraft.ads.mediation.internal

import android.app.Activity
import java.util.concurrent.atomic.AtomicReference

/** One process-wide owner across providers and formats; only that show may release it. */
internal object FullScreenShowGate {
    private val owner = AtomicReference<Any?>(null)

    val isAnyAdShowing: Boolean
        get() = owner.get() != null

    fun tryAcquire(activity: Activity, providerFailureReason: String?, attempt: Any): String? =
        providerFailureReason
            ?: AdLifecycleMonitor.activityShowFailureReason(activity)
            ?: if (owner.compareAndSet(null, attempt)) null else "another_full_screen_ad_showing"

    fun release(attempt: Any) {
        owner.compareAndSet(attempt, null)
    }
}
