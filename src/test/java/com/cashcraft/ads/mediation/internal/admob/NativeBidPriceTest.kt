package com.cashcraft.ads.mediation.admob

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeBidPriceTest {
    private val config = AdMobNextGenBidPrice.parse(File("src/main/assets/google_next_gen_preload_reflection_paths.json").readText()).getValue("1.2.1")

    @Test fun `loaded Native path returns USD per impression including genuine zero`() {
        assertEquals(listOf("a", "b", "m"), config.nativePricePath)
        assertEquals(0.00125, AdMobNextGenBidPrice.readNativePrice(Ad(1250, "USD"), config)!!, 0.0)
        assertEquals(0.0, AdMobNextGenBidPrice.readNativePrice(Ad(0, "usd"), config)!!, 0.0)
    }

    @Test fun `missing fields unsupported currency and invalid amount remain unknown`() {
        assertNull(AdMobNextGenBidPrice.readNativePrice(Any(), config))
        assertNull(AdMobNextGenBidPrice.readNativePrice(Ad(-1, "USD"), config))
        assertNull(AdMobNextGenBidPrice.readNativePrice(Ad(1000, "EUR"), config))
        assertNull(AdMobNextGenBidPrice.readNativePrice(Ad(1000, ""), config))
        assertNull(AdMobNextGenBidPrice.readNativePrice(Ad(1000, "USD"), config.copy(nativePricePath = emptyList())))
    }

    private class Ad(micros: Long, currency: String) { val a = Inner(micros, currency) }
    private class Inner(micros: Long, currency: String) { val b = Config(micros, currency) }
    private class Config(micros: Long, currency: String) { val m = Price(micros, currency) }
    private class Price(val b: Long, val d: String)
}
