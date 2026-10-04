package com.cashcraft.ads.mediation.internal

import android.content.Context
import com.cashcraft.ads.mediation.AdSceneType
import com.tencent.mmkv.MMKV
import java.time.Instant
import java.time.ZoneId

/** Persistent actual callbacks, independent of policy switches. One store per Ads instance. */
internal class AdUsageStore(context: Context, firstLaunchTimeMillis: Long) {
    private val preferences = synchronized(lock) {
        if (MMKV.getRootDir() == null) MMKV.initialize(context.applicationContext)
        requireNotNull(MMKV.mmkvWithID("cashcraft_ads_scene_usage"))
    }

    init {
        synchronized(lock) {
            if (!preferences.containsKey(FIRST_LAUNCH)) {
                preferences.encode(FIRST_LAUNCH, firstLaunchTimeMillis)
            }
        }
    }

    val firstLaunchTimeMillis: Long
        get() = preferences.decodeLong(FIRST_LAUNCH, 0L)

    val lastFullscreenCloseMillis: Long?
        get() = if (preferences.containsKey(LAST_CLOSE)) preferences.decodeLong(LAST_CLOSE, 0L) else null

    internal data class DailyUsage(val shows: Long, val clicks: Long)

    val sceneTypeUsageEnabledAtMillis: Long?
        get() = synchronized(lock) {
            if (preferences.containsKey(SCENE_TYPE_ENABLED_AT)) preferences.decodeLong(SCENE_TYPE_ENABLED_AT, 0L)
            else null
        }

    /** First v2 activation is recorded once, including activation with frequency switched off. */
    fun enableSceneTypeUsage(nowMillis: Long) {
        synchronized(lock) {
            if (!preferences.containsKey(SCENE_TYPE_ENABLED_AT)) {
                preferences.encode(SCENE_TYPE_ENABLED_AT, nowMillis)
            }
        }
    }

    /** Reads never reset or persist anything. Dates use the device's current local timezone. */
    fun dailyUsage(nowMillis: Long, sceneType: AdSceneType? = null): DailyUsage = synchronized(lock) {
        if (sceneType != null) {
            val prefix = sceneTypePrefix(nowMillis, sceneType)
            DailyUsage(preferences.decodeLong("${prefix}_shows", 0L), preferences.decodeLong("${prefix}_clicks", 0L))
        } else if (preferences.decodeString(DAY, null) == localDay(nowMillis)) {
            DailyUsage(preferences.decodeLong(SHOWS, 0L), preferences.decodeLong(CLICKS, 0L))
        } else {
            DailyUsage(0L, 0L)
        }
    }

    fun impression(nowMillis: Long, sceneType: AdSceneType? = null) = record(nowMillis, true, sceneType)
    fun click(nowMillis: Long, sceneType: AdSceneType? = null) = record(nowMillis, false, sceneType)

    fun fullscreenClosed(nowMillis: Long) {
        synchronized(lock) { preferences.encode(LAST_CLOSE, nowMillis) }
    }

    private fun record(nowMillis: Long, impression: Boolean, sceneType: AdSceneType?) {
        synchronized(lock) {
            val usage = dailyUsage(nowMillis, sceneType)
            if (sceneType != null) {
                enableSceneTypeUsage(nowMillis)
                val prefix = sceneTypePrefix(nowMillis, sceneType)
                // Never rewrite the un-attributable legacy counters, including their date.
                val key = if (impression) "${prefix}_shows" else "${prefix}_clicks"
                preferences.encode(key, increment(if (impression) usage.shows else usage.clicks))
                return
            }
            preferences.encode(DAY, localDay(nowMillis))
            preferences.encode(SHOWS, if (impression) increment(usage.shows) else usage.shows)
            preferences.encode(CLICKS, if (impression) usage.clicks else increment(usage.clicks))
        }
    }

    private fun localDay(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun sceneTypePrefix(nowMillis: Long, sceneType: AdSceneType): String =
        "scene_type_${localDay(nowMillis)}_${sceneType.configKey}"

    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private companion object {
        val lock = Any()
        const val FIRST_LAUNCH = "first_launch_ms"
        const val LAST_CLOSE = "last_fullscreen_close_ms"
        const val DAY = "local_day"
        const val SHOWS = "shows"
        const val CLICKS = "clicks"
        const val SCENE_TYPE_ENABLED_AT = "scene_type_enabled_at_ms"
    }
}
