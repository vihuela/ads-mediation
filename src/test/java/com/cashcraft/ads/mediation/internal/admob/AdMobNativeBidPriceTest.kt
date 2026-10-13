package com.cashcraft.ads.mediation.admob

import com.cashcraft.ads.mediation.AdBidCandidate
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.internal.BidCandidateSelector
import com.cashcraft.ads.mediation.internal.BidDecision
import com.cashcraft.ads.mediation.internal.toEventData
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdMobNativeBidPriceTest {
    private val configs = AdMobNextGenBidPrice.parse(
        File("src/main/assets/google_next_gen_preload_reflection_paths.json").readText(),
    )

    private fun price(ad: Any, version: String = "1.2.1") =
        AdMobNextGenBidPrice.readNativePrice(ad, version, configs)

    @Test fun `reads inherited native path from shipped config and converts micros`() {
        assertEquals(listOf(listOf("a", "b", "m")), configs.getValue("1.2.1").nativeAdPaths)
        assertEquals(0.001234, price(ad(1_234L))!!, 0.0)
    }

    @Test fun `genuine zero is a known price`() {
        assertEquals(0.0, price(ad(0L))!!, 0.0)
    }

    @Test fun `unknown SDK versions fail closed without guessing a path`() {
        assertNull(price(ad(1_234L), "1.2.2"))
    }

    @Test fun `missing or null path segments and invalid leaves remain unknown`() {
        assertNull(price(Any()))
        assertNull(price(Native(null)))
        assertNull(price(Native(Internal(null))))
        assertNull(price(Native(Internal(Configuration(null)))))
        assertNull(price(Native(Internal(Configuration(Any())))))
    }

    @Test fun `negative values and non long micros are rejected`() {
        listOf(-1L, 1.5, Double.NaN, Double.POSITIVE_INFINITY, "1234", 1234).forEach {
            assertNull(price(ad(it)))
        }
    }

    @Test fun `only USD amounts enter the USD auction`() {
        listOf(null, "", "EUR", "CNY", 1).forEach { assertNull(price(ad(100L, it))) }
        assertEquals(0.0001, price(ad(100L, " usd "))!!, 0.0)
    }

    @Test fun `missing currency config cannot turn a value into an assumed USD price`() {
        val config = configs.getValue("1.2.1").copy(priceCurrencyCodeField = null)
        assertNull(AdMobNextGenBidPrice.readNativePrice(ad(1L), "1.2.1", mapOf("1.2.1" to config)))
    }

    @Test fun `a broken candidate path does not prevent a subsequent valid path`() {
        val config = configs.getValue("1.2.1").copy(
            nativeAdPaths = listOf(emptyList(), listOf("missing"), listOf("a", "b", "m")),
        )
        assertEquals(0.002, AdMobNextGenBidPrice.readNativePrice(
            ad(2_000L), "1.2.1", mapOf("1.2.1" to config),
        )!!, 0.0)
    }

    @Test fun `reflected native price actually changes winner and failed reflection stays null in bid event`() {
        val formats = listOf(AdFormat.NATIVE)
        val google = AdBidCandidate(AdPlatform.ADMOB, AdFormat.NATIVE, "google", true, price(ad(2_000L)))
        val topon = AdBidCandidate(AdPlatform.TOPON, AdFormat.NATIVE, "topon", true, 0.001)
        assertEquals(google, BidCandidateSelector.select(listOf(google, topon), formats))
        val unknown = google.copy(priceUsd = price(Any()))
        val candidates = listOf(unknown, topon)
        val winner = BidCandidateSelector.select(candidates, formats)
        assertEquals(topon, winner)
        assertNull(BidDecision(formats, candidates, winner).toEventData().admobValue)
        assertEquals(unknown, BidCandidateSelector.select(listOf(unknown), formats))
    }

    @Test fun `inherited fields match the actual resolved Next Gen SDK`() {
        val wrapper = Class.forName("ads_mobile_sdk.hr1")
        assertEquals("ads_mobile_sdk.jm", wrapper.superclass.name)
        assertEquals("ads_mobile_sdk.h91", wrapper.superclass.getDeclaredField("a").type.name)
        val internal = Class.forName("com.google.android.libraries.ads.mobile.sdk.internal.nativead.InternalNativeAd")
        assertEquals("ads_mobile_sdk.k91", internal.superclass.name)
        assertEquals("ads_mobile_sdk.a2", internal.superclass.getDeclaredField("b").type.name)
        assertEquals("ads_mobile_sdk.i9", Class.forName("ads_mobile_sdk.a2").getDeclaredField("m").type.name)
        assertEquals(Long::class.javaPrimitiveType, Class.forName("ads_mobile_sdk.i9").getDeclaredField("b").type)
        assertEquals(String::class.java, Class.forName("ads_mobile_sdk.i9").getDeclaredField("d").type)
    }

    private fun ad(micros: Any?, currency: Any? = "USD") = Native(Internal(Configuration(Price(micros, currency))))
    private open class WrapperBase(private val a: Any?)
    private class Native(internal: Any?) : WrapperBase(internal)
    private open class InternalBase(private val b: Any?)
    private class Internal(config: Any?) : InternalBase(config)
    private class Configuration(private val m: Any?)
    private class Price(private val b: Any?, private val d: Any?)
}
