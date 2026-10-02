package com.cashcraft.ads.mediation

enum class AdFormat(val analyticsValue: String) {
    APP_OPEN("app_open"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    BANNER("banner"),
    NATIVE("native"),
}

enum class AdEventName(val analyticsName: String) {
    LOAD_REQUEST("ad_load_request"),
    LOAD_RESULT("ad_load_result"),
    POSITION("ad_position"),
    BID_RESULT("ad_bid_result"),
    IMPRESSION("ad_impression"),
    SHOW_FAIL("ad_show_fail"),
    CLICK("ad_click"),
    DISMISS("ad_close"),
    PAID("ad_paid"),
    REWARD_EARNED("ad_reward_earned"),
    BANNER_REFRESH("ad_banner_refresh"),
}

/** SDK-neutral event payload shared by AdMob and TopOn. */
data class AdEvent(
    val name: AdEventName,
    val platform: AdPlatform,
    val format: AdFormat,
    val position: String,
    val sessionId: String,
    val adUnitId: String,
    val number: Long,
    val reason: String? = null,
    val errorCode: String? = null,
    val adSource: String? = null,
    val responseId: String? = null,
    val value: Double? = null,
    val valueMicros: Long? = null,
    val currency: String? = null,
    val mediationAdapterClassName: String? = null,
    val precisionType: String? = null,
    val requestId: String? = null,
    val result: String? = null,
    val latencyMillis: Long? = null,
    val bufferSize: Int? = null,
    val winnerPlatform: AdPlatform? = null,
    val admobAvailable: Boolean? = null,
    val topOnAvailable: Boolean? = null,
    val admobPriceAvailable: Boolean? = null,
    val topOnPriceAvailable: Boolean? = null,
    val admobValue: Double? = null,
    val topOnValue: Double? = null,
    val winningValue: Double? = null,
    val admobAdUnitId: String? = null,
    val topOnAdUnitId: String? = null,
    val mediationMode: AdMediationMode = when (platform) {
        AdPlatform.ADMOB -> AdMediationMode.ADMOB
        AdPlatform.TOPON -> AdMediationMode.TOPON
    },
    val slotId: String? = null,
) {
    /** Field names intentionally retain the existing analytics contract. */
    fun analyticsParameters(): Map<String, Any> = buildMap {
        put("ad_type", format.analyticsValue)
        put("ad_platform", platform.analyticsValue)
        put("mediation_mode", mediationMode.analyticsValue)
        put("position", position)
        put("session_id", sessionId)
        put("ad_unit_id", adUnitId)
        put("number", number)
        slotId?.let { put("slot_id", it) }
        reason?.let { put("reason", it.take(MAX_REASON_LENGTH)) }
        errorCode?.let { put("error_code", it) }
        adSource?.let { put("ad_source", it) }
        responseId?.let { put("response_id", it) }
        value?.let { put("value", it) }
        valueMicros?.let { put("value_micros", it) }
        currency?.let { put("currency", it) }
        mediationAdapterClassName?.let { put("mediation_adapter_class_name", it) }
        precisionType?.let { put("precision_type", it) }
        requestId?.let { put("request_id", it) }
        slotId?.let { put("slot_id", it) }
        result?.let { put("result", it) }
        latencyMillis?.let { put("latency_ms", it) }
        bufferSize?.let { put("buffer_size", it) }
        if (name == AdEventName.BID_RESULT) {
            put("winner_platform", winnerPlatform?.analyticsValue ?: "none")
        }
        admobAvailable?.let { put("admob_available", it) }
        topOnAvailable?.let { put("topon_available", it) }
        admobPriceAvailable?.let { put("admob_price_available", it) }
        topOnPriceAvailable?.let { put("topon_price_available", it) }
        admobValue?.let { put("admob_value", it) }
        topOnValue?.let { put("topon_value", it) }
        winningValue?.let { put("winning_value", it) }
        admobAdUnitId?.let { put("admob_ad_unit_id", it) }
        topOnAdUnitId?.let { put("topon_ad_unit_id", it) }
    }

    private companion object {
        const val MAX_REASON_LENGTH = 64
    }
}

fun interface AdEventListener {
    fun onEvent(event: AdEvent)

    companion object {
        val NONE = AdEventListener { }
    }
}
