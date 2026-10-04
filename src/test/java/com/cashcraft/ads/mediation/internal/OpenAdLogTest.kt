package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.flowPrice
import com.cashcraft.ads.mediation.flowQuote
import com.cashcraft.ads.mediation.flowReason
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OpenAdLogTest {
    @Test fun `interstitial table contains only two candidates and preserves its task position and immediate result`() {
        val tag = "InterAdTableTest"
        ShadowLog.clear()
        val logger = AdsModuleLogger(true, tag)
        val auction = FullScreenAdAuction(
            formats = listOf(AdFormat.INTERSTITIAL),
            read = { _, _ -> FullScreenAdAuction.Inventory(true, true) },
            price = { _, platform -> if (platform == AdPlatform.ADMOB) .01 else .02 },
            trace = { logger.sceneTask(AdFormat.INTERSTITIAL, "inter-A", "save", 100, it) },
            traceTable = { logger.sceneTask(AdFormat.INTERSTITIAL, "inter-A", "save", 100, it, multiline = true) },
        )
        auction.snapshot(true)
        auction.select()
        val logs = ShadowLog.getLogsForTag(tag)
        val tableIndex = logs.indexOfFirst { it.msg.contains("比价候选表") }
        assertEquals(5, logs[tableIndex].msg.lines().size)
        assertTrue(logs.all { it.msg.startsWith("[插页任务][任务=inter-A][位置=save]") })
        assertTrue(logs.none { it.msg.contains("开屏") || it.msg.contains("NativeFallback") })
        assertEquals(tableIndex + 2, logs.size)
        assertTrue(logs[tableIndex + 1].msg.endsWith("比价结果：TopOn 插页胜出，报价 0.02 美元/次展示。"))
    }

    @Test fun `one multiline candidate table is immediately followed by one result entry`() {
        val tag = "OpenAdTableTest"
        ShadowLog.clear()
        val logger = AdsModuleLogger(true, tag)
        val auction = FullScreenAdAuction(
            read = { _, _ -> FullScreenAdAuction.Inventory(true, true) },
            price = { format, platform ->
                (if (format == AdFormat.APP_OPEN) .01 else .03) + (if (platform == AdPlatform.TOPON) .01 else 0.0)
            },
            trace = { logger.sceneTask(AdFormat.APP_OPEN, "A", "cold\nstart", 380, it) },
            traceTable = { logger.sceneTask(AdFormat.APP_OPEN, "A", "cold\nstart", 380, it, multiline = true) },
        )
        auction.snapshot(true)
        auction.select()
        val logs = ShadowLog.getLogsForTag(tag)
        val tableIndex = logs.indexOfFirst { it.msg.contains("比价候选表") }
        assertTrue(tableIndex >= 0)
        assertEquals(1, logs.count { it.msg.contains("比价候选表") })
        val lines = logs[tableIndex].msg.lines()
        assertEquals(7, lines.size) // Title, header, separator, four candidates: one Logcat entry.
        assertTrue(lines[0].contains("[任务=A][位置=cold start]"))
        val rows = lines.drop(3).map { line -> line.split('|').drop(1).dropLast(1).map(String::trim) }
        assertEquals(listOf(
            listOf("开屏", "AdMob", "已就绪", "0.01", "--"),
            listOf("开屏", "TopOn", "已就绪", "0.02", "是"),
            listOf("插页", "AdMob", "已就绪", "0.03", "--"),
            listOf("插页", "TopOn", "已就绪", "0.04", "是"),
        ), rows)
        assertEquals(1, lines.drop(1).map { line -> line.sumOf { if (it.code > 127) 2 else 1 } }.distinct().size)
        assertEquals(tableIndex + 2, logs.size)
        assertTrue(logs[tableIndex + 1].msg.endsWith("比价结果：TopOn 插页胜出，报价 0.04 美元/次展示。"))
    }

    @Test fun `Chinese descriptions distinguish no ad unknown quote zero and exact small bids`() {
        assertEquals("无可用广告", flowQuote(false, null))
        assertEquals("报价未知", flowQuote(true, null))
        assertEquals("0", flowQuote(true, 0.0))
        assertEquals("0.000000001", 1e-9.flowPrice())
        assertEquals("报价未知", Double.NaN.flowPrice())
        assertEquals("暂无可用广告", "no_preloaded_ad".flowReason())
        assertEquals("本次广告机会已取消", "opportunity_cancelled".flowReason())
        assertEquals("尚未配置全屏原生布局", "native_layout_not_configured".flowReason())
    }

    @Test fun `task logs preserve correlation and events and disabled logging is silent`() {
        val tag = "OpenAdFlowTest"
        ShadowLog.clear()
        val logger = AdsModuleLogger(true, tag)
        logger.sceneTask(AdFormat.APP_OPEN, "task-A", "cold\nstart", 0, "开始开屏广告请求。")
        logger.sceneTask(AdFormat.APP_OPEN, "task-A", "cold\nstart", 200, "关联展示记录：TopOn 插页；展示记录=session-B。")
        logger.sceneTask(AdFormat.APP_OPEN, "task-A", "cold\nstart", 300, "任务结束：广告已展示并关闭。")
        AdsModuleLogger(false, tag).sceneTask(AdFormat.APP_OPEN, "task-C", "cold", 0, "开始开屏广告请求。")
        val logs = ShadowLog.getLogsForTag(tag)
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.type == android.util.Log.INFO && it.msg.contains("[任务=task-A][位置=cold start][总耗时=") })
        assertTrue(logs[0].msg.contains("开始开屏广告请求。"))
        assertTrue(logs[1].msg.contains("关联展示记录：TopOn 插页；展示记录=session-B。"))
        assertTrue(logs[2].msg.contains("任务结束：广告已展示并关闭。"))
    }
}
