package com.cashcraft.ads.mediation.internal

import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.TUAdConst
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [SdkUsdRateShadow::class])
class AdRevenueAmountTest {
    @Test fun `AdMob paid amounts retain each original currency`() {
        for (currency in listOf("USD", "CNY", "EUR", "JPY")) {
            val amount = adMobRevenueAmount(AdValue(PrecisionType.values().first(), 1234L, " ${currency.lowercase()} "))!!
            assertEquals(currency, amount.currencyCode)
            assertEquals(1234L, amount.valueMicros)
            assertEquals(0.001234, amount.value, 0.0)
        }
    }

    @Test fun `AdMob paid rejects invalid amounts and missing currency but keeps zero`() {
        assertNull(adMobRevenueAmount(AdValue(PrecisionType.values().first(), -1L, "USD")))
        assertNull(adMobRevenueAmount(AdValue(PrecisionType.values().first(), 10L, " ")))
        assertEquals(0L, adMobRevenueAmount(AdValue(PrecisionType.values().first(), 0L, "EUR"))!!.valueMicros)
    }

    @Test fun `TopOn paid uses the USD API even when original revenue is CNY`() {
        for (currency in listOf("USD", "CNY")) {
            val info = info(1.25, currency)
            val apiUsd = info.getPublisherRevenue(TUAdConst.CURRENCY.USD)!!
            val amount = topOnRevenueAmount(info)!!
            assertEquals("USD", amount.currencyCode)
            assertEquals(apiUsd, amount.value, 0.0)
            if (currency == "CNY") {
                assertTrue(amount.value > 0.0 && amount.value < info.publisherRevenue!!)
                assertNotEquals(info.publisherRevenue, amount.value)
            }
        }
    }

    @Test fun `TopOn USD micro rounding preserves the API decimal and genuine zero`() {
        val amount = topOnRevenueAmount(info(0.0005737, "USD"))!!
        assertEquals(574L, amount.valueMicros)
        assertEquals(0.0005737, amount.value, 0.0)
        assertEquals("USD", amount.currencyCode)
        assertEquals(0L, topOnRevenueAmount(info(0.0, "CNY"))!!.valueMicros)
    }

    @Test fun `TopOn paid rejects invalid USD amounts overflow and SDK exceptions`() {
        for (value in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.MAX_VALUE)) {
            assertNull(topOnRevenueAmount(info(value, "USD")))
        }
        // The real SDK's CNY conversion dereferences null revenue; it must not escape our boundary.
        assertThrows(NullPointerException::class.java) {
            info(null, "CNY").getPublisherRevenue(TUAdConst.CURRENCY.USD)
        }
        assertNull(topOnRevenueAmount(info(null, "CNY")))
    }

    @Test fun `TopOn USD API does not require the original currency getter`() {
        for (currency in listOf(null, "", " ")) {
            val amount = topOnRevenueAmount(info(1.0, currency))!!
            assertEquals("USD", amount.currencyCode)
            assertEquals(1.0, amount.value, 0.0)
        }
    }

    // Use the actual resolved 6.6.22.3 TUAdInfo implementation, with synthetic callback data.
    private fun info(revenue: Double?, currency: String?): TUAdInfo {
        val type = Class.forName("com.thinkup.core.common.o.dq")
        return (type.getDeclaredConstructor().newInstance() as TUAdInfo).also { info ->
            type.getDeclaredField("md").apply { isAccessible = true }.set(info, revenue)
            type.getDeclaredField("mn").apply { isAccessible = true }.set(info, currency)
        }
    }
}

// Only the SDK's configuration boundary is stubbed; TUAdInfo's USD overload executes real SDK code.
@org.robolectric.annotation.Implements(com.thinkup.core.api.TUSDKUtils::class)
class SdkUsdRateShadow {
    companion object {
        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun getRmbChangeToUsdRate(): Double = 0.125
    }
}
