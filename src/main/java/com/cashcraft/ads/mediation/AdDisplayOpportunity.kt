package com.cashcraft.ads.mediation

/** Cancels only this display opportunity. SDK loading and cached ads remain available. */
class AdDisplayOpportunity internal constructor(private var cancelAction: (() -> Unit)?) {
    /** Effective on the main thread. Has no effect after handoff to SDK show(). */
    fun cancel() {
        val action = synchronized(this) { cancelAction }
        action?.invoke()
    }

    internal fun detach() = synchronized(this) { cancelAction = null }
}
