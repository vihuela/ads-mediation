package com.cashcraft.ads.mediation.internal

import android.app.Activity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Shared full-screen show admission while keeping one independent gate per provider. */
internal class FullScreenShowGate {
    private val showing = AtomicBoolean(false)

    init {
        gates += showing
    }

    val isShowing: Boolean
        get() = showing.get()

    fun tryAcquire(activity: Activity, providerFailureReason: String?): String? =
        providerFailureReason
            ?: AdLifecycleMonitor.activityShowFailureReason(activity)
            ?: if (!showing.compareAndSet(false, true)) {
                "another_full_screen_ad_showing"
            } else {
                null
            }

    fun release() {
        showing.set(false)
    }

    internal companion object {
        private val gates = CopyOnWriteArrayList<AtomicBoolean>()

        val isAnyAdShowing: Boolean
            get() = gates.any(AtomicBoolean::get)
    }
}
