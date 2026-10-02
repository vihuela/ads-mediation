package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import org.junit.Assert.*
import org.junit.Test

class NativeFlowLogTest {
    @Test fun `native log separates human results from identity and keeps unknown distinct from zero`() {
        val event = AdEvent(AdEventName.BID_RESULT, AdPlatform.TOPON, AdFormat.NATIVE,
            "home_native", "session-secret", "full-platform-id", 1,
            requestId = "request-secret", slotId = "home", winnerPlatform = AdPlatform.TOPON,
            admobAvailable = true, topOnAvailable = true, admobValue = null, topOnValue = 0.0)
        val ordinary = requireNotNull(formatNativeEventLogMessage(event))
        assertTrue(ordinary.startsWith("[原生广告][home]"))
        assertTrue(ordinary.contains("AdMob 报价未知"))
        assertTrue(ordinary.contains("TopOn 0 美元/次展示"))
        assertFalse(ordinary.contains("secret"))
        assertFalse(ordinary.contains("full-platform-id"))
        assertFalse(ordinary.contains("落选"))
        assertTrue(formatNativeDebugLogMessage(event).contains("获取记录=request-secret"))
        assertTrue(formatNativeDebugLogMessage(event).contains("展示记录=session-secret"))
        assertNull(formatNativeEventLogMessage(event.copy(name = AdEventName.LOAD_RESULT)))
        assertTrue(formatNativeEventLogMessage(event.copy(name = AdEventName.IMPRESSION))!!.contains("已确认广告曝光"))
        assertFalse(formatNativeEventLogMessage(event.copy(name = AdEventName.PAID, value = 0.0, currency = "USD"))!!.contains("到账"))
        assertFalse("bad\nline\rtext".nativeLogText().contains('\n'))
        assertEquals(200, "x".repeat(300).nativeLogText().length)
        AdsModuleLogger(false, "test").native("home") { error("disabled logger evaluated message") }
    }
    @Test fun `native revenue display preserves micros and loading debug names observable outcomes`() {
        val event = AdEvent(AdEventName.PAID, AdPlatform.TOPON, AdFormat.NATIVE,
            "home_native", "session", "platform-id", 1, slotId = "home",
            value = Long.MAX_VALUE / 1_000_000.0, valueMicros = Long.MAX_VALUE, currency = "USD")
        assertTrue(formatNativeEventLogMessage(event)!!.contains("9223372036854.775807 USD"))
        assertTrue(formatNativeDebugLogMessage(event).contains("原始收益微单位=${Long.MAX_VALUE}"))
        assertTrue(formatNativeDebugLogMessage(event).contains("原始收益金额="))
        assertTrue(formatNativeEventLogMessage(event.copy(valueMicros = 0, value = 0.0))!!.contains("0 USD"))
        assertTrue(formatNativeEventLogMessage(event.copy(valueMicros = null, value = Double.NaN))!!.contains("金额未知"))

        val load = event.copy(name = AdEventName.LOAD_RESULT, result = "filled", latencyMillis = 12,
            value = null, valueMicros = null, currency = null)
        val debug = formatNativeDebugLogMessage(load)
        assertTrue(debug.contains("本层获取成功，尚未确认曝光"))
        assertTrue(debug.contains("平台=TopOn"))
        assertTrue(debug.contains("获取等待=12ms"))
        assertFalse(debug.contains("网络"))
        assertTrue(formatNativeDebugLogMessage(load.copy(result = "cancelled")).contains("本层获取已取消"))
        assertTrue(formatNativeDebugLogMessage(load.copy(result = "no_fill")).contains("暂无广告填充"))
        val failure = load.copy(result = "failed", reason = "consent_not_obtained", errorCode = "bad\ncode\r" + "x".repeat(300))
        assertTrue(formatNativeDebugLogMessage(failure).contains("当前许可不允许请求"))
        assertTrue(formatNativeDebugLogMessage(failure).contains("错误码=bad code "))
        assertFalse(formatNativeDebugLogMessage(failure).contains('\n'))
        assertFalse(formatNativeDebugLogMessage(failure).contains("x".repeat(201)))
    }

    @Test fun `native exceptions keep root stack without SDK messages and bound cyclic causes`() {
        val root = IllegalStateException("https://secret.example/material?key=secret\nresponse-json")
        root.stackTrace = arrayOf(StackTraceElement("NativeSource", "render", "NativeSource.kt", 42))
        val wrapper = IllegalArgumentException("device-id-secret", root)
        val text = formatNativeExceptionLogMessage(wrapper)
        assertTrue(text.contains("异常类型=IllegalArgumentException"))
        assertTrue(text.contains("根因类型=IllegalStateException"))
        assertTrue(text.contains("NativeSource.render(NativeSource.kt:42)"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("response-json"))
        root.initCause(wrapper)
        assertTrue(formatNativeExceptionLogMessage(wrapper).contains("原因链已截断"))
        AdsModuleLogger(false, "test").native("home", error = wrapper) { error("disabled message evaluated") }
        AdsModuleLogger(false, "test").eventDispatchFailed(
            AdEvent(AdEventName.PAID, AdPlatform.TOPON, AdFormat.NATIVE, "home", "session", "unit", 1), wrapper)
    }
}
