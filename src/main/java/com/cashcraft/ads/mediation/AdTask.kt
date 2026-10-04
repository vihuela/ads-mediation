package com.cashcraft.ads.mediation

import java.util.UUID

/** One scene request. Cancellation stops waiting, never the shared SDK loads or cache. */
open class AdTask internal constructor(private var cancelAction: (() -> Unit)?) {
    /** Correlates this request's complete execution flow in Logcat. */
    val id: String = UUID.randomUUID().toString()

    /** SDK-owned ads cannot be closed after handoff. Our full-screen Native can be closed. */
    fun cancel() { synchronized(this) { cancelAction }?.invoke() }

    internal fun detach() = synchronized(this) { cancelAction = null }
}
