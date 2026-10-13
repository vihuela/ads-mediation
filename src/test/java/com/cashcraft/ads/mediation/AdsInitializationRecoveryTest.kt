package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import com.google.android.ump.FormError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import java.util.concurrent.atomic.AtomicBoolean
import org.robolectric.Robolectric
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], manifest = Config.NONE,
    shadows = [ConsentPlatformShadow::class, InitializationMobileAdsShadow::class,
        InitializationInterstitialShadow::class],
    instrumentedPackages = ["com.google.android.libraries.ads.mobile.sdk.MobileAds\$Companion",
        "com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader\$Companion"],
)
class AdsInitializationRecoveryTest {
    private val initialStage = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "initializationStage")
    private val initialAdMobState = AdMobAds.state

    @Before
    @After
    fun clearPendingInitialization() {
        // Consent and GMA initialization are intercepted; no real advertising SDK starts.
        ConsentPlatformShadow.reset()
        InitializationMobileAdsShadow.calls = 0
        InitializationMobileAdsShadow.lastConfig = null
        InitializationMobileAdsShadow.error = null
        listOf("initializationFailure", "admobInitializationResult", "topOnInitializationResult").forEach {
            ReflectionHelpers.setStaticField(Ads::class.java, it, null)
        }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationAttempt", 0L)
        ReflectionHelpers.setStaticField(Ads::class.java, "retryingProviders", false)
        ReflectionHelpers.setStaticField(Ads::class.java, "lastNotifiedInitializationState", AdsState.NOT_INITIALIZED)
        ReflectionHelpers.setStaticField(Ads::class.java, "notifyingInitializationState", false)
        ReflectionHelpers.getStaticField<java.util.ArrayDeque<*>>(Ads::class.java, "pendingInitializationStates").clear()
        ReflectionHelpers.getStaticField<MutableCollection<*>>(Ads::class.java, "initializationStateListeners").clear()
        ReflectionHelpers.getStaticField<AtomicBoolean>(Ads::class.java, "providerInitializationStarted").set(false)
        ReflectionHelpers.getStaticField<AtomicBoolean>(AdMobAds::class.java, "mobileAdsInitializationStarted").set(false)
        ReflectionHelpers.getStaticField<MutableCollection<*>>(AdMobAds::class.java, "initializationListeners").clear()
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "initializationError", null)
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.NOT_INITIALIZED)
        val listener = ReflectionHelpers.getStaticField<AdLifecycleMonitor.Listener>(Ads::class.java, "lifecycleListener")
        AdLifecycleMonitor.removeListener(listener)
        val controller = ReflectionHelpers.getStaticField<Any?>(Ads::class.java, "autoSelectionAppOpenController")
        controller?.let {
            AdLifecycleMonitor.removeListener(ReflectionHelpers.getField(it, "lifecycleListener"))
        }
        // AdMob lifecycle observation belongs to its controller, not to AdMobAds itself.
        ReflectionHelpers.getStaticField<Any?>(AdMobAds::class.java, "autoAppOpenController")?.let {
            AdLifecycleMonitor.removeListener(ReflectionHelpers.getField(it, "lifecycleListener"))
        }
        val callbacks = ReflectionHelpers.getStaticField<Application.ActivityLifecycleCallbacks>(
            AdLifecycleMonitor::class.java, "callbacks",
        )
        val installedApplication = ReflectionHelpers.getStaticField<Application?>(
            AdLifecycleMonitor::class.java, "installedApplication",
        )
        installedApplication?.unregisterActivityLifecycleCallbacks(callbacks)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "installedApplication", null)
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "resumedActivity", WeakReference<Activity>(null))
        ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", false)
        ReflectionHelpers.getStaticField<MutableSet<*>>(AdLifecycleMonitor::class.java, "startedActivities").clear()
        ReflectionHelpers.getStaticField<MutableCollection<*>>(Ads::class.java, "initializationListeners").clear()
        ReflectionHelpers.setStaticField(Ads::class.java, "activePlatform", null)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", initialAdMobState)
        listOf("application", "config", "facadeEvents", "umpConsentManager", "autoSelectionAppOpenController")
            .forEach { ReflectionHelpers.setStaticField(Ads::class.java, it, null) }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage", initialStage)
    }

    @Test
    fun `late initialization binds resumed host and starts consent without another resume`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        try {
            Ads.initialize(host.get(), AdsConfig(AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false))
            org.junit.Assert.assertSame(host.get(), AdLifecycleMonitor.currentActivity)
            assertTrue(AdLifecycleMonitor.isAppInForeground)
            assertEquals(listOf(host.get()), ConsentPlatformShadow.requests)
            assertEquals(AdsState.INITIALIZING, Ads.state)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `equal Activity initialization seeds an existing Application initialization once`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        val config = initializePending()
        val results = mutableListOf<Boolean>()
        try {
            assertNull(AdLifecycleMonitor.currentActivity)
            Ads.initialize(host.get(), config.copy(), results::add)
            val manager = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "umpConsentManager")
            Ads.initialize(host.get(), config.copy())
            org.junit.Assert.assertSame(manager, ReflectionHelpers.getStaticField<Any>(Ads::class.java, "umpConsentManager"))
            assertEquals(listOf(host.get()), ConsentPlatformShadow.requests)
            org.junit.Assert.assertSame(host.get(), AdLifecycleMonitor.currentActivity)
            assertThrows(IllegalArgumentException::class.java) {
                Ads.initialize(host.get(), config.copy(loggingEnabled = true))
            }
            assertEquals(listOf(host.get()), ConsentPlatformShadow.requests)
            finishInitialization(false)
            assertEquals(listOf(false), results)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `unresumed destroyed finishing and unobserved plain hosts are rejected before initialization`() {
        val config = AdsConfig(AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false)
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        try {
            for (state in listOf(Lifecycle.State.CREATED, Lifecycle.State.STARTED)) {
                host.get().registry.currentState = state
                assertThrows(IllegalArgumentException::class.java) { Ads.initialize(host.get(), config) }
            }
            host.get().registry.currentState = Lifecycle.State.RESUMED
            host.get().finish()
            assertThrows(IllegalArgumentException::class.java) { Ads.initialize(host.get(), config) }
        } finally { host.pause().stop().destroy() }
        assertThrows(IllegalArgumentException::class.java) { Ads.initialize(host.get(), config) }
        val plain = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertThrows(IllegalArgumentException::class.java) { Ads.initialize(plain.get(), config) }
        } finally { plain.pause().stop().destroy() }
        assertEquals(AdsState.NOT_INITIALIZED, Ads.state)
        assertTrue(ConsentPlatformShadow.requests.isEmpty())
        assertNull(AdLifecycleMonitor.currentActivity)
    }

    @Test
    fun `observed resumed plain host is accepted`() {
        AdLifecycleMonitor.install(RuntimeEnvironment.getApplication())
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            Ads.initialize(host.get(), AdsConfig(AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false))
            assertEquals(listOf(host.get()), ConsentPlatformShadow.requests)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `Activity overload rejects another Application without rebinding the monitor`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        val config = initializePending()
        val originalApplication = host.get().application
        ReflectionHelpers.setField(host.get(), "mApplication", Application())
        try {
            assertThrows(IllegalArgumentException::class.java) { Ads.initialize(host.get(), config) }
            assertNull(AdLifecycleMonitor.currentActivity)
            assertTrue(ConsentPlatformShadow.requests.isEmpty())
        } finally {
            ReflectionHelpers.setField(host.get(), "mApplication", originalApplication)
            host.pause().stop().destroy()
        }
    }

    @Test
    fun `Activity initialization outside main thread is rejected before binding`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        val config = AdsConfig(AdMobProviderConfig(AdMobIds.TEST), loggingEnabled = false)
        var error: Throwable? = null
        try {
            Thread { error = runCatching { Ads.initialize(host.get(), config) }.exceptionOrNull() }
                .apply { start(); join() }
            assertTrue(error is IllegalStateException)
            assertEquals(AdsState.NOT_INITIALIZED, Ads.state)
            assertNull(AdLifecycleMonitor.currentActivity)
            assertTrue(ConsentPlatformShadow.requests.isEmpty())
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `network consent failure recovers once and persistent listener keeps Splash observer`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        val states = mutableListOf<AdsState>()
        val splash = mutableListOf<Boolean>()
        val cancel = Ads.addInitializationStateListener(states::add)
        try {
            Ads.initialize(host.get(), recoveryConfig(), splash::add)
            val oldFailure = ConsentPlatformShadow.failures.single()
            oldFailure.onConsentInfoUpdateFailure(FormError(FormError.ErrorCode.INTERNET_ERROR, "offline"))
            assertEquals(AdsState.FAILED, Ads.state)
            assertTrue(Ads.canRetryInitialization)
            assertEquals(listOf(false), splash)
            Ads.retryInitialization(host.get())
            repeat(5) { Ads.retryInitialization(host.get()) }
            assertEquals(2, ConsentPlatformShadow.requests.size)
            oldFailure.onConsentInfoUpdateFailure(FormError(FormError.ErrorCode.INVALID_OPERATION, "old callback"))
            assertEquals(AdsState.INITIALIZING, Ads.state)
            val recoverySplash = mutableListOf<Boolean>()
            Ads.initialize(host.get(), recoveryConfig(), recoverySplash::add)
            ConsentPlatformShadow.allowed = true
            ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
            settleProvider()
            assertEquals(AdsState.READY, Ads.state)
            assertEquals(listOf(true), recoverySplash)
            assertEquals(listOf(AdsState.NOT_INITIALIZED, AdsState.INITIALIZING, AdsState.FAILED,
                AdsState.INITIALIZING, AdsState.READY), states)
            assertEquals(1, InitializationMobileAdsShadow.calls)
            repeat(5) { Ads.retryInitialization() }
            assertEquals(1, InitializationMobileAdsShadow.calls)
            assertFalse(Ads.canRetryInitialization)
            cancel()
            assertEquals(0, ConsentPlatformShadow.resets)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `null retry waits for resumed host and cancelled persistent listener leaves observer intact`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        try {
            Ads.initialize(host.get(), recoveryConfig())
            ConsentPlatformShadow.failures.last().onConsentInfoUpdateFailure(FormError(1, "temporary internal error"))
            host.pause()
            host.get().registry.currentState = Lifecycle.State.STARTED
            Ads.retryInitialization()
            assertEquals(1, ConsentPlatformShadow.requests.size)
            assertEquals(AdsState.INITIALIZING, Ads.state)
            val states = mutableListOf<AdsState>()
            val cancel = Ads.addInitializationStateListener(states::add)
            val once = mutableListOf<Boolean>()
            Ads.initialize(host.get().application, recoveryConfig(), once::add)
            cancel()
            host.resume()
            host.get().registry.currentState = Lifecycle.State.RESUMED
            assertEquals(2, ConsentPlatformShadow.requests.size)
            ConsentPlatformShadow.failures.last().onConsentInfoUpdateFailure(FormError(3, "bad configuration"))
            assertEquals(listOf(false), once)
            assertEquals(listOf(AdsState.INITIALIZING), states)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `invalid operation unknown error and successful consent denial are terminal`() {
        for (code in listOf(3, 99, null)) {
            clearPendingInitialization()
            val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
            host.get().registry.currentState = Lifecycle.State.RESUMED
            try {
                Ads.initialize(host.get(), recoveryConfig())
                if (code != null) ConsentPlatformShadow.failures.last()
                    .onConsentInfoUpdateFailure(FormError(code, "not transient"))
                else ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
                assertEquals(AdsState.FAILED, Ads.state)
                assertFalse(Ads.canRetryInitialization)
                Ads.retryInitialization(host.get())
                assertEquals(1, ConsentPlatformShadow.requests.size)
                assertEquals(0, InitializationMobileAdsShadow.calls)
                assertEquals(0, ConsentPlatformShadow.resets)
            } finally { host.pause().stop().destroy() }
        }
    }

    @Test
    fun `GMA IOException retries without refreshing consent and SDK configuration exceptions do not`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        try {
            InitializationMobileAdsShadow.error = java.io.IOException("offline")
            Ads.initialize(host.get(), recoveryConfig())
            ConsentPlatformShadow.allowed = true
            ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
            settleProvider()
            assertEquals(AdsState.FAILED, Ads.state)
            assertTrue(Ads.canRetryInitialization)
            InitializationMobileAdsShadow.error = IllegalArgumentException("invalid app ID")
            Ads.retryInitialization(host.get())
            repeat(5) { Ads.retryInitialization(host.get()) }
            settleProvider()
            assertEquals(2, InitializationMobileAdsShadow.calls)
            assertEquals(1, ConsentPlatformShadow.requests.size)
            assertFalse(Ads.canRetryInitialization)
            Ads.retryInitialization()
            assertEquals(2, InitializationMobileAdsShadow.calls)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `GMA transient failure recovers through SDK initialize using installed configuration`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        try {
            InitializationMobileAdsShadow.error = java.io.IOException("offline")
            Ads.initialize(host.get(), recoveryConfig())
            ConsentPlatformShadow.allowed = true
            ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
            settleProvider()
            assertTrue(Ads.canRetryInitialization)
            InitializationMobileAdsShadow.error = null
            Ads.retryInitialization(host.get())
            repeat(5) { Ads.retryInitialization(host.get()) }
            settleProvider()
            assertEquals(AdsState.READY, Ads.state)
            assertEquals(2, InitializationMobileAdsShadow.calls)
            assertTrue(InitializationMobileAdsShadow.lastConfig!!.nativeAdDebuggerDisabled)
            assertEquals(1, ConsentPlatformShadow.requests.size)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `retry before installation and during initial consent is a no op`() {
        Ads.retryInitialization()
        assertEquals(AdsState.NOT_INITIALIZED, Ads.state)
        initializePending()
        Ads.retryInitialization()
        assertEquals(AdsState.INITIALIZING, Ads.state)
        assertEquals(0, InitializationMobileAdsShadow.calls)
    }

    @Test
    fun `retry and persistent registration require main thread`() {
        var retryError: Throwable? = null
        var listenerError: Throwable? = null
        Thread {
            retryError = runCatching { Ads.retryInitialization() }.exceptionOrNull()
            listenerError = runCatching { Ads.addInitializationStateListener {} }.exceptionOrNull()
        }.apply { start(); join() }
        assertTrue(retryError is IllegalStateException)
        assertTrue(listenerError is IllegalStateException)
    }

    @Test
    fun `already successful bidder remains ready when other platform fails`() {
        val cfg = recoveryConfig().copy(provider = BiddingProviderConfig(
            recoveryConfig().provider as AdMobProviderConfig, topOn()))
        Ads.initialize(RuntimeEnvironment.getApplication(), cfg)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.FAILED)
        ReflectionHelpers.callInstanceMethod<Unit>(Ads, "finishBiddingProviderInitialization",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        ReflectionHelpers.callInstanceMethod<Unit>(Ads, "finishBiddingProviderInitialization",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertEquals(AdsState.READY, Ads.state)
        Ads.retryInitialization()
        assertEquals(AdMobState.READY, AdMobAds.state)
        assertEquals(0, InitializationMobileAdsShadow.calls)
    }

    @Test
    fun `form timeout recovers via required form API without resetting consent`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        try {
            Ads.initialize(host.get(), recoveryConfig())
            ConsentPlatformShadow.formError = FormError(4, "timeout")
            ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
            assertTrue(Ads.canRetryInitialization)
            Ads.retryInitialization(host.get())
            ConsentPlatformShadow.formError = null
            ConsentPlatformShadow.allowed = true
            ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
            settleProvider()
            assertEquals(AdsState.READY, Ads.state)
            assertEquals(2, ConsentPlatformShadow.forms)
            assertEquals(0, ConsentPlatformShadow.resets)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `previous consent still permits SDK initialization after UMP request error`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        try {
            Ads.initialize(host.get(), recoveryConfig())
            ConsentPlatformShadow.allowed = true
            ConsentPlatformShadow.failures.last().onConsentInfoUpdateFailure(FormError(2, "offline"))
            settleProvider()
            assertEquals(AdsState.READY, Ads.state)
            assertFalse(Ads.canRetryInitialization)
            assertEquals(0, ConsentPlatformShadow.forms)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `listener triggered retry delivers transitions in order and cannot steal terminal observer`() {
        val host = Robolectric.buildActivity(ConsentHost::class.java).setup()
        host.get().registry.currentState = Lifecycle.State.RESUMED
        val oldObserver = mutableListOf<Boolean>()
        val newObserver = mutableListOf<Boolean>()
        val states = mutableListOf<AdsState>()
        try {
            Ads.addInitializationStateListener {
                if (it == AdsState.FAILED) {
                    Ads.retryInitialization(host.get())
                    Ads.initialize(host.get(), recoveryConfig(), newObserver::add)
                }
            }
            Ads.addInitializationStateListener(states::add)
            Ads.initialize(host.get(), recoveryConfig(), oldObserver::add)
            ConsentPlatformShadow.failures.last().onConsentInfoUpdateFailure(FormError(2, "offline"))
            assertEquals(listOf(false), oldObserver)
            assertTrue(newObserver.isEmpty())
            assertEquals(listOf(AdsState.NOT_INITIALIZED, AdsState.INITIALIZING,
                AdsState.FAILED, AdsState.INITIALIZING), states)
            ConsentPlatformShadow.allowed = true
            ConsentPlatformShadow.successes.last().onConsentInfoUpdateSuccess()
            settleProvider()
            assertEquals(listOf(true), newObserver)
        } finally { host.pause().stop().destroy() }
    }

    @Test
    fun `TopOn failure remains terminal without a documented retry contract`() {
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.FAILED)
        // A stale failure in another platform must not make this configuration retryable.
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.FAILED)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "initializationError", java.io.IOException("unrelated"))
        Ads.initialize(RuntimeEnvironment.getApplication(), AdsConfig(topOn(),
            umpConsent = UmpConsentConfig(enabled = false), loggingEnabled = false))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AdsState.FAILED, Ads.state)
        assertFalse(Ads.canRetryInitialization)
        Ads.retryInitialization()
        assertEquals(AdsState.FAILED, Ads.state)
        assertEquals(0, InitializationMobileAdsShadow.calls)
    }

    @Test
    fun `disabled UMP provider retry works without a foreground host`() {
        InitializationMobileAdsShadow.error = java.io.IOException("offline")
        Ads.initialize(RuntimeEnvironment.getApplication(),
            recoveryConfig().copy(umpConsent = UmpConsentConfig(enabled = false)))
        settleProvider()
        assertTrue(Ads.canRetryInitialization)
        InitializationMobileAdsShadow.error = null
        Ads.retryInitialization()
        settleProvider()
        assertEquals(AdsState.READY, Ads.state)
        assertEquals(2, InitializationMobileAdsShadow.calls)
        assertTrue(ConsentPlatformShadow.requests.isEmpty())
    }

    private fun recoveryConfig() = AdsConfig(
        AdMobProviderConfig(AdMobIds(AdMobIds.TEST.applicationId, interstitialId = AdMobIds.TEST.interstitialId)),
        autoShowAppOpen = false, loggingEnabled = false)

    private fun settleProvider() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (Ads.state == AdsState.INITIALIZING && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("SDK initialization did not complete", Ads.state == AdsState.INITIALIZING)
    }

    private fun initializePending(): AdsConfig {
        val config = recoveryConfig()
        Ads.initialize(RuntimeEnvironment.getApplication(), config)
        return config
    }

    private fun finishInitialization(success: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            Ads, "finishInitialization",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, success),
        )
    }

    private fun topOn() = TopOnProviderConfig(
        TopOnIds("app-id", "app-key", "app-open", "interstitial", "rewarded"),
    )
}

class ConsentHost : Activity(), LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

@Implements(UserMessagingPlatform::class)
class ConsentPlatformShadow {
    companion object {
        val requests = mutableListOf<Activity>()
        val successes = mutableListOf<ConsentInformation.OnConsentInfoUpdateSuccessListener>()
        val failures = mutableListOf<ConsentInformation.OnConsentInfoUpdateFailureListener>()
        var allowed = false
        var formError: FormError? = null
        var forms = 0
        var resets = 0
        fun reset() {
            requests.clear(); successes.clear(); failures.clear()
            allowed = false; formError = null; forms = 0; resets = 0
        }

        @JvmStatic
        @Implementation
        fun loadAndShowConsentFormIfRequired(activity: Activity,
            listener: com.google.android.ump.ConsentForm.OnConsentFormDismissedListener) {
            forms++
            listener.onConsentFormDismissed(formError)
        }

        @JvmStatic
        @Implementation
        fun getConsentInformation(context: android.content.Context): ConsentInformation =
            Proxy.newProxyInstance(ConsentInformation::class.java.classLoader, arrayOf(ConsentInformation::class.java)) { _, method, args ->
                when (method.name) {
                    "requestConsentInfoUpdate" -> {
                        requests += args!![0] as Activity
                        successes += args[2] as ConsentInformation.OnConsentInfoUpdateSuccessListener
                        failures += args[3] as ConsentInformation.OnConsentInfoUpdateFailureListener
                        null
                    }
                    "canRequestAds" -> allowed
                    "isConsentFormAvailable" -> false
                    "reset" -> { resets++; null }
                    "getConsentStatus" -> ConsentInformation.ConsentStatus.UNKNOWN
                    "getPrivacyOptionsRequirementStatus" -> ConsentInformation.PrivacyOptionsRequirementStatus.UNKNOWN
                    else -> null
                }
            } as ConsentInformation
    }
}

// Kotlin SDK calls resolve through Companion, as in the existing preload SDK shadows.
@Implements(
    className = "com.google.android.libraries.ads.mobile.sdk.MobileAds\$Companion",
    isInAndroidSdk = false,
)
class InitializationMobileAdsShadow {
    @Implementation
    fun initialize(context: android.content.Context, config: InitializationConfig) {
        calls++
        lastConfig = config
        error?.let { throw it }
    }

    companion object {
        @Volatile var calls = 0
        @Volatile var lastConfig: InitializationConfig? = null
        @Volatile var error: Throwable? = null
    }
}

@Implements(className = "com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader\$Companion", isInAndroidSdk = false)
class InitializationInterstitialShadow {
    @Implementation
    fun start(id: String,
        configuration: com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration,
        callback: com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback): Boolean = true
    @Implementation
    fun isAdAvailable(id: String): Boolean = false
}
