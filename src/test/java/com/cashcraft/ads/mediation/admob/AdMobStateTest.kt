package com.cashcraft.ads.mediation.admob

import org.junit.Assert.assertEquals
import org.junit.Test

class AdMobStateTest {
    @Test
    fun `show failure reason identifies initialization state`() {
        assertEquals("sdk_not_initialized", AdMobState.NOT_INITIALIZED.showFailureReason())
        assertEquals("sdk_initializing", AdMobState.INITIALIZING.showFailureReason())
        assertEquals("sdk_initialization_failed", AdMobState.FAILED.showFailureReason())
        assertEquals("sdk_not_ready", AdMobState.READY.showFailureReason())
    }
}
