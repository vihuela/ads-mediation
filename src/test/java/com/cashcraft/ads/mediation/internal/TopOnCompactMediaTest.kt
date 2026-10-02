package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.internal.nativeads.topOnNativeBidPrice
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class TopOnCompactMediaTest {
    @Test fun `TopOn bid converts USD eCPM and preserves zero while invalid prices remain unknown`() {
        assertEquals(0.005, topOnNativeBidPrice(5.0)!!, 0.0)
        assertEquals(0.0, topOnNativeBidPrice(0.0)!!, 0.0)
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { assertNull(topOnNativeBidPrice(it)) }
    }
}
