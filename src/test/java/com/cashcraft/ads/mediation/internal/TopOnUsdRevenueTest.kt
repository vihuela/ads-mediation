package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.thinkup.core.api.TUAdConst
import com.thinkup.core.api.TUAdInfo
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [SdkUsdRateShadow::class])
class TopOnUsdRevenueTest {
    @Test
    fun `fullscreen and native paid event and payload use identical SDK USD amounts`() {
        for (format in AdFormat.entries) {
            val info = info(1.25, "CNY")
            val expectedUsd = info.getPublisherRevenue(TUAdConst.CURRENCY.USD)!!
            val expectedMicros = BigDecimal.valueOf(expectedUsd).movePointRight(6)
                .setScale(0, RoundingMode.HALF_UP).longValueExact()
            deliver(format, info) { events, payloads ->
                val paid = events.single { it.name == AdEventName.PAID }
                val payload = payloads.single() as TopOnRevenuePayload
                assertEquals(expectedUsd, paid.value!!, 0.0)
                assertEquals(expectedMicros, paid.valueMicros)
                assertEquals("USD", paid.currency)
                assertEquals(expectedMicros, payload.valueMicros)
                assertEquals("USD", payload.currencyCode)
                assertEquals(format, payload.format)
                assertEquals(paid.sessionId, payload.sessionId)
                assertSame(info, payload.adInfo)
                assertEquals("CNY", info.currency) // The original vendor object is not modified.
                assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
                assertEquals(0, events.count { it.name == AdEventName.SHOW_FAIL })
            }
        }
    }

    @Test
    fun `invalid USD revenue is not reported as zero and does not remove an impression`() {
        for (format in AdFormat.entries) {
            for (value in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
                deliver(format, info(value, "CNY")) { events, payloads ->
                    assertTrue(payloads.isEmpty())
                    assertFalse(events.any { it.name == AdEventName.PAID })
                    assertEquals(1, events.count { it.name == AdEventName.POSITION })
                    assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
                    assertFalse(events.any { it.name == AdEventName.SHOW_FAIL })
                }
            }
        }
    }

    @Test
    fun `genuine zero USD revenue is delivered to both channels`() {
        deliver(AdFormat.NATIVE, info(0.0, "CNY")) { events, payloads ->
            assertEquals(0.0, events.single { it.name == AdEventName.PAID }.value!!, 0.0)
            assertEquals(0L, payloads.single().valueMicros)
            assertEquals("USD", payloads.single().currencyCode)
        }
    }

    private fun deliver(format: AdFormat, info: TUAdInfo,
        verify: (List<AdEvent>, List<AdRevenuePayload>) -> Unit) {
        val original = ReflectionHelpers.getStaticField<AdsConfig?>(TopOnAds::class.java, "commonConfig")
        val events = mutableListOf<AdEvent>()
        val payloads = mutableListOf<AdRevenuePayload>()
        val config = AdsConfig(
            provider = TopOnProviderConfig(TopOnIds("test-app", "test-key", interstitialPlacementId = "test-unit")),
            revenueListener = AdRevenueListener(payloads::add), loggingEnabled = false,
        )
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig", config)
        try {
            val session = AdShowSession(
                listener = AdEventListener(events::add), platform = AdPlatform.TOPON,
                mediationMode = AdMediationMode.TOPON, format = format, position = "test",
                adUnitId = "test-unit", sessionId = "test-${format.name}", number = 1L,
            )
            session.impression("test-network", "test-response")
            // Both full-screen SDK callbacks and nativePaid route into the same revenuePaid method.
            TopOnAds.nativePaid(session, info)
            verify(events, payloads)
        } finally {
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig", original)
        }
    }

    private fun info(value: Double?, currency: String): TUAdInfo {
        val type = Class.forName("com.thinkup.core.common.o.dq")
        return (type.getDeclaredConstructor().newInstance() as TUAdInfo).also {
            type.getDeclaredField("md").apply { isAccessible = true }.set(it, value)
            type.getDeclaredField("mn").apply { isAccessible = true }.set(it, currency)
        }
    }
}
