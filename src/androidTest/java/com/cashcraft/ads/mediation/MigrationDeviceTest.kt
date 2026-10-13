package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cashcraft.ads.mediation.internal.*
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.FormError
import com.thinkup.core.api.TUAdConst
import com.thinkup.core.api.TUAdInfo
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only regression probes. Injected consent failures/revenue are not live network results. */
@RunWith(AndroidJUnit4::class)
class MigrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var complete = false
            main { complete = condition() }
            if (complete) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $label")
    }
    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun launch(): DeviceHostActivity = instrumentation.startActivitySync(
        Intent(context, DeviceHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
    ) as DeviceHostActivity
    private fun finish(vararg hosts: DeviceHostActivity) {
        main { hosts.forEach { it.finish() } }
        await("host destroyed") { hosts.all { it.isDestroyed } }
    }
    private fun resetMonitor() = main {
        val type = AdLifecycleMonitor::class.java
        val app = field(type, "installedApplication").get(null) as Application?
        app?.unregisterActivityLifecycleCallbacks(field(type, "callbacks").get(null) as Application.ActivityLifecycleCallbacks)
        field(type, "installedApplication").set(null, null)
        field(type, "resumedActivity").set(null, WeakReference<Activity>(null))
        field(type, "isAppInForeground").set(null, false)
        (field(type, "startedActivities").get(null) as MutableSet<*>).clear()
    }

    @Test fun activityInstancesAndSharedLock() {
        resetMonitor()
        val old = launch()
        // Install while a second real Activity is being created, after the old one's onStart.
        DeviceHostActivity.beforeCreate = { AdLifecycleMonitor.install(it.application) }
        val host = launch()
        val admobOwner = Any()
        val topOnOwner = Any()
        try {
            await("old Activity stopped, current Activity focused") { old.stopped && host.hasWindowFocus() }
            main {
                assertTrue(AdLifecycleMonitor.isAppInForeground)
                assertSame(host, AdLifecycleMonitor.currentActivity)
                assertNull(FullScreenShowGate.tryAcquire(host, null, admobOwner))
                assertEquals("another_full_screen_ad_showing", FullScreenShowGate.tryAcquire(host, null, topOnOwner))
                FullScreenShowGate.release(topOnOwner)
                assertTrue(FullScreenShowGate.isAnyAdShowing)
                FullScreenShowGate.release(admobOwner)
                assertNull(FullScreenShowGate.tryAcquire(host, null, topOnOwner))
                FullScreenShowGate.release(admobOwner) // Delayed completion of the previous show.
                assertTrue(FullScreenShowGate.isAnyAdShowing)
                FullScreenShowGate.release(topOnOwner)
                assertFalse(FullScreenShowGate.isAnyAdShowing)
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            await("background") { !AdLifecycleMonitor.isAppInForeground }
            context.startActivity(Intent(context, DeviceHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("foreground restored") { AdLifecycleMonitor.currentActivity === host && host.hasWindowFocus() }
            main { assertNull(AdLifecycleMonitor.activityShowFailureReason(host)) }
            Log.i("AdsDeviceProbe", "PASS Activity instance accounting, background/foreground, shared lock, stale owner release")
        } finally {
            main { FullScreenShowGate.release(admobOwner); FullScreenShowGate.release(topOnOwner) }
            finish(host, old)
            DeviceHostActivity.beforeCreate = null
        }
    }

    @Test fun consentFailureRetriesAndInitializesRealAdMob() {
        resetMonitor()
        val failures = mutableListOf<ConsentInformation.OnConsentInfoUpdateFailureListener>()
        var allowed = false
        val consent = Proxy.newProxyInstance(ConsentInformation::class.java.classLoader,
            arrayOf(ConsentInformation::class.java)) { _, method, args ->
            when (method.name) {
                "requestConsentInfoUpdate" -> { failures += args!![3] as ConsentInformation.OnConsentInfoUpdateFailureListener; null }
                "canRequestAds" -> allowed
                "getConsentStatus" -> ConsentInformation.ConsentStatus.UNKNOWN
                "getPrivacyOptionsRequirementStatus" -> ConsentInformation.PrivacyOptionsRequirementStatus.UNKNOWN
                "isConsentFormAvailable" -> false
                "reset" -> error("Retry must preserve persisted consent")
                else -> null
            }
        } as ConsentInformation
        val states = mutableListOf<AdsState>()
        val firstObserver = mutableListOf<Boolean>()
        val recoveryObserver = mutableListOf<Boolean>()
        var unsubscribe: (() -> Unit)? = null
        val config = AdsConfig(AdMobProviderConfig(AdMobIds(AdMobIds.TEST.applicationId,
            interstitialId = AdMobIds.TEST.interstitialId)), autoShowAppOpen = false)
        main {
            assertEquals(AdsState.NOT_INITIALIZED, Ads.state)
            unsubscribe = Ads.addInitializationStateListener(states::add)
            Ads.initialize(context.applicationContext as Application, config, firstObserver::add)
            val manager = field(Ads::class.java, "umpConsentManager").get(null)
            field(UmpConsentManager::class.java, "consentInformation").set(manager, consent)
        }
        val host = launch()
        try {
            await("consent request") { failures.size == 1 }
            main {
                val oldFailure = failures.single()
                oldFailure.onConsentInfoUpdateFailure(FormError(FormError.ErrorCode.INTERNET_ERROR, "device injected offline"))
                assertEquals(AdsState.FAILED, Ads.state)
                assertTrue(Ads.canRetryInitialization)
                assertEquals(listOf(false), firstObserver)
                Ads.retryInitialization(host)
                repeat(5) { Ads.retryInitialization(host) }
                assertEquals(2, failures.size)
                oldFailure.onConsentInfoUpdateFailure(FormError(FormError.ErrorCode.INVALID_OPERATION, "stale failure"))
                assertEquals(AdsState.INITIALIZING, Ads.state)
                Ads.initialize(host, config, recoveryObserver::add)
                // Model an already-consented user after reconnect; real GMA initializes next.
                allowed = true
                failures.last().onConsentInfoUpdateFailure(FormError(FormError.ErrorCode.INTERNET_ERROR, "consent cache allowed"))
            }
            await("real GMA initialized") { Ads.state != AdsState.INITIALIZING }
            main {
                assertEquals(AdsState.READY, Ads.state)
                assertEquals(listOf(true), recoveryObserver)
                assertEquals(listOf(AdsState.NOT_INITIALIZED, AdsState.INITIALIZING, AdsState.FAILED,
                    AdsState.INITIALIZING, AdsState.READY), states)
                assertFalse(Ads.canRetryInitialization)
                repeat(5) { Ads.retryInitialization(host) }
                assertEquals(2, failures.size)
            }
            Log.i("AdsDeviceProbe", "PASS injected UMP failure -> retry -> real AdMob READY; duplicate retries and stale callback ignored")
        } finally {
            main { unsubscribe?.invoke() }
            finish(host)
        }
    }

    @Test fun topOnSdkUsdAndInvalidRevenue() = main {
        val configField = field(TopOnAds::class.java, "commonConfig")
        val original = configField.get(null)
        val events = mutableListOf<AdEvent>()
        val payloads = mutableListOf<AdRevenuePayload>()
        configField.set(null, AdsConfig(TopOnProviderConfig(TopOnIds("probe", "probe", interstitialPlacementId = "probe")),
            revenueListener = AdRevenueListener(payloads::add), loggingEnabled = false))
        val type = Class.forName("com.thinkup.core.common.o.dq")
        fun info(value: Double?, currency: String) = (type.getDeclaredConstructor().newInstance() as TUAdInfo).also {
            field(type, "md").set(it, value)
            field(type, "mn").set(it, currency)
        }
        try {
            for (format in AdFormat.entries) {
                for ((value, currency) in listOf(1.25 to "USD", 1.25 to "CNY", 0.0 to "USD",
                    null to "USD", null to "CNY", -1.0 to "USD", Double.NaN to "USD",
                    Double.POSITIVE_INFINITY to "USD", Double.MAX_VALUE to "USD")) {
                    events.clear(); payloads.clear()
                    val adInfo = info(value, currency)
                    val sdkUsd = runCatching { adInfo.getPublisherRevenue(TUAdConst.CURRENCY.USD) }.getOrNull()
                    val expectedMicros = sdkUsd?.takeIf { it.isFinite() && it >= 0 }?.let {
                        runCatching { BigDecimal.valueOf(it).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                    }
                    val session = AdShowSession(AdEventListener(events::add), AdPlatform.TOPON, AdMediationMode.TOPON,
                        format, "device_probe", "test-unit", "probe-${format.name}", 1)
                    session.impression("probe", "probe-response")
                    TopOnAds.nativePaid(session, adInfo)
                    if (expectedMicros == null) {
                        assertNull(topOnRevenueAmount(adInfo))
                        assertTrue(payloads.isEmpty())
                        assertFalse(events.any { it.name == AdEventName.PAID })
                    } else {
                        val paid = events.single { it.name == AdEventName.PAID }
                        assertEquals(sdkUsd, paid.value)
                        assertEquals(expectedMicros, paid.valueMicros)
                        assertEquals("USD", paid.currency)
                        assertEquals(expectedMicros, payloads.single().valueMicros)
                        assertEquals("USD", payloads.single().currencyCode)
                        assertSame(adInfo, (payloads.single() as TopOnRevenuePayload).adInfo)
                    }
                    assertEquals(1, events.count { it.name == AdEventName.POSITION })
                    assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
                    assertFalse(events.any { it.name == AdEventName.SHOW_FAIL })
                }
            }
            Log.i("AdsDeviceProbe", "PASS real TUAdInfo USD API, all 4 formats, invalid -> null, genuine zero, paid/payload alignment")
        } finally { configField.set(null, original) }
    }
}

class DeviceHostActivity : Activity(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    var stopped = false
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        beforeCreate?.also { beforeCreate = null }?.invoke(this)
        registry.currentState = Lifecycle.State.CREATED
        setContentView(TextView(this).apply { text = "Ads SDK device regression" })
    }
    override fun onStart() { super.onStart(); stopped = false; registry.currentState = Lifecycle.State.STARTED }
    override fun onResume() { super.onResume(); registry.currentState = Lifecycle.State.RESUMED }
    override fun onPause() { registry.currentState = Lifecycle.State.STARTED; super.onPause() }
    override fun onStop() { stopped = true; registry.currentState = Lifecycle.State.CREATED; super.onStop() }
    override fun onDestroy() { registry.currentState = Lifecycle.State.DESTROYED; super.onDestroy() }
    companion object { @Volatile var beforeCreate: ((DeviceHostActivity) -> Unit)? = null }
}
