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
        if (event.format == AdFormat.NATIVE) {
            formatNativeEventLogMessage(event)?.let { message ->
                if (event.name == AdEventName.SHOW_FAIL) Log.w(tag, message) else Log.i(tag, message)
            }
            Log.d(tag, formatNativeDebugLogMessage(event))
            return
        }
        val message = formatAdEventLogMessage(event)
        if (event.name == AdEventName.SHOW_FAIL) {
            Log.w(tag, message)
        } else {
            Log.d(tag, message)
        }
    }

    /** 中文流程消息按需构造，关闭日志不会读取素材或格式化身份。 */
    fun native(position: String, debug: Boolean = false, warning: Boolean = false,
        error: Throwable? = null, message: () -> String) {
        if (!enabled) return
        val text = "[原生广告]${if (debug) "[调试]" else ""}[${position.nativeLogText()}] ${message().nativeLogText()}"
        when {
            // SDK 异常消息可能带素材 URL/响应内容；保留根因类型和栈，不展开原始消息。
            error != null -> Log.e(tag, "$text | ${formatNativeExceptionLogMessage(error)}")
            warning -> Log.w(tag, text)
            debug -> Log.d(tag, text)
            else -> Log.i(tag, text)
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
        if (event.format == AdFormat.NATIVE) {
            native(event.slotId ?: event.position.removeSuffix("_native"), error = error) {
                "业务事件回调异常，平台交付继续"
            }
            return
        }
        Log.e(
            tag,
            "ad_event_dispatch_failed event=${event.name.analyticsName} " +
                "session_id=${event.sessionId} reason=${error.message.orEmpty().oneLine()}",
            error,
        )
    }

    fun bannerDiagnostic(
        slotId: String,
        reason: String,
        responseId: String?,
        rawValue: String? = null,
        currency: String? = null,
    ) {
        if (!enabled) return
        Log.w(tag, "banner_diagnostic slot_id=$slotId reason=${reason.oneLine()} " +
            "response_id=${responseId?.oneLine()} value=${rawValue?.oneLine()} currency=${currency?.oneLine()}")
    }

    fun bannerRevenueDispatchFailed(event: AdEvent, error: Throwable) {
        if (!enabled) return
        Log.e(tag, "banner_revenue_dispatch_failed slot_id=${event.slotId} " +
            "session_id=${event.sessionId} reason=${error.message.orEmpty().oneLine()}", error)
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
    event.slotId?.let { append(" slot_id=").append(it) }
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

/** 普通日志只解释业务结果，内部身份留在 DEBUG；金额不改写原事件。 */
internal fun formatNativeEventLogMessage(event: AdEvent): String? {
    val platform = if (event.platform == com.cashcraft.ads.mediation.AdPlatform.ADMOB) "AdMob" else "TopOn"
    val message = when (event.name) {
        AdEventName.POSITION -> if (event.mediationMode == com.cashcraft.ads.mediation.AdMediationMode.BIDDING)
            "开始获取广告，正在检查两端缓存。" else "开始获取广告，正在检查缓存。"
        AdEventName.BID_RESULT -> {
            val winner = when (event.winnerPlatform) {
                com.cashcraft.ads.mediation.AdPlatform.ADMOB -> "AdMob 胜出"
                com.cashcraft.ads.mediation.AdPlatform.TOPON -> "TopOn 胜出"
                null -> "没有可用广告"
            }
            "比价：AdMob ${nativePrice(event.admobValue)}、TopOn ${nativePrice(event.topOnValue)} 美元/次展示，$winner。"
        }
        AdEventName.IMPRESSION -> "$platform 已确认广告曝光。"
        AdEventName.CLICK -> "$platform 已通知广告点击。"
        AdEventName.DISMISS -> "$platform 已确认卡片关闭。"
        AdEventName.PAID -> {
            // 优先使用原始微单位，避免大额 Double 丢失精度；仅改变日志显示。
            val amount = event.valueMicros?.let { BigDecimal.valueOf(it, 6).stripTrailingZeros().toPlainString() }
                ?: event.value?.takeIf { it.isFinite() }?.toPlainLogString() ?: "金额未知"
            "收到平台收益回调：$amount ${event.currency.orEmpty().nativeLogText()}。"
        }
        AdEventName.SHOW_FAIL -> "获取或展示结束 | ${nativeReason(event.reason)}"
        AdEventName.LOAD_REQUEST, AdEventName.LOAD_RESULT, AdEventName.REWARD_EARNED, AdEventName.BANNER_REFRESH -> return null
    }
    return "[原生广告][${(event.slotId ?: event.position.removeSuffix("_native")).nativeLogText()}] $message"
}

internal fun formatNativeDebugLogMessage(event: AdEvent): String = buildString {
    append("[原生广告][调试][")
        .append((event.slotId ?: event.position.removeSuffix("_native")).nativeLogText()).append("] ")
    append(when (event.name) {
        AdEventName.LOAD_REQUEST -> "开始本层获取"
        AdEventName.LOAD_RESULT -> when (event.result) {
            "filled" -> "本层获取成功，尚未确认曝光"
            "cancelled" -> "本层获取已取消"
            "no_fill" -> "本层获取结束，暂无广告填充"
            else -> "本层获取失败 | ${nativeReason(event.reason)}"
        }
        else -> "事件关联"
    })
    append(" | 平台=").append(if (event.platform == com.cashcraft.ads.mediation.AdPlatform.ADMOB) "AdMob" else "TopOn")
    append(" | 获取记录=").append((event.requestId ?: event.sessionId).nativeLogText())
    append(" | 展示记录=").append(event.sessionId.nativeLogText())
    event.responseId?.let { append(" | 平台响应标识=").append(it.nativeLogText()) }
    event.latencyMillis?.let { append(" | 获取等待=").append(it).append("ms") }
    event.reason?.let { append(" | 原因标识=").append(it.nativeLogText()) }
    event.result?.let { append(" | 结果标识=").append(it.nativeLogText()) }
    event.errorCode?.let { append(" | 错误码=").append(it.nativeLogText()) }
    event.value?.let { append(" | 原始收益金额=").append(it.toPlainLogString()) }
    event.valueMicros?.let { append(" | 原始收益微单位=").append(it) }
    event.currency?.let { append(" | 币种=").append(it.nativeLogText()) }
}

private fun nativePrice(value: Double?): String =
    value?.takeIf { it.isFinite() && it >= 0 }?.toPlainLogString() ?: "报价未知"

private fun nativeReason(reason: String?): String = when (reason) {
    "no_fill" -> "暂无广告填充"
    "native_bid_timeout" -> "等待广告超时"
    "native_inactive", "native_temporarily_unavailable" -> "页面暂时不可展示"
    "native_destroyed" -> "生命周期结束"
    "native_position_in_use" -> "业务位置已被其他容器占用"
    "native_ad_expired" -> "广告已失效"
    "native_layout_invalid" -> "布局绑定无效"
    "native_render_failed" -> "渲染失败"
    "native_load_failed", "native_load_exception", "native_load_setup_failed" -> "平台获取失败"
    "native_platform_not_configured" -> "平台尚未配置"
    "native_platform_initialization_failed" -> "平台初始化失败"
    "native_template_size_unknown" -> "模板比例尚未确认"
    "unsupported_native_render_mode" -> "广告不兼容当前布局"
    "consent_not_allowed", "consent_not_obtained" -> "当前许可不允许请求"
    else -> "本次未完成，具体原因见调试日志"
}

internal fun String.nativeLogText(): String = replace('\n', ' ').replace('\r', ' ').take(200)

/** 不读取异常消息；根因类型及一次根因栈用于排查，避免 SDK 响应或素材地址泄漏。 */
internal fun formatNativeExceptionLogMessage(error: Throwable): String = buildString {
    val causes = mutableSetOf<Throwable>()
    var current = error
    append("异常类型=").append(current.javaClass.simpleName.nativeLogText())
    causes.add(current)
    // ponytail: 最多追溯 8 层原因和 20 帧根因栈，更深异常需独立诊断。
    while (causes.size < 8) {
        val cause = current.cause ?: break
        if (!causes.add(cause)) break
        current = cause
        append(" | 根因类型=").append(current.javaClass.simpleName.nativeLogText())
    }
    if (current.cause != null) append(" | 原因链已截断")
    append(" | 根因调用栈：")
    current.stackTrace.take(20).forEach { append("\n  at ").append(it.toString().nativeLogText()) }
}
