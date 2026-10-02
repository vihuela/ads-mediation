package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.appOpenLogLines
import org.junit.Assert.*
import org.junit.Test

class AppOpenLogTest {
    private val event = AdEvent(
        name = AdEventName.IMPRESSION, platform = AdPlatform.TOPON,
        format = AdFormat.APP_OPEN, position = "SP_AppStart",
        sessionId = "full-show-session", adUnitId = "topon-placement", number = 1,
    )

    @Test fun `TopOn platform and AdMob source remain distinct through load and impression`() {
        val shown = event.copy(adSource = "AdMob", responseId = "show-id").appOpenLogLines()
        assertTrue(shown.first().startsWith("[开屏广告][SP_AppStart] TopOn 已确认广告曝光"))
        assertFalse(shown.first().contains("full-show-session"))
        assertFalse(shown.first().contains("topon-placement"))
        assertTrue(shown.drop(1).all { it.startsWith("[开屏广告][调试][SP_AppStart]") })
        assertTrue(shown.first().contains("实际来源=AdMob"))
        assertTrue(shown.last().contains("unit=topon-placement resp=show-id"))
        val loaded = event.copy(
            name = AdEventName.LOAD_RESULT, sessionId = "full-load-session",
            result = "filled", adSource = "AdMob", requestId = "library-request",
            responseId = "sdk-request", latencyMillis = 150, bufferSize = 1,
        ).appOpenLogLines()
        assertTrue(loaded.first().contains("加载成功"))
        assertTrue(loaded[1].contains("source=AdMob result=filled load=150ms buffer=1"))
        assertTrue(loaded.last().contains("req=library-request resp=sdk-request"))
        assertTrue(loaded.drop(1).all { it.contains("sid=full-load-session") })
    }

    @Test fun `bids distinguish unavailable prices from zero and keep tiny prices intact`() {
        val bid = event.copy(
            name = AdEventName.BID_RESULT, winnerPlatform = AdPlatform.TOPON,
            admobAvailable = true, topOnAvailable = true,
            admobPriceAvailable = false, topOnPriceAvailable = true,
            topOnValue = 0.000000001, winningValue = 0.000000001,
            admobAdUnitId = "google-unit", topOnAdUnitId = "topon-placement",
        )
        val lines = bid.appOpenLogLines()
        assertTrue(lines.first().contains("AdMob 报价未知、TopOn 0.000000001 美元/次展示，TopOn 胜出"))
        assertFalse(lines.first().contains("sid="))
        assertTrue(lines[1].contains("winner=TOPON winUsd=0.000000001"))
        assertTrue(lines[2].contains("platform=ADMOB ready=true priced=false usd=unknown unit=google-unit"))
        assertTrue(lines[3].contains("platform=TOPON ready=true priced=true usd=0.000000001"))
        assertTrue(bid.copy(admobPriceAvailable = true, admobValue = 0.0)
            .appOpenLogLines()[2].contains("priced=true usd=0"))
        assertTrue(bid.copy(winnerPlatform = null, winningValue = null)
            .appOpenLogLines()[1].contains("winner=none winUsd=unknown"))
    }

    @Test fun `missing source is explicit and SDK newlines cannot inject log rows`() {
        assertTrue(event.appOpenLogLines()[1].endsWith("source=unknown"))
        val lines = event.copy(
            name = AdEventName.SHOW_FAIL, reason = "network\nfailed", errorCode = "3001\rtest",
        ).appOpenLogLines()
        assertTrue(lines.last().contains("code=3001 test reason=network failed"))
        assertTrue(lines.all { '\n' !in it && '\r' !in it })
        assertFalse(lines.joinToString().contains("null"))
    }

    @Test fun `human errors are explained and paid micros stay exact`() {
        val failed = event.copy(name = AdEventName.SHOW_FAIL, reason = "wait_timeout", errorCode = "123")
            .appOpenLogLines()
        assertTrue(failed.first().contains("等待广告超时"))
        assertFalse(failed.first().contains("wait_timeout"))
        assertTrue(failed.last().contains("code=123 reason=wait_timeout"))
        val paid = event.copy(name = AdEventName.PAID, valueMicros = Long.MAX_VALUE,
            value = Long.MAX_VALUE / 1_000_000.0, currency = "USD").appOpenLogLines()
        assertTrue(paid.first().contains("9223372036854.775807 USD"))
        assertTrue(paid[1].contains("micros=${Long.MAX_VALUE}"))
        assertFalse(paid.first().contains("到账"))
    }

    @Test fun `disabled module logger never calls Android Log`() {
        // JVM Android stubs throw if Log is reached; this also exercises the existing config gate.
        AdsModuleLogger(enabled = false, tag = "AdsMediation").event(event)
    }
}
