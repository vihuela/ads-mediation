package com.cashcraft.ads.mediation.internal.topon

import android.os.SystemClock

/** 主线程按 SDK 广告位记冷却；页面、尺寸和加载轮次变化不清除此记录。 */
internal class TopOnLoadCooldown(private val clock: () -> Long) {
    private val deadlines = mutableMapOf<String, Long>()

    fun failed(placementId: String) {
        val now = clock()
        deadlines.entries.removeAll { it.value <= now }
        deadlines[placementId] = now + COOLDOWN_MILLIS
    }

    fun remainingMillis(placementId: String): Long {
        val deadline = deadlines[placementId] ?: return 0L
        val remaining = (deadline - clock()).coerceAtLeast(0L)
        if (remaining == 0L) deadlines.remove(placementId)
        return remaining
    }

    companion object {
        const val COOLDOWN_MILLIS = 2_100L
        val shared by lazy { TopOnLoadCooldown(SystemClock::elapsedRealtime) }
    }
}
