package com.cashcraft.ads.mediation

import java.math.BigDecimal

/** First line is the human INFO/WARN summary; remaining lines are DEBUG details, like native ad logs. */
fun AdEvent.appOpenLogLines(): List<String> = buildList {
    val scene = if (isLoadEvent) "" else "[${position.logText()}]"
    val prefix = "[开屏广告][调试]$scene"
    val sid = sessionId.logText()
    val provider = if (platform == AdPlatform.ADMOB) "AdMob" else "TopOn"
    val source = adSource?.takeIf { it.isNotBlank() }?.logText() ?: "未提供"
    val message = when (name) {
        AdEventName.LOAD_REQUEST -> "$provider 开始加载开屏广告。"
        AdEventName.LOAD_RESULT -> when (result) {
            "filled" -> "$provider 加载成功，尚未确认曝光；实际来源=$source。"
            "no_fill" -> "$provider 加载结束，暂无广告填充。"
            "cancelled" -> "$provider 加载已取消。"
            else -> "$provider 加载未完成：${reason.flowReason()}。"
        } + (latencyMillis?.let { " 耗时=${it}ms。" } ?: "")
        AdEventName.POSITION -> "$provider 开始尝试展示，尚未确认曝光。"
        AdEventName.BID_RESULT -> {
            val winner = when (winnerPlatform) {
                AdPlatform.ADMOB -> "AdMob 胜出"
                AdPlatform.TOPON -> "TopOn 胜出"
                null -> "没有可用广告"
            }
            "比价：AdMob ${admobValue.flowPrice()}、TopOn ${topOnValue.flowPrice()} 美元/次展示，$winner。"
        }
        AdEventName.IMPRESSION -> "$provider 已确认广告曝光，实际来源=$source。"
        AdEventName.SHOW_FAIL -> "$provider 获取或展示结束：${reason.flowReason()}。"
        AdEventName.CLICK -> "$provider 已通知广告点击。"
        AdEventName.DISMISS -> "$provider 已关闭开屏广告。"
        AdEventName.PAID -> {
            val amount = valueMicros?.let { BigDecimal.valueOf(it, 6).stripTrailingZeros().toPlainString() }
                ?: value?.takeIf { it.isFinite() }?.logPrice() ?: "金额未知"
            "收到 $provider 收益回调：$amount ${currency?.logText() ?: "币种未知"}。"
        }
        else -> "收到 $provider 广告事件，详情见调试日志。"
    }
    add("[开屏广告]$scene $message")
    val stage = when (name) {
        AdEventName.LOAD_REQUEST -> "开始加载"
        AdEventName.LOAD_RESULT -> when (result) {
            "filled" -> "加载成功"
            "no_fill" -> "加载无填充"
            "cancelled" -> "加载取消"
            "failed" -> "加载失败"
            else -> "加载结束"
        }
        AdEventName.POSITION -> "尝试展示"
        AdEventName.BID_RESULT -> "竞价结束"
        AdEventName.IMPRESSION -> "已展示"
        AdEventName.SHOW_FAIL -> "展示失败"
        AdEventName.CLICK -> "用户点击"
        AdEventName.DISMISS -> "已关闭"
        AdEventName.PAID -> "收益回调"
        else -> name.analyticsName
    }
    add(buildString {
        append("$prefix $stage | sid=$sid platform=$platform event=${name.analyticsName}")
        if (!isLoadEvent) append(" pos=${position.logText()}")
        if (adSource != null || name == AdEventName.IMPRESSION ||
            (name == AdEventName.LOAD_RESULT && result == "filled")) {
            append(" source=${adSource?.takeIf { it.isNotBlank() }?.logText() ?: "unknown"}")
        }
        result?.let { append(" result=${it.logText()}") }
        latencyMillis?.let { append(" load=${it}ms") }
        bufferSize?.let { append(" buffer=$it") }
        if (name == AdEventName.BID_RESULT) {
            append(" winner=${winnerPlatform ?: "none"} winUsd=${winningValue.logPrice()}")
        }
        if (name == AdEventName.PAID) {
            value?.let { append(" paid=${it.logPrice()}") }
            valueMicros?.let { append(" micros=$it") }
            currency?.let { append(" currency=${it.logText()}") }
            precisionType?.let { append(" precision=${it.logText()}") }
        }
    })
    if (name == AdEventName.LOAD_REQUEST || name == AdEventName.POSITION ||
        requestId != null || responseId != null || mediationAdapterClassName != null) {
        add(buildString {
            append("$prefix 广告标识 | sid=$sid unit=${adUnitId.logText()}")
            requestId?.let { append(" req=${it.logText()}") }
            responseId?.let { append(" resp=${it.logText()}") }
            mediationAdapterClassName?.let { append(" adapter=${it.logText()}") }
        })
    }
    if (reason != null || errorCode != null) {
        add(buildString {
            append("$prefix 回调详情 | sid=$sid")
            errorCode?.let { append(" code=${it.logText()}") }
            reason?.let { append(" reason=${it.logText()}") }
        })
    }
    if (name == AdEventName.BID_RESULT) {
        add("$prefix 竞价候选 | sid=$sid platform=ADMOB ready=${admobAvailable ?: "unknown"} " +
            "priced=${admobPriceAvailable ?: "unknown"} usd=${admobValue.logPrice()}" +
            (admobAdUnitId?.let { " unit=${it.logText()}" } ?: ""))
        add("$prefix 竞价候选 | sid=$sid platform=TOPON ready=${topOnAvailable ?: "unknown"} " +
            "priced=${topOnPriceAvailable ?: "unknown"} usd=${topOnValue.logPrice()}" +
            (topOnAdUnitId?.let { " unit=${it.logText()}" } ?: ""))
    }
}

private fun String.logText(): String = replace('\n', ' ').replace('\r', ' ')

internal fun AdFormat.flowName(): String = when (this) {
    AdFormat.APP_OPEN -> "开屏"
    AdFormat.INTERSTITIAL -> "插页"
    AdFormat.REWARDED -> "激励"
    AdFormat.NATIVE -> "原生"
    AdFormat.BANNER -> "横幅"
}

internal fun AdPlatform?.flowName(): String = when (this) {
    AdPlatform.ADMOB -> "AdMob"
    AdPlatform.TOPON -> "TopOn"
    null -> "无"
}

internal fun flowQuote(available: Boolean, price: Double?): String =
    if (available) price.flowPrice() else "无可用广告"

// Do not turn missing prices into zero, or round a real bid down to zero in diagnostics.
private fun Double?.logPrice(): String = this?.let {
    if (it.isFinite()) BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() else it.toString()
} ?: "unknown"

internal fun Double?.flowPrice(): String =
    this?.takeIf { it.isFinite() && it >= 0 }?.logPrice() ?: "报价未知"

internal fun String?.flowReason(): String = when (this) {
    "dismissed" -> "广告已关闭"
    "invalid_position" -> "业务位置不能为空"
    "invalid_timeout" -> "等待时长配置无效"
    "request_in_progress" -> "已有其他广告请求正在等待"
    "another_full_screen_ad_showing" -> "已有其他全屏广告正在展示"
    "scene_validation_failed" -> "检查展示场景时发生异常"
    "activity_awaiting_first_resume" -> "页面尚未首次进入前台"
    "activity_window_not_attached", "activity_window_not_focused" -> "页面窗口暂时不可展示"
    "native_layout_not_configured" -> "尚未配置全屏原生布局"
    "native_layout_invalid", "native_render_failed" -> "全屏原生布局或渲染失败"
    "native_ad_unavailable", "native_not_ready", "native_session_missing" -> "全屏原生广告已不可用"
    "native_closed_before_impression" -> "全屏原生关闭前未确认曝光"
    "native_no_longer_visible" -> "全屏原生已关闭或不可见"
    "native_handoff_timeout" -> "等待全屏原生页面接收广告超时"
    "native_activity_start_failed" -> "启动全屏原生页面失败"
    "show_failed", "sdk_show_failed" -> "广告展示失败"
    "wait_timeout", "show_callback_timeout" -> "等待广告超时"
    "ad_load_failed", "load_failed" -> "广告加载失败"
    "no_fill", "no_preloaded_ad" -> "暂无可用广告"
    "sdk_not_initialized", "sdk_initializing" -> "广告平台尚未就绪"
    "sdk_initialization_failed" -> "广告平台初始化失败"
    "activity_not_resumed", "app_not_in_foreground" -> "页面暂时不可展示"
    "activity_not_available", "scene_invalid" -> "展示场景已结束"
    "opportunity_cancelled" -> "本次广告机会已取消"
    "consent_not_allowed", "consent_not_obtained" -> "当前许可不允许请求广告"
    "app_open_container_unavailable", "app_open_container_resolution_failed",
    "app_open_container_attach_failed" -> "开屏展示容器不可用"
    "show_exception" -> "调用广告展示时发生异常"
    "dismissed_before_impression" -> "收到关闭回调前未确认曝光"
    else -> "本次未完成，具体原因见调试日志"
}
