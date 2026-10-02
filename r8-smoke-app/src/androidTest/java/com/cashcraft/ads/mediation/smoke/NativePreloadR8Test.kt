@file:Suppress("DEPRECATION")
package com.cashcraft.ads.mediation.smoke
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
/** 不从测试 APK 调用目标被 R8 内联的 SDK/Kotlin 方法；实际探针也在目标 APK 压缩。 */
class NativePreloadR8Test : InstrumentationTestCase() {
    private val activities = mutableListOf<Activity>()

    fun testManagedAdapterClaimDeadlineAndAutomaticRefill() {
        await("AdMob 初始化") { NativePreloadProbeAccess.isReady() }
        try {
            onMain { NativePreloadProbeAccess.startManaged() }
            await("真实适配库存") { NativePreloadProbeAccess.managedReady() }
            onMain { NativePreloadProbeAccess.takeManaged() }
            await("真实适配自动补货") { NativePreloadProbeAccess.managedReady() }
        } finally {
            onMain { NativePreloadProbeAccess.closeManaged() }
        }
    }

    fun testPeekPollQuoteAutomaticRefillAndDestroy() {
        await("AdMob 初始化") { NativePreloadProbeAccess.isReady() }
        onMain { NativePreloadProbeAccess.start(instrumentation.targetContext) }
        await("buffer=1") { NativePreloadProbeAccess.available(1) }
        onMain { NativePreloadProbeAccess.checkPeekAndPoll() }
        // 只 start 一次，领取对象保持存活，补货完全由 SDK 完成。
        await("消费自动补货") { NativePreloadProbeAccess.available(2) }
        onMain { NativePreloadProbeAccess.close() }
        SystemClock.sleep(2_000)
        onMain { NativePreloadProbeAccess.checkEmpty() }
    }

    fun testPreloadedAdSurvivesActivityADestructionAndImpressesInActivityB() {
        await("AdMob 初始化") { NativePreloadProbeAccess.isReady() }

        // 1. 启动 Activity A
        val activityA = launchActivity(NativeSmokeActivity::class.java)

        // 2. 启动预加载并等待 buffer=1
        onMain { NativePreloadProbeAccess.start(activityA) }
        await("Activity A 预加载完成") { NativePreloadProbeAccess.available(1) }

        // 3. 验证队首 peek 非消费、锁定版本队列反射及报价关联
        onMain { NativePreloadProbeAccess.checkPeekAndQuote() }

        // 4. 销毁 Activity A
        onMain { activityA.finish() }
        await("Activity A 已销毁") { activityA.isDestroyed }

        // 5. 验证预加载对象在 Activity A 销毁后存活，身份未变
        onMain { NativePreloadProbeAccess.checkSurvivesActivityADestruction() }

        // 6. 独立启动 Activity B
        val activityB = launchActivity(NativeSmokeDetailsActivity::class.java)

        // 7. Activity B 领取并独立创建平台 NativeAdView 注册/渲染
        onMain { NativePreloadProbeAccess.pollAndRenderInActivity(activityB) }

        // 8. 验证平台 View 可见及 SDK 真实曝光（和 paid 回调如果提供）
        await("Activity B 平台 View 实际可见") { NativePreloadProbeAccess.isRenderedViewVisible() }
        await("Activity B SDK 真实曝光") { NativePreloadProbeAccess.getImpressionCount() > 0 }
        onMain { NativePreloadProbeAccess.verifyRenderedAdExposure() }
        Log.i(TAG, "B 页真实回调：曝光=${onMain { NativePreloadProbeAccess.getImpressionCount() }}，" +
            "收益=${onMain { NativePreloadProbeAccess.getPaidCount() }}")
        snapshot("native-preload-r8-transfer-b")
        // 9. 验证 SDK 自动补货：Activity B 消费后未再次 start，SDK 自动补齐 buffer=1
        await("消费后 SDK 自动补货") { NativePreloadProbeAccess.available(2) }

        // 10. 关闭并验证清空
        onMain { NativePreloadProbeAccess.close() }
        SystemClock.sleep(2_000)
        onMain { NativePreloadProbeAccess.checkEmpty() }

        // 记录实际样本，不把官方测试 ID 的零价当成不可变契约，也不自动关闭非零门槛。
        val priceSummary = onMain { NativePreloadProbeAccess.getPriceBoundarySummary() }
        Log.i(TAG, "Release 探针价格边界: $priceSummary")
    }

    fun testBackgroundCloseAndRestartDoesNotReuseOldInventory() {
        await("AdMob 初始化") { NativePreloadProbeAccess.isReady() }
        launchActivity(NativeSmokeActivity::class.java)
        await("应用前台") { NativePreloadProbeAccess.isAppInForeground() }
        onMain {
            NativePreloadProbeAccess.observeLifecycle()
            NativePreloadProbeAccess.start(instrumentation.targetContext)
        }
        await("旧会话库存") { NativePreloadProbeAccess.available(1) }
        onMain { NativePreloadProbeAccess.checkPeekAndQuote() }

        val details = launchActivity(NativeSmokeDetailsActivity::class.java)
        // 等待既有生命周期监测器的 pause 宽限；不能只检查 B 已恢复后的瞬时前台值。
        SystemClock.sleep(500)
        assertTrue(onMain { NativePreloadProbeAccess.isAppInForeground() })
        assertEquals(0, onMain { NativePreloadProbeAccess.getBackgroundCount() })
        onMain { NativePreloadProbeAccess.checkSurvivesActivityADestruction() }

        assertTrue(onMain { details.moveTaskToBack(true) })
        await("真实退入后台") { !NativePreloadProbeAccess.isAppInForeground() }
        assertEquals(1, onMain { NativePreloadProbeAccess.getBackgroundCount() })
        // 此处只验证 SDK 可控关闭机制；没有接通生产后台或 UMP 许可代次。
        onMain { NativePreloadProbeAccess.close() }
        SystemClock.sleep(3_000)
        onMain { NativePreloadProbeAccess.checkEmpty() }

        launchActivity(NativeSmokeDetailsActivity::class.java)
        await("返回应用前台") { NativePreloadProbeAccess.isAppInForeground() }
        onMain { NativePreloadProbeAccess.start(instrumentation.targetContext) }
        await("关闭后新会话库存") { NativePreloadProbeAccess.available(1) }
        onMain {
            NativePreloadProbeAccess.checkNewSessionQuote()
            NativePreloadProbeAccess.checkPeekAndPoll()
            // 领取触发 SDK 自动补货，在同一主线程操作中关闭，检查旧补货不复活队列。
            NativePreloadProbeAccess.close()
        }
        SystemClock.sleep(3_000)
        onMain { NativePreloadProbeAccess.checkEmpty() }

        onMain { NativePreloadProbeAccess.start(instrumentation.targetContext) }
        await("补货关闭后再建库存") { NativePreloadProbeAccess.available(1) }
        onMain {
            NativePreloadProbeAccess.checkNewSessionQuote()
            NativePreloadProbeAccess.checkPeekAndPoll()
            NativePreloadProbeAccess.close()
        }
        Log.i(TAG, "后台机制：Activity 内切换后台次数=0，真实退后台次数=1；" +
            "关闭后空队列两次，同 key 两次重建均领取新身份；UMP/TopOn 未验证")
    }

    private fun launchActivity(clazz: Class<out Activity>): Activity {
        val intent = Intent(instrumentation.targetContext, clazz)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        activities += activity
        return activity
    }
    private fun snapshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        // Release 测试 APK 不依赖仅在测试侧引用、可能已被目标 R8 删除的 Kotlin IO helper。
        val stream = FileOutputStream(File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "$name.png"))
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } finally {
            stream.close()
            bitmap.recycle()
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(100)
        }
        fail("超时：$label")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { result.set(block()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    override fun tearDown() {
        try {
            onMain { NativePreloadProbeAccess.cleanup() }
            onMain {
                activities.forEach { if (!it.isDestroyed) it.finish() }
                activities.clear()
            }
        } finally {
            super.tearDown()
        }
    }

    private companion object {
        const val TAG = "NativePreloadR8Test"
    }
}
