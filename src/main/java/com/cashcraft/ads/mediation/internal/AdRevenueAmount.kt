package com.cashcraft.ads.mediation.internal

import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.TUAdConst
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Amount and micros describe the same currency; this layer never computes exchange rates. */
internal data class AdRevenueAmount(val value: Double, val valueMicros: Long, val currencyCode: String)

internal fun adMobRevenueAmount(value: AdValue): AdRevenueAmount? {
    val micros = value.valueMicros.takeIf { it >= 0L } ?: return null
    val currency = value.currencyCode.normalizedCurrency() ?: return null
    return AdRevenueAmount(micros / 1_000_000.0, micros, currency)
}

internal fun topOnRevenueAmount(info: TUAdInfo): AdRevenueAmount? {
    // TopOn owns currency conversion. Never fall back to raw revenue or eCPM for paid events.
    val value = runCatching { info.getPublisherRevenue(TUAdConst.CURRENCY.USD) }.getOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val micros = runCatching {
        BigDecimal.valueOf(value).movePointRight(6)
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull() ?: return null
    return AdRevenueAmount(value, micros, "USD")
}

private fun String?.normalizedCurrency(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
