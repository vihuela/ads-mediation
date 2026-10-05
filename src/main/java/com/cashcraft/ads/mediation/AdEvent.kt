package com.cashcraft.ads.mediation

enum class AdFormat(val analyticsValue: String) {
    APP_OPEN("app_open"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    BANNER("banner"),
    NATIVE("native"),
}

enum class AdEventName(val analyticsName: String) {
    LOAD("ad_load"),
    LOADED("ad_loaded"),
    LOAD_FAIL("ad_load_fail"),
    POSITION("ad_position"),
    SCENE_SKIP("ad_scene_skip"),
    BID_RESULT("ad_bid_result"),
    IMPRESSION("ad_impression"),
    SHOW_FAIL("ad_show_fail"),
    CLICK("ad_click"),
    DISMISS("ad_close"),
    REWARD("ad_reward"),
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
    val platformKnown: Boolean = true,
    /** 同一 Banner 页面周期内的广告序号，首条为 1。 */
    val refreshIndex: Long? = null,
) {
    internal val isLoadEvent: Boolean
        get() = name in setOf(AdEventName.LOAD, AdEventName.LOADED, AdEventName.LOAD_FAIL)

    /** v2 business properties. Diagnostics or invalid events return an empty map. */
    fun analyticsParameters(): Map<String, Any> {
        if (name in setOf(AdEventName.BID_RESULT, AdEventName.BANNER_REFRESH) ||
            (format == AdFormat.BANNER && name == AdEventName.DISMISS)) return emptyMap()
        val properties = linkedMapOf<String, Any>("ad_type" to format.analyticsValue)
        if (name == AdEventName.SCENE_SKIP) {
            properties["position_id"] = position.takeIf { it.isNotBlank() } ?: return emptyMap()
            properties["reason"] = skipReasons[reason] ?: return emptyMap()
            return properties
        }
        if (!platformKnown && name !in setOf(AdEventName.POSITION, AdEventName.SHOW_FAIL)) return emptyMap()
        properties["ad_platform"] = if (platformKnown) platform.analyticsValue else "unknown"
        properties["mediation_mode"] = mediationMode.analyticsValue
        if (isLoadEvent) {
            properties["request_id"] = requestId?.takeIf { it.isNotBlank() } ?: return emptyMap()
            if (name != AdEventName.LOAD) {
                properties["latency_ms"] = latencyMillis?.takeIf { it >= 0 } ?: return emptyMap()
            }
        } else {
            properties["position_id"] = position.takeIf { it.isNotBlank() } ?: return emptyMap()
            val businessSessionId = if (format == AdFormat.BANNER) slotId ?: sessionId else sessionId
            properties["ad_session_id"] = businessSessionId.takeIf { it.isNotBlank() } ?: return emptyMap()
        }
        if (platformKnown) adUnitId.takeIf { it.isNotBlank() }?.let { properties["ad_unit_id"] = it }
        when (name) {
            AdEventName.LOADED, AdEventName.CLICK ->
                adSource?.takeIf { it.isNotBlank() }?.let { properties["ad_source"] = it }
            AdEventName.LOAD_FAIL, AdEventName.SHOW_FAIL -> {
                val failure = failureReason()
                properties["reason"] = failure
                if (failure != "exception") errorCode?.takeIf { it.isNotBlank() }?.let { properties["error_code"] = it }
            }
            AdEventName.IMPRESSION -> {
                if (format == AdFormat.BANNER) {
                    refreshIndex?.takeIf { it > 0 }?.let { properties["refresh_index"] = it }
                }
                val micros = valueMicros?.takeIf { it >= 0 }
                val code = currency?.takeIf { it.matches(Regex("[A-Z]{3}")) }
                // TopOn display callbacks can confirm an impression without revenue data.
                if (micros != null && code != null) {
                    properties["revenue_amount"] = micros / 1_000_000.0
                    properties["currency"] = code
                    precisionType?.takeIf { it.isNotBlank() }?.let { properties["precision_type"] = it }
                }
                adSource?.takeIf { it.isNotBlank() }?.let { properties["ad_source"] = it }
            }
            AdEventName.REWARD -> if (format != AdFormat.REWARDED) return emptyMap()
            else -> Unit
        }
        return properties
    }

    private fun failureReason(): String {
        if (errorCode == "load_exception") return "exception"
        return when (reason) {
            "no_fill", "no_preloaded_ad", "no_candidate" -> "no_fill"
            "timeout", "wait_timeout", "load_timeout", "native_load_timeout", "native_bid_timeout" -> "timeout"
            "cancelled", "opportunity_cancelled", "native_cancelled" -> "cancelled"
            "ad_busy", "already_showing", "fullscreen_busy", "another_full_screen_ad_showing",
            "request_in_progress" -> "ad_busy"
            "ad_platform_disabled" -> "platform_disabled"
            "scene_inactive", "scene_invalid", "activity_not_available", "activity_not_resumed",
            "app_not_in_foreground", "native_inactive", "native_destroyed", "native_temporarily_unavailable" -> "scene_inactive"
            "exception", "load_exception", "banner_configuration_failed", "banner_callback_configuration_failed" -> "exception"
            else -> skipReasons[reason] ?: when (result) {
                "no_fill" -> "no_fill"
                "timeout" -> "timeout"
                "cancelled" -> "cancelled"
                else -> "ad_error"
            }
        }
    }

    private companion object {
        val skipReasons = mapOf(
            "global_disabled" to "global_disabled",
            "platform_disabled" to "platform_disabled",
            "position_disabled" to "position_disabled",
            "new_user_protection" to "new_user_protection",
            "fullscreen_gap" to "fullscreen_gap",
            "daily_show_limit" to "show_rate_limited",
            "daily_click_limit" to "click_rate_limited",
            "show_rate_limited" to "show_rate_limited",
            "click_rate_limited" to "click_rate_limited",
        )
    }
}

fun interface AdEventListener {
    fun onEvent(event: AdEvent)

    companion object {
        val NONE = AdEventListener { }
    }
}
