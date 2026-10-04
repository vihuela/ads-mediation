package com.cashcraft.ads.mediation

import android.test.InstrumentationTestCase
import com.cashcraft.ads.mediation.internal.AdUsageStore
import com.tencent.mmkv.MMKV
import java.time.Instant
import java.time.ZoneId

/** 使用独立 SDK 测试包验证真实 JNI，不访问宿主的健康数据或广告。 */
@Suppress("DEPRECATION")
class AdUsagePersistenceTest : InstrumentationTestCase() {
    fun testSceneUsagePersistsWithoutImportingSharedPreferences() {
        val context = instrumentation.context
        if (MMKV.getRootDir() == null) MMKV.initialize(context)
        val storage = requireNotNull(MMKV.mmkvWithID("cashcraft_ads_scene_usage"))
        val legacy = context.getSharedPreferences("cashcraft_ads_usage", 0)
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        storage.clearAll()
        legacy.edit().putLong("first_launch_ms", 1L)
            .putLong("last_fullscreen_close_ms", 2L)
            .putLong("main_type_v2_${day}_open_shows", 99L).commit()
        val old = legacy.all.toMap()
        try {
            val store = AdUsageStore(context, now - 1000L)
            assertEquals(now - 1000L, store.firstLaunchTimeMillis)
            assertNull(store.lastFullscreenCloseMillis)
            assertEquals(AdUsageStore.DailyUsage(0, 0), store.dailyUsage(now, AdSceneType.OPEN))
            store.impression(now, AdSceneType.OPEN)
            store.click(now, AdSceneType.OPEN)
            store.fullscreenClosed(now)
            storage.sync()
            storage.clearMemoryCache()
            val restored = AdUsageStore(context, now)
            assertEquals(AdUsageStore.DailyUsage(1, 1), restored.dailyUsage(now, AdSceneType.OPEN))
            assertEquals(AdUsageStore.DailyUsage(0, 0), restored.dailyUsage(now, AdSceneType.NATIVE_FULLSCREEN))
            assertEquals(now, restored.lastFullscreenCloseMillis)
            assertEquals(now - 1000L, restored.firstLaunchTimeMillis)
            assertTrue(storage.containsKey("scene_type_${day}_open_shows"))
            assertEquals(old, legacy.all)
        } finally {
            storage.clearAll()
            legacy.edit().clear().commit()
        }
    }
}
