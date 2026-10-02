package com.cashcraft.ads.mediation.internal.nativeads

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal object NativeMainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
    fun post(block: () -> Unit): NativeLoad {
        val action = Runnable(block)
        handler.post(action)
        return NativeLoad { handler.removeCallbacks(action) }
    }
}

/** Shared by all automatic app-open paths; no Activity, View or ad is retained here. */
internal object NativeInteractions {
    private val state = NativeInteractionState(SystemClock::elapsedRealtime)
    fun onInteraction(id: String, action: NativeInteraction) = state.interact(id, action)
    fun onBackground() = state.background()
    fun onForeground() = state.foreground()
    fun blocksAutoAppOpen(): Boolean = state.blocked()
}

internal class NativeInteractionState(private val clock: () -> Long) {
    private val tickets = mutableMapOf<String, Long>()
    private var awaitingReturn = false
    private var suppressForeground = false

    fun interact(id: String, action: NativeInteraction) {
        prune()
        when (action) {
            NativeInteraction.CLICK -> tickets[id] = maxOf(tickets[id] ?: 0, clock() + CLICK_WINDOW_MILLIS)
            NativeInteraction.OPEN -> tickets[id] = clock() + OVERLAY_FALLBACK_MILLIS
            NativeInteraction.CLOSE -> if (tickets.remove(id) != null) suppressForeground = true
        }
    }

    fun background() {
        prune()
        awaitingReturn = awaitingReturn || tickets.isNotEmpty()
        suppressForeground = false
    }

    fun foreground() {
        suppressForeground = awaitingReturn
        awaitingReturn = false
        if (suppressForeground) tickets.clear()
        prune()
    }

    fun blocked(): Boolean {
        prune()
        return suppressForeground || tickets.isNotEmpty()
    }

    private fun prune() { tickets.entries.removeAll { clock() >= it.value } }

    private companion object {
        const val CLICK_WINDOW_MILLIS = 5_000L
        // Missing dismissal callbacks must not suppress unrelated future foregrounds forever.
        const val OVERLAY_FALLBACK_MILLIS = 120_000L
    }
}
