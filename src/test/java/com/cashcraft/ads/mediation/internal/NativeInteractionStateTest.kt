package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.internal.nativeads.NativeInteraction
import com.cashcraft.ads.mediation.internal.nativeads.NativeInteractionState
import org.junit.Assert.*
import org.junit.Test

class NativeInteractionStateTest {
    @Test fun `plain click expires but a long external visit suppresses precisely its return`() {
        var now = 0L
        val state = NativeInteractionState { now }
        state.interact("no-navigation", NativeInteraction.CLICK)
        assertTrue(state.blocked())
        now = 5_000
        assertFalse(state.blocked())
        state.interact("external", NativeInteraction.CLICK)
        state.background()
        now += 3_600_000
        state.foreground()
        assertTrue(state.blocked())
        state.background()
        state.foreground()
        assertFalse(state.blocked())
    }

    @Test fun `overlay closing in background preserves return suppression without poisoning the next visit`() {
        var now = 0L
        val state = NativeInteractionState { now }
        state.interact("external", NativeInteraction.CLICK)
        state.interact("external", NativeInteraction.OPEN)
        state.background()
        state.interact("external", NativeInteraction.CLOSE)
        now = 3_600_000
        state.foreground()
        assertTrue(state.blocked())
        state.interact("external", NativeInteraction.CLOSE)
        state.background()
        state.foreground()
        assertFalse(state.blocked())
    }

    @Test fun `repeat click does not shorten an open overlay fallback or renew another ticket`() {
        var now = 0L
        val state = NativeInteractionState { now }
        state.interact("overlay", NativeInteraction.OPEN)
        now = 1_000
        state.interact("overlay", NativeInteraction.CLICK)
        now = 6_000
        assertTrue(state.blocked())
        now = 119_000
        state.interact("other", NativeInteraction.CLICK)
        now = 120_000
        state.interact("overlay", NativeInteraction.CLOSE)
        assertTrue(state.blocked())
        now = 124_000
        assertFalse(state.blocked())
    }

    @Test fun `expired overlay close cannot suppress a later foreground`() {
        var now = 0L
        val state = NativeInteractionState { now }
        state.interact("expired", NativeInteraction.OPEN)
        now = 120_000
        state.interact("expired", NativeInteraction.CLOSE)
        assertFalse(state.blocked())
    }

    @Test fun `native overlay suppression does not reserve or block the manual fullscreen gate`() {
        val state = NativeInteractionState { 0L }
        val attempt = FullScreenShowAttempt()
        state.interact("native", NativeInteraction.OPEN)
        try {
            assertTrue(state.blocked())
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            assertNull(FullScreenShowGate.reserve(attempt))
            assertNull(FullScreenShowGate.commit(attempt))
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            assertTrue(state.blocked())
        } finally {
            attempt.complete()
        }
        assertFalse(FullScreenShowGate.isAnyAdShowing)
        assertTrue(state.blocked())
    }

    @Test fun `old close cannot remove another overlay and missing close is bounded`() {
        var now = 0L
        val state = NativeInteractionState { now }
        state.interact("current", NativeInteraction.OPEN)
        state.interact("old", NativeInteraction.CLOSE)
        assertTrue(state.blocked())
        now = 120_000
        assertFalse(state.blocked())
    }

    @Test fun `mediated SDK landing pages are not automatic app open hosts`() {
        listOf(
            "com.bytedance.sdk.openadsdk.activity.single.IABLandingPageActivity",
            "com.google.android.libraries.ads.mobile.sdk.common.AdActivity",
            "com.thinkup.core.basead.ui.web.WebLandPageActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.smartdigimkt.sdk.basead.ui.web.WebLandPageActivity",
            "com.mbridge.msdk.activity.MBCommonActivity",
        ).forEach { assertTrue(it, isAdSdkActivityClassName(it)) }
        listOf("com.daily.health.manager.face.act.MainAct",
            "com.cashcraft.ads.mediation.smoke.NativeSmokeActivity",
            "com.example.sdk.LandingPageActivity",
        ).forEach { assertFalse(it, isAdSdkActivityClassName(it)) }
    }
}
