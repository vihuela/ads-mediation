package com.cashcraft.ads.mediation.internal

import android.content.Context
import com.cashcraft.ads.mediation.AdMainType
import java.time.Instant
import java.time.ZoneId

/** Persistent actual callbacks, independent of policy switches. One store per Ads instance. */
internal class AdUsageStore(context: Context, firstLaunchTimeMillis: Long) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "cashcraft_ads_usage", Context.MODE_PRIVATE,
    )

    init {
        synchronized(preferences) {
            if (!preferences.contains(FIRST_LAUNCH)) {
                preferences.edit().putLong(FIRST_LAUNCH, firstLaunchTimeMillis).commit()
            }
        }
    }

    val firstLaunchTimeMillis: Long
        get() = preferences.getLong(FIRST_LAUNCH, 0L)

    val lastFullscreenCloseMillis: Long?
        get() = if (preferences.contains(LAST_CLOSE)) preferences.getLong(LAST_CLOSE, 0L) else null

    internal data class DailyUsage(val shows: Long, val clicks: Long)

    val mainTypeUsageEnabledAtMillis: Long?
        get() = synchronized(preferences) {
            if (preferences.contains(MAIN_TYPE_ENABLED_AT)) preferences.getLong(MAIN_TYPE_ENABLED_AT, 0L)
            else null
        }

    /** First v2 activation is recorded once, including activation with frequency switched off. */
    fun enableMainTypeUsage(nowMillis: Long) {
        synchronized(preferences) {
            if (!preferences.contains(MAIN_TYPE_ENABLED_AT)) {
                preferences.edit().putLong(MAIN_TYPE_ENABLED_AT, nowMillis).commit()
            }
        }
    }

    /** Reads never reset or persist anything. Dates use the device's current local timezone. */
    fun dailyUsage(nowMillis: Long, mainType: AdMainType? = null): DailyUsage = synchronized(preferences) {
        if (mainType != null) {
            val prefix = mainTypePrefix(nowMillis, mainType)
            DailyUsage(preferences.getLong("${prefix}_shows", 0L), preferences.getLong("${prefix}_clicks", 0L))
        } else if (preferences.getString(DAY, null) == localDay(nowMillis)) {
            DailyUsage(preferences.getLong(SHOWS, 0L), preferences.getLong(CLICKS, 0L))
        } else {
            DailyUsage(0L, 0L)
        }
    }

    fun impression(nowMillis: Long, mainType: AdMainType? = null) = record(nowMillis, true, mainType)
    fun click(nowMillis: Long, mainType: AdMainType? = null) = record(nowMillis, false, mainType)

    fun fullscreenClosed(nowMillis: Long) {
        synchronized(preferences) { preferences.edit().putLong(LAST_CLOSE, nowMillis).commit() }
    }

    private fun record(nowMillis: Long, impression: Boolean, mainType: AdMainType?) {
        synchronized(preferences) {
            val usage = dailyUsage(nowMillis, mainType)
            if (mainType != null) {
                enableMainTypeUsage(nowMillis)
                val prefix = mainTypePrefix(nowMillis, mainType)
                // Never rewrite the un-attributable legacy counters, including their date.
                val key = if (impression) "${prefix}_shows" else "${prefix}_clicks"
                preferences.edit().putLong(key, increment(if (impression) usage.shows else usage.clicks)).commit()
                return
            }
            preferences.edit()
                .putString(DAY, localDay(nowMillis))
                .putLong(SHOWS, if (impression) increment(usage.shows) else usage.shows)
                .putLong(CLICKS, if (impression) usage.clicks else increment(usage.clicks))
                .commit()
        }
    }

    private fun localDay(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun mainTypePrefix(nowMillis: Long, mainType: AdMainType): String =
        "main_type_v2_${localDay(nowMillis)}_${mainType.configKey}"

    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private companion object {
        const val FIRST_LAUNCH = "first_launch_ms"
        const val LAST_CLOSE = "last_fullscreen_close_ms"
        const val DAY = "local_day"
        const val SHOWS = "shows"
        const val CLICKS = "clicks"
        const val MAIN_TYPE_ENABLED_AT = "main_type_v2_enabled_at_ms"
    }
}
