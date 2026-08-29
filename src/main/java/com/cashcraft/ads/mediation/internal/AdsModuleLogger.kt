package com.cashcraft.ads.mediation.internal

import android.util.Log
import com.cashcraft.ads.mediation.AdConsentSnapshot
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import java.math.BigDecimal
import java.math.RoundingMode

/** Single Logcat boundary for this module; production variants are silent unless explicitly enabled. */
internal class AdsModuleLogger(
    private val enabled: Boolean,
    private val tag: String,
) {
    fun event(event: AdEvent) {
        if (!enabled) return
        val message = formatAdEventLogMessage(event)
        if (event.name == AdEventName.SHOW_FAIL) {
            Log.w(tag, message)
        } else {
            Log.d(tag, message)
        }
    }

    fun consent(
        stage: String,
        snapshot: AdConsentSnapshot,
        errorMessage: String? = null,
    ) {
        if (!enabled) return
        val message = buildString {
            append("ump_stage=").append(stage)
            append(" consent_status=").append(snapshot.status.analyticsValue)
            append(" can_request_ads=").append(snapshot.canRequestAds)
            append(" privacy_options_required=").append(snapshot.privacyOptionsRequired)
            errorMessage?.let { append(" error=").append(it.oneLine()) }
        }
        if (errorMessage == null) Log.d(tag, message) else Log.w(tag, message)
    }

    fun eventDispatchFailed(event: AdEvent, error: Throwable) {
        if (!enabled) return
        Log.e(
            tag,
            "ad_event_dispatch_failed event=${event.name.analyticsName} " +
                "session_id=${event.sessionId} reason=${error.message.orEmpty().oneLine()}",
            error,
        )
    }

    fun showFailureException(
        format: AdFormat,
        position: String,
        sessionId: String,
        reason: String,
        errorCode: String?,
        error: Throwable,
    ) {
        if (!enabled) return
        Log.e(
            tag,
            buildString {
                append("ad_show_exception")
                append(" ad_type=").append(format.analyticsValue)
                append(" position=").append(position.oneLine())
                append(" session_id=").append(sessionId)
                append(" reason=").append(reason.oneLine())
                errorCode?.let { append(" error_code=").append(it.oneLine()) }
            },
            error,
        )
    }
}

internal fun formatAdEventLogMessage(event: AdEvent): String = buildString {
    append("ad_event=").append(event.name.analyticsName)
    append(" ad_platform=").append(event.platform.analyticsValue)
    append(" mediation_mode=").append(event.mediationMode.analyticsValue)
    append(" ad_type=").append(event.format.analyticsValue)
    append(" position=").append(event.position.oneLine())
    append(" session_id=").append(event.sessionId)
    append(" number=").append(event.number)
    append(" ad_unit_id=").append(event.adUnitId)
    event.adSource?.let { append(" ad_source=").append(it.oneLine()) }
    event.responseId?.let { append(" response_id=").append(it.oneLine()) }
    event.errorCode?.let { append(" error_code=").append(it.oneLine()) }
    event.reason?.let { append(" reason=").append(it.oneLine()) }
    event.value?.let { append(" value=").append(it.toPlainLogString()) }
    event.currency?.let { append(" currency=").append(it.oneLine()) }
    event.requestId?.let { append(" request_id=").append(it.oneLine()) }
    event.result?.let { append(" result=").append(it.oneLine()) }
    event.latencyMillis?.let { append(" latency_ms=").append(it) }
    event.bufferSize?.let { append(" buffer_size=").append(it) }
    if (event.name == AdEventName.BID_RESULT) {
        append(" winner_platform=")
            .append(event.winnerPlatform?.analyticsValue ?: "none")
    }
    event.admobAvailable?.let { append(" admob_available=").append(it) }
    event.topOnAvailable?.let { append(" topon_available=").append(it) }
    event.admobPriceAvailable?.let { append(" admob_price_available=").append(it) }
    event.topOnPriceAvailable?.let { append(" topon_price_available=").append(it) }
    event.admobValue?.let { append(" admob_value=").append(it.toPlainLogString()) }
    event.topOnValue?.let { append(" topon_value=").append(it.toPlainLogString()) }
    event.winningValue?.let { append(" winning_value=").append(it.toPlainLogString()) }
}

private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ')

private fun Double.toPlainLogString(): String = if (isFinite()) {
    BigDecimal.valueOf(this)
        .setScale(MAX_PRICE_LOG_DECIMALS, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
} else {
    toString()
}

private const val MAX_PRICE_LOG_DECIMALS = 8
