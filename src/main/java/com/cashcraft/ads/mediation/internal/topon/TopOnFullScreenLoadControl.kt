package com.cashcraft.ads.mediation.internal.topon

/** 只调度加载轮次，不持有展示机会或 Activity；所有入口由主线程调用。 */
internal class TopOnFullScreenLoadControl(
    private val clock: () -> Long,
    private val cooldown: TopOnLoadCooldown,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val canLoad: () -> Boolean,
) {
    private class Round(
        val isReady: () -> Boolean,
        val isLoading: () -> Boolean,
        val load: (Boolean) -> Boolean,
    ) {
        var active = false
        var exhausted = false
        var failures = 0
        var retryAt = 0L
        var afterShow = false
        var inFlight = false
        var pending: Runnable? = null
    }

    private val rounds = mutableMapOf<String, Round>()
    private var paused = false
    private var loadAllowed = true

    fun request(
        placementId: String,
        newOpportunity: Boolean = false,
        afterShow: Boolean = false,
        isReady: () -> Boolean,
        isLoading: () -> Boolean,
        load: (Boolean) -> Boolean,
    ) {
        val round = rounds.getOrPut(placementId) { Round(isReady, isLoading, load) }
        if (!round.active) {
            if (round.exhausted && !newOpportunity && !afterShow) return
            begin(round, afterShow)
        } else {
            // 活跃轮次的补货可绕过旧库存，但不能刷新已经使用的失败预算。
            round.afterShow = round.afterShow || afterShow
        }
        drive(placementId, round)
    }

    fun failed(placementId: String): Boolean {
        val round = rounds[placementId] ?: return false
        if (!round.inFlight) return false
        // 先发布共享冷却，逐次失败通知的重入也不能提前发起下一请求。
        cooldown.failed(placementId)
        round.inFlight = false
        round.failures++
        if (round.failures >= 4) {
            round.active = false
            round.exhausted = true
            cancelPending(round)
            return true
        }
        round.retryAt = clock() + (2_000L shl (round.failures - 1))
        drive(placementId, round)
        return true
    }

    fun loaded(placementId: String): Boolean {
        val round = rounds[placementId] ?: return false
        if (!round.inFlight) return false
        finishRound(round)
        return true
    }

    private fun finishRound(round: Round) {
        cancelPending(round)
        round.active = false
        round.exhausted = false
        round.inFlight = false
        round.failures = 0
        round.retryAt = 0L
        round.afterShow = false
    }

    fun onPolicyChanged(allowed: Boolean) {
        val restored = !loadAllowed && allowed
        loadAllowed = allowed
        rounds.toMap().forEach { (id, round) ->
            if (!allowed) cancelPending(round)
            if (restored && round.exhausted) begin(round, round.afterShow)
            drive(id, round)
        }
    }

    fun pause() {
        paused = true
        rounds.values.forEach(::cancelPending)
    }

    fun resume() {
        paused = false
        rounds.toMap().forEach { (id, round) -> drive(id, round) }
    }

    private fun begin(round: Round, afterShow: Boolean) {
        round.active = true
        round.exhausted = false
        round.failures = 0
        round.retryAt = 0L
        round.afterShow = afterShow
    }

    private fun drive(placementId: String, round: Round) {
        if (!round.active || round.inFlight || round.pending != null || paused || !loadAllowed || !canLoad()) return
        if (round.isLoading()) return
        if (!round.afterShow && round.isReady()) {
            finishRound(round)
            return
        }
        val wait = maxOf(round.retryAt - clock(), cooldown.remainingMillis(placementId))
        if (wait > 0) {
            lateinit var task: Runnable
            task = Runnable {
                if (round.pending !== task) return@Runnable
                round.pending = null
                drive(placementId, round)
            }
            round.pending = task
            postDelayed(task, wait)
            return
        }
        // 真正调用 SDK 前复查；暂停、库存命中和已有请求都不消耗次数。
        if (paused || !loadAllowed || !canLoad() || round.isLoading()) return
        if (!round.afterShow && round.isReady()) {
            finishRound(round)
            return
        }
        round.inFlight = true
        if (!round.load(round.afterShow)) round.inFlight = false
    }

    private fun cancelPending(round: Round) {
        round.pending?.let(cancel)
        round.pending = null
    }
}
