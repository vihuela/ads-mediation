package com.cashcraft.ads.mediation

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.os.SystemClock
import android.view.LayoutInflater
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.cashcraft.ads.mediation.internal.nativeads.createDefaultNativeLayout
import java.io.File

/** 无请求的 owner 检查与显式运行的两平台测试广告验收；TopOn必须开启官方调试模式，不点击广告。 */
@Suppress("DEPRECATION")
class NativeFragmentTest : InstrumentationTestCase() {
    fun testPositionConflictAndOldConnectionCannotDestroyReplacement() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeFragmentTestActivity
        var outcome: Result<Unit>? = null
        try {
            instrumentation.runOnMainSync {
                outcome = runCatching {
                    val request = NativeRequest("exclusive-position")
                    val first = AdsNativeView(activity, activity, request, active = false)
                    val rejected = AdsNativeView(activity, activity, request, active = false)
                    assertEquals(NativeState.Failed("native_position_occupied"), rejected.state)
                    assertEquals(NativeState.Idle, first.state)
                    rejected.destroy()
                    first.release()
                    val replacement = AdsNativeView(activity, activity, request, active = false)
                    first.destroy()
                    first.release()
                    assertEquals(NativeState.Idle, replacement.state)
                    replacement.destroy()
                }
            }
            outcome!!.getOrThrow()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testRecreatedFragmentViewPermanentlyDestroysOnlyItsOldCard() =
        verifyFragmentViewRecreation(NativeRetentionPolicy.DESTROY_ON_HIDE)

    fun testRetainPolicyStillEndsAtFragmentViewOwnerDestruction() =
        verifyFragmentViewRecreation(NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE)

    fun testRealAdFragmentRecreationAndExplicitRetry() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        val platform = if (args.getString("platform") == "topon") AdPlatform.TOPON else AdPlatform.ADMOB
        val adUnitId = if (platform == AdPlatform.TOPON) requireNotNull(args.getString("nativePlacement"))
            else "ca-app-pub-3940256099942544/2247696110"
        // 公开Identifier调用在测试线程执行，避免主线程阻塞；不记录设备ID。
        val topOnTestDeviceId = if (platform == AdPlatform.TOPON) args.getString("toponTestDeviceId") ?: run {
            val client = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val info = client.getMethod("getAdvertisingIdInfo", android.content.Context::class.java)
                .invoke(null, instrumentation.targetContext)
            info.javaClass.getMethod("getId").invoke(info) as String
        } else null
        onMain {
            val provider = if (platform == AdPlatform.TOPON) {
                com.thinkup.core.api.TUSDK.setDebuggerConfig(instrumentation.targetContext,
                    requireNotNull(topOnTestDeviceId?.takeIf(String::isNotBlank)),
                    com.thinkup.core.api.TUDebuggerConfig.Builder(50).build())
                TopOnProviderConfig(TopOnIds(
                    requireNotNull(args.getString("applicationId")),
                    requireNotNull(args.getString("applicationKey")),
                    args.getString("appOpenPlacement") ?: "unused-open",
                    args.getString("interstitialPlacement") ?: "unused-interstitial",
                    args.getString("rewardedPlacement") ?: "unused-rewarded",
                    nativePlacementId = adUnitId))
            } else AdMobProviderConfig(AdMobIds.TEST)
            Ads.initialize(instrumentation.targetContext.applicationContext as Application,
                AdsConfig(provider, umpConsent = UmpConsentConfig(enabled = false), autoShowAppOpen = false))
        }
        await("${platform.analyticsValue} 初始化") { Ads.nativeAvailability(platform).ready }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeFragmentTestActivity
        try {
            for (policy in NativeRetentionPolicy.entries) {
                var rejectLayout = true
                val fragment = NativeTestFragment().apply {
                    retentionPolicy = policy
                    adRequest = NativeRequest("fragment_live_${policy.name}")
                    nativeLayout = NativeLayout.Custom { context ->
                        // 真实 SDK 已交付，但业务工厂失败；不伪造 no_fill 或平台失败。
                        check(!rejectLayout) { "fragment_acceptance_layout_rejected" }
                        createDefaultNativeLayout(context)
                    }
                }
                val old = onMain {
                    activity.supportFragmentManager.beginTransaction()
                        .add(android.R.id.content, fragment).commitNow()
                    fragment.card
                }
                val oldStates = mutableListOf<NativeState>()
                onMain {
                    old.setOnStateChanged { oldStates += it }
                    old.setActive(true)
                }
                await("工厂失败后的明确终态", 90_000) { old.state is NativeState.Failed }
                onMain {
                    assertEquals(NativeState.Failed("native_layout_invalid"), old.state)
                    assertEquals(0, old.childCount)
                }
                SystemClock.sleep(500)
                onMain {
                    assertEquals("失败不自动重试", 1, oldStates.count { it == NativeState.Loading })
                    rejectLayout = false
                    old.retry()
                }
                awaitLoaded(old)
                onMain {
                    assertEquals(2, oldStates.count { it == NativeState.Loading })
                    repeat(3) { old.retry() }
                }
                SystemClock.sleep(500)
                val originalView = onMain {
                    assertEquals("已加载 retry 不重复请求", 2, oldStates.count { it == NativeState.Loading })
                    old.getChildAt(0)
                }
                snapshot("fragment-live-${platform.analyticsValue}-${policy.name}-before")
                onMain {
                    activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
                    assertEquals(NativeState.Destroyed, old.state)
                    assertEquals(0, old.childCount)
                    activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
                    assertNotSame(old, fragment.card)
                }
                val replacement = onMain { fragment.card }
                val newStates = mutableListOf<NativeState>()
                onMain {
                    replacement.setOnStateChanged { newStates += it }
                    old.retry()
                    old.setActive(true)
                    replacement.setActive(true)
                }
                awaitLoaded(replacement)
                snapshot("fragment-live-${platform.analyticsValue}-${policy.name}-recreated")
                onMain {
                    assertEquals(NativeState.Destroyed, old.state)
                    assertEquals(2, oldStates.count { it == NativeState.Loading })
                    assertEquals(1, newStates.count { it == NativeState.Loading })
                    assertNotSame(originalView, replacement.getChildAt(0))
                    activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
                    assertEquals(NativeState.Destroyed, replacement.state)
                    assertEquals(0, replacement.childCount)
                }
                Log.i("NativeFragmentLive", "platform=${platform.analyticsValue} policy=$policy factoryFailure=1 explicitRetry=1 " +
                    "oldLoads=2 recreatedLoads=1 oldOwnerDestroyed=true finalCleanup=true")
            }
        } finally {
            onMain { activity.finish() }
            await("验收 Activity 销毁") { activity.isDestroyed }
        }
    }

    private fun awaitLoaded(card: AdsNativeView) = await("真实 SDK 广告绑定", 90_000) {
        assertFalse("SDK 或绑定失败：${card.state}", card.state is NativeState.Failed)
        card.state == NativeState.Loaded && card.childCount == 1 && card.isShown
    }

    private fun snapshot(label: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "$label.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun await(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("超时：$label")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun verifyFragmentViewRecreation(policy: NativeRetentionPolicy) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, NativeFragmentTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as NativeFragmentTestActivity
        try {
            instrumentation.runOnMainSync {
                val fragment = NativeTestFragment().apply { retentionPolicy = policy }
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment).commitNow()
                val oldCard = fragment.card
                assertEquals(NativeState.Idle, oldCard.state)
                activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
                assertEquals(NativeState.Destroyed, oldCard.state)
                activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
                assertNotSame(oldCard, fragment.card)
                assertEquals(NativeState.Idle, fragment.card.state)
                oldCard.retry()
                oldCard.setActive(true)
                assertEquals(NativeState.Destroyed, oldCard.state)
                activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
                assertEquals(NativeState.Destroyed, fragment.card.state)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}

class NativeFragmentTestActivity : FragmentActivity()

class NativeTestFragment : Fragment() {
    var retentionPolicy = NativeRetentionPolicy.DESTROY_ON_HIDE
    var adRequest = NativeRequest("fragment_test")
    var nativeLayout: NativeLayout = NativeLayout.Default
    lateinit var card: AdsNativeView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        FrameLayout(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        card = AdsNativeView(requireActivity(), viewLifecycleOwner,
            adRequest, layout = nativeLayout, active = false, retentionPolicy = retentionPolicy)
        (view as ViewGroup).addView(card)
    }
}
