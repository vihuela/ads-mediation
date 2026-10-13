package com.cashcraft.ads.mediation.internal

import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import com.thinkup.core.api.TUAdInfo
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun `TopOn paid uses original revenue currency pair without requesting USD conversion`() {
        for (currency in listOf("USD", "CNY", "EUR", "JPY")) {
            val amount = topOnRevenueAmount(info(1.25, " ${currency.lowercase()} "))!!
            assertEquals(currency, amount.currencyCode)
            assertEquals(1.25, amount.value, 0.0)
            assertEquals(1_250_000L, amount.valueMicros)
        }
    }

    @Test fun `TopOn micro rounding is explicit and original decimal remains available`() {
        val amount = topOnRevenueAmount(info(0.0005737, "CNY"))!!
        assertEquals(574L, amount.valueMicros)
        assertEquals(0.0005737, amount.value, 0.0)
        assertEquals(0L, topOnRevenueAmount(info(0.0, "CNY"))!!.valueMicros)
    }

    @Test fun `TopOn paid rejects invalid amounts overflow and unknown currency`() {
        for (value in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertNull(topOnRevenueAmount(info(value, "USD")))
        }
        for (currency in listOf(null, "", " ")) assertNull(topOnRevenueAmount(info(1.0, currency)))
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
