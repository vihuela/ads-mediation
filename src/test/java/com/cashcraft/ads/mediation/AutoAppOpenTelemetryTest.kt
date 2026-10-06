package com.cashcraft.ads.mediation

import android.app.Activity
import android.os.Looper
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.ShadowMMKV
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises the controller and callbacks installed by Ads.initialize, without starting either SDK. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], manifest = Config.NONE,
    shadows = [ConsentPlatformShadow::class, InitializationMobileAdsShadow::class, ShadowMMKV::class],
    instrumentedPackages = ["com.google.android.libraries.ads.mobile.sdk.MobileAds\$Companion"],
)
class AutoAppOpenTelemetryTest {
    // Reuse the initialization suite's public singleton/lifecycle isolation, including consent shadows.
    private val isolation = AdsInitializationTest()
    private val savedFields = listOf("policyChecker", "policyUpdatePosted", "nativeLogger")
        .associateWith { ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it) }
    private val initialTopOnState = TopOnAds.state
    private val host = Robolectric.buildActivity(Activity::class.java)
    private lateinit var automatic: AutoAppOpenController<FullScreenShowAttempt>
    private val events = mutableListOf<AdEvent>()
    private var onEvent: (AdEvent) -> Unit = {}
    private var backgrounded = false
    private var shows = 0
    private var inventoryReads = 0
    private val config = AdsConfig(
        provider = BiddingProviderConfig(
            AdMobProviderConfig(AdMobIds.TEST),
            TopOnProviderConfig(TopOnIds("test-app", "test-key", appOpenPlacementId = "test-open")),
        ),
        autoShowAppOpen = true, appOpenPosition = "auto-foreground", loggingEnabled = false,
        eventListener = AdEventListener { events += it; onEvent(it) },
    )

    @Before fun prepare() {
        isolation.clearPendingInitialization()
        ShadowMMKV.reset()
        // Leave UMP's success callback pending so real provider initialization never runs.
        Ads.initialize(RuntimeEnvironment.getApplication(), config)
        host.setup().visible().windowFocusChanged(true)
        ConsentPlatformShadow.allowed = true
        setStage("COMPLETE")
        ReflectionHelpers.getStaticField<AtomicBoolean>(Ads::class.java, "providerInitializationStarted").set(true)
        ReflectionHelpers.setStaticField(AdMobAds::class.java, "state", AdMobState.READY)
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.READY)
        // Policy completion must not trigger unrelated SDK preload work in this isolated fixture.
        ReflectionHelpers.setStaticField(Ads::class.java, "policyUpdatePosted", true)
        automatic = ReflectionHelpers.getStaticField(Ads::class.java, "autoBiddingAppOpenController")
        assertNull(AdLifecycleMonitor.activityShowFailureReason(host.get()))
        assertFalse(Ads.isReady(AdFormat.APP_OPEN))
        assertEquals(0, InitializationMobileAdsShadow.calls)
    }

    @After fun restore() {
        onEvent = {}
        if (::automatic.isInitialized) automatic.finishOpportunity("test_cleanup")
        if (!backgrounded) host.pause().stop()
        host.destroy()
        isolation.clearPendingInitialization()
        savedFields.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", initialTopOnState)
    }

    @Test fun `empty inventory announces once before timeout and failure retains the qualified session`() {
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
        val position = events.single()
        assertEquals("auto-foreground", position.position)
        assertEquals(AdFormat.APP_OPEN, position.format)
        assertEquals(position.sessionId, attempt.positionSessionId)
        assertSame(attempt, ReflectionHelpers.getStaticField<Any?>(FullScreenShowGate::class.java, "owner"))
        advance(500)
        assertEquals(1, events.size) // Repeated qualification checks cannot publish another POSITION.
        advance(7_001)
        assertCorrelatedFailure(attempt, "no_preloaded_ad")
        automatic.onAdAvailable()
        advance(1_000)
        assertEquals(2, events.size)
        assertGateReleased()
    }

    @Test fun `frequency rejection never announces or reads available inventory`() {
        Ads.policyChecker!!.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 0))
        spyOnReadyInventory()
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(attempt.positionSessionId)
        assertTrue(events.none { it.name == AdEventName.POSITION })
        assertEquals(AdBlockReason.DAILY_SHOW_LIMIT, attempt.policy!!.blocked)
        assertEquals(0, inventoryReads)
        assertEquals(0, shows)
        assertGateReleased()
    }

    @Test fun `show binding reuses the early session when a readiness signal has no actual bidder`() {
        val available: () -> Boolean = { true }
        ReflectionHelpers.setField(automatic, "isAdAvailable", available)
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertCorrelatedFailure(attempt, "no_preloaded_ad")
        assertGateReleased()
    }

    @Test fun `host first becoming interactive after the window cannot fabricate a position`() {
        var now = 0L
        val clock: () -> Long = { now }
        ReflectionHelpers.setField(automatic, "clock", clock)
        host.windowFocusChanged(false)
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events.isEmpty())
        now = 8_000L
        host.windowFocusChanged(true)
        advance(100)
        assertNull(attempt.positionSessionId)
        assertTrue(events.isEmpty())
        assertNull(ReflectionHelpers.getField<Any?>(automatic, "pendingOpportunity"))
        assertGateReleased()
    }

    @Test fun `disabled platforms never announce or read available inventory`() {
        Ads.policyChecker!!.policy = AdPolicy(platforms = AdPlatform.entries.associateWith { false })
        spyOnReadyInventory()
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(attempt.positionSessionId)
        assertEquals(listOf(AdEventName.SCENE_SKIP), events.map { it.name })
        assertEquals("platform_disabled", events.single().reason)
        assertEquals(0, inventoryReads)
        assertEquals(0, shows)
        assertGateReleased()
    }

    @Test fun `position listener backgrounding prevents inventory read and show`() {
        spyOnReadyInventory()
        onEvent = { if (it.name == AdEventName.POSITION) {
            backgrounded = true
            host.pause().stop()
        } }
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertCorrelatedFailure(attempt, "app_backgrounded_before_show")
        assertFalse(AdLifecycleMonitor.isAppInForeground)
        assertEquals(0, inventoryReads)
        assertEquals(0, shows)
        assertGateReleased()
    }

    @Test fun `position listener cancellation prevents inventory read and show`() {
        spyOnReadyInventory()
        onEvent = { if (it.name == AdEventName.POSITION) automatic.finishOpportunity("opportunity_cancelled") }
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        advance(8_000)
        assertCorrelatedFailure(attempt, "opportunity_cancelled")
        assertEquals(0, inventoryReads)
        assertEquals(0, shows)
        assertGateReleased()
    }

    @Test fun `disabling automatic ads after qualification releases gate on the scheduled check`() {
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(attempt.positionSessionId)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", config.copy(autoShowAppOpen = false))
        advance(100)
        assertCorrelatedFailure(attempt, "provider_not_ready")
        assertGateReleased()
    }

    @Test fun `losing provider readiness after qualification releases gate when inventory wakes scheduling`() {
        val attempt = begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(attempt.positionSessionId)
        setStage("WAITING_FOR_UMP")
        automatic.onAdAvailable()
        assertCorrelatedFailure(attempt, "provider_not_ready")
        advance(8_000)
        assertEquals(2, events.size)
        assertGateReleased()
    }

    private fun begin(): FullScreenShowAttempt {
        automatic.onProviderInitialized()
        return checkNotNull(ReflectionHelpers.getField<FullScreenShowAttempt?>(automatic, "pendingOpportunity"))
    }

    private fun assertCorrelatedFailure(attempt: FullScreenShowAttempt, reason: String) {
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals(reason, events.last().reason)
        assertEquals(attempt.positionSessionId, events.first().sessionId)
        assertEquals(events.first().sessionId, events.last().sessionId)
        assertNull(ReflectionHelpers.getField<Any?>(automatic, "pendingOpportunity"))
    }

    private fun assertGateReleased() {
        assertNull(ReflectionHelpers.getStaticField<Any?>(FullScreenShowGate::class.java, "owner"))
        val next = FullScreenShowAttempt()
        assertNull(FullScreenShowGate.reserve(next))
        next.complete()
    }

    private fun spyOnReadyInventory() {
        // Keep Ads.initialize's real qualification/failure binding; observe only the show boundary.
        ReflectionHelpers.setField(automatic, "isAdAvailable", { inventoryReads++; true })
        val show: (Activity, FullScreenShowAttempt) -> Unit = { _, attempt -> shows++; attempt.complete() }
        ReflectionHelpers.setField(automatic, "show", show)
    }

    private fun setStage(name: String) {
        val current = ReflectionHelpers.getStaticField<Any>(Ads::class.java, "initializationStage")
        val stage = current.javaClass.enumConstants!!.single { (it as Enum<*>).name == name }
        ReflectionHelpers.setStaticField(Ads::class.java, "initializationStage", stage)
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}
