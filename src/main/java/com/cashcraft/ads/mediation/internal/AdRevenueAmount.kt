package com.cashcraft.ads.mediation.internal

import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.thinkup.core.api.TUAdInfo
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Amount and micros always describe the same original currency; this is not an FX conversion. */
internal data class AdRevenueAmount(val value: Double, val valueMicros: Long, val currencyCode: String)

internal fun adMobRevenueAmount(value: AdValue): AdRevenueAmount? {
    val micros = value.valueMicros.takeIf { it >= 0L } ?: return null
    val currency = value.currencyCode.normalizedCurrency() ?: return null
    return AdRevenueAmount(micros / 1_000_000.0, micros, currency)
}

internal fun topOnRevenueAmount(info: TUAdInfo): AdRevenueAmount? {
    // The no-argument getter and currency belong together. USD conversion is only for bidding.
    val value = info.publisherRevenue?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val currency = info.currency.normalizedCurrency() ?: return null
    val micros = runCatching {
        BigDecimal.valueOf(value).movePointRight(6)
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull() ?: return null
    return AdRevenueAmount(value, micros, currency)
}

private fun String?.normalizedCurrency(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
