package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.flowName
import com.cashcraft.ads.mediation.flowQuote
import com.cashcraft.ads.mediation.flowPrice
import java.util.concurrent.CopyOnWriteArrayList

/** SDK callbacks wake waiting tasks; cancelling a task only removes its observer. Main thread only. */
internal object FullScreenLoadSignals {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun add(listener: () -> Unit) { listeners.addIfAbsent(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun changed() { listeners.forEach { runCatching { it() } } }
}

/** Provider/format slots, not consumed ad objects. Losers remain in shared SDK caches. */
internal class FullScreenAdAuction(
    private val read: (AdFormat, AdPlatform) -> Inventory,
    private val price: (AdFormat, AdPlatform) -> Double?,
    private val trace: (String) -> Unit = {},
    private val traceTable: (String) -> Unit = trace,
    private val formats: List<AdFormat> = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL),
) {
    data class Inventory(val ready: Boolean, val settled: Boolean, val enabled: Boolean = true)
    data class Winner(val format: AdFormat, val bid: BidDecision)
    private class Candidate(val format: AdFormat, val platform: AdPlatform) {
        var ready = false
        var settled = false
        var lastState: String? = null
    }
    private val candidates = formats.flatMap { format ->
        AdPlatform.entries.map { Candidate(format, it) }
    }
    private var frozen = false

    /** At the deadline, new arrivals are excluded, but expired/consumed inventory is still removed. */
    fun snapshot(acceptNewResults: Boolean): DisplayOpportunityController.LoadSnapshot {
        if (!frozen) candidates.forEach { c ->
            val inventory = read(c.format, c.platform)
            c.ready = inventory.enabled && inventory.ready && (acceptNewResults || c.ready)
            c.settled = c.settled || c.ready || !inventory.enabled || (acceptNewResults && inventory.settled)
            val state = when {
                !inventory.enabled -> "未启用，不参与等待"
                c.ready -> "已就绪"
                c.settled -> "无可用广告"
                else -> "加载中"
            }
            if (state != c.lastState) {
                c.lastState = state
                trace("候选状态：${c.platform.flowName()} ${c.format.flowName()}，$state。")
            }
        }
        return DisplayOpportunityController.LoadSnapshot(candidates.any { it.ready }, candidates.all { it.settled })
    }

    /** Freeze once: platform auction per format, then the format auction. No new load is started. */
    fun select(): Winner? {
        check(!frozen) { "Full-screen auction already decided" }
        snapshot(acceptNewResults = false)
        frozen = true
        trace("确定本次候选：可用 ${candidates.count { it.ready }} 条，仍在加载 ${candidates.count { !it.settled }} 条；后续加载结果仅供下次使用。")
        fun bid(format: AdFormat): BidDecision {
            val a = candidates.first { it.format == format && it.platform == AdPlatform.ADMOB }
            val t = candidates.first { it.format == format && it.platform == AdPlatform.TOPON }
            val selection = BidCandidateSelector.select(a.ready, if (a.ready) price(format, a.platform) else null,
                t.ready, if (t.ready) price(format, t.platform) else null)
            return BidDecision(selection, a.ready, t.ready, selection?.admobPriceUsd, selection?.toponPriceUsd)
        }
        val decisions = formats.associateWith(::bid)
        // maxByOrNull keeps the first on ties: app-open precedes interstitial in open scenes.
        val winner = decisions.mapNotNull { (format, decision) ->
            decision.selection?.let { Winner(format, decision) }
        }.maxByOrNull { it.bid.selection?.priceUsd ?: Double.NEGATIVE_INFINITY }
        val rows = listOf(listOf("类型", "平台", "状态", "报价", "类型内胜者")) + candidates.map { candidate ->
            val decision = decisions.getValue(candidate.format)
            val quote = if (candidate.platform == AdPlatform.ADMOB) decision.admobPriceUsd else decision.topOnPriceUsd
            listOf(candidate.format.flowName(), candidate.platform.flowName(),
                candidate.lastState.orEmpty().substringBefore("，"), flowQuote(candidate.ready, quote),
                if (decision.selection?.winner == candidate.platform) "是" else "--")
        }
        // Cells contain only module-owned Chinese text and ASCII names/numbers; Chinese occupies two columns.
        fun width(value: String) = value.sumOf { if (it.code > 127) 2 else 1 }
        val widths = rows.first().indices.map { column -> rows.maxOf { width(it[column]) } }
        fun row(values: List<String>) = values.mapIndexed { column, value ->
            value + " ".repeat(widths[column] - width(value))
        }.joinToString(" | ", "| ", " |")
        val table = buildString {
            appendLine("比价候选表（报价单位：美元/次展示）：")
            appendLine(row(rows.first()))
            appendLine(widths.joinToString("-+-", "+-", "-+") { "-".repeat(it) })
            append(rows.drop(1).joinToString("\n", transform = ::row))
        }
        traceTable(table)
        val result = winner?.let {
            val quote = it.bid.selection?.priceUsd?.let { price -> "报价 ${price.flowPrice()} 美元/次展示" } ?: "报价未知"
            "${it.bid.selection?.winner.flowName()} ${it.format.flowName()}胜出，$quote"
        } ?: "${formats.joinToString("和") { it.flowName() }}无可用广告"
        trace("比价结果：$result。")
        return winner
    }
}
