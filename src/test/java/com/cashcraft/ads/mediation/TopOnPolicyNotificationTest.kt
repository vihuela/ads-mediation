package com.cashcraft.ads.mediation

import android.app.Activity
import android.os.Looper
import com.cashcraft.ads.mediation.internal.AdPolicyChecker
import com.cashcraft.ads.mediation.internal.AdUsageStore
import com.cashcraft.ads.mediation.internal.ShadowMMKV
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnFullScreenLoadControl
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [ShadowMMKV::class])
class TopOnPolicyNotificationTest {
    @Test fun `TopOn readiness query failure does not stop other readiness listeners`() = withFailingQuery { calls ->
        Ads.notifyNativeReadiness()
        assertEquals(listOf("topon", "listener-one", "listener-two"), calls)
    }

    @Test fun `TopOn readiness query failure does not drop initialization completion`() = withFailingQuery { calls ->
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationCallback", { success: Boolean ->
            assertTrue(success)
            calls += "initialized"
        })
        ReflectionHelpers.callInstanceMethod<Unit>(Ads, "finishInitialization",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertEquals(listOf("topon", "listener-one", "listener-two", "initialized"), calls)
    }

    @Test fun `TopOn readiness query failure does not drop privacy dismissal`() = withFailingQuery { calls ->
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            // Disabled UMP completes synchronously through the production privacy callback chain.
            Ads.showPrivacyOptions(activity.get()) { error ->
                assertNull(error)
                calls += "dismissed"
            }
            assertEquals(listOf("topon", "listener-one", "listener-two", "dismissed"), calls)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun withFailingQuery(check: (MutableList<String>) -> Unit) {
        val savedAds = listOf("config", "policyChecker", "umpConsentManager", "initializationCallback",
            "initializationStage", "initializationFailure")
            .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
        val started = ReflectionHelpers.getStaticField<AtomicBoolean>(Ads::class.java, "providerInitializationStarted")
        val wasStarted = started.getAndSet(true)
        val control = ReflectionHelpers.getStaticField<TopOnFullScreenLoadControl>(TopOnAds::class.java, "fullScreenLoadControl")
        val savedAllowed = ReflectionHelpers.getField<Boolean>(control, "loadAllowed")
        val savedState = TopOnAds.state
        val calls = mutableListOf<String>()
        val observers = listOf(
            Ads.observeNativeReadiness { calls += "listener-one" },
            Ads.observeNativeReadiness { calls += "listener-two" },
        )
        var failQuery = false
        try {
            ShadowMMKV.reset()
            val app = RuntimeEnvironment.getApplication()
            ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(AdMobProviderConfig(AdMobIds.TEST)))
            ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", AdPolicyChecker(AdUsageStore(app, 0L)))
            ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
                UmpConsentManager(app, UmpConsentConfig(enabled = false), false, "test"))
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.READY)
            control.request("throwing-query", isReady = {
                if (failQuery) {
                    calls += "topon"
                    error("SDK readiness query failed")
                }
                false
            }, isLoading = { false }, load = { false })
            failQuery = true
            check(calls)
        } finally {
            observers.forEach { it.close() }
            ReflectionHelpers.getField<MutableMap<String, Any>>(control, "rounds").remove("throwing-query")
            ReflectionHelpers.setField(control, "loadAllowed", savedAllowed)
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", savedState)
            started.set(wasStarted)
            savedAds.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
        }
    }

    @Test fun `policy notifications are coalesced once and consent readiness still restores loads`() {
        val savedAds = listOf("config", "policyChecker", "umpConsentManager")
            .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
        val control = ReflectionHelpers.getStaticField<TopOnFullScreenLoadControl>(TopOnAds::class.java, "fullScreenLoadControl")
        val savedAllowed = ReflectionHelpers.getField<Boolean>(control, "loadAllowed")
        val savedState = TopOnAds.state
        val app = RuntimeEnvironment.getApplication()
        var attempts = 0
        try {
            ShadowMMKV.reset()
            ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(AdMobProviderConfig(AdMobIds.TEST)))
            ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", AdPolicyChecker(AdUsageStore(app, 0L)))
            ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
                UmpConsentManager(app, UmpConsentConfig(enabled = false), false, "test"))
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.READY)
            // A declined SDK handoff leaves the round eligible for each notification.
            control.request("probe", isReady = { false }, isLoading = { false }, load = { attempts++; false })
            attempts = 0

            repeat(3) { Ads.updatePolicy(AdPolicy()) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, attempts)

            Ads.updatePolicy(AdPolicy(enabled = false))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, attempts)
            Ads.updatePolicy(AdPolicy())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, attempts)

            ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
                UmpConsentManager(app, UmpConsentConfig(enabled = true), false, "test"))
            Ads.notifyNativeReadiness()
            assertEquals(2, attempts)
            ReflectionHelpers.setStaticField(Ads::class.java, "umpConsentManager",
                UmpConsentManager(app, UmpConsentConfig(enabled = false), false, "test"))
            Ads.notifyNativeReadiness()
            assertEquals(3, attempts)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            ReflectionHelpers.getField<MutableMap<String, Any>>(control, "rounds").remove("probe")
            ReflectionHelpers.setField(control, "loadAllowed", savedAllowed)
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", savedState)
            savedAds.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
        }
    }
}
