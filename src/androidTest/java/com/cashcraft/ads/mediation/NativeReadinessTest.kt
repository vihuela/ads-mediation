package com.cashcraft.ads.mediation

import android.content.Context
import android.test.InstrumentationTestCase
import com.google.android.ump.ConsentInformation
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import com.cashcraft.ads.mediation.internal.nativeads.NativeAvailability
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Exercises the real Ads readiness wiring without initializing either SDK or making a request. */
@Suppress("DEPRECATION")
class NativeReadinessTest : InstrumentationTestCase() {
    fun testSubscriptionReadsAdsAvailabilityAcrossConsentAndInitializationChanges() {
        withReadiness(AdMobProviderConfig(AdMobIds.TEST)) { scope ->
            val observed = mutableListOf<NativeAvailability>()
            val close = Ads.observeNativeReadiness { observed += Ads.nativeAvailability(AdPlatform.ADMOB) }
            try {
                scope.stage("WAITING_FOR_UMP")
                scope.consent.allowed = false
                scope.admobState(AdMobState.INITIALIZING)
                Ads.notifyNativeReadiness()

                scope.stage("COMPLETE")
                Ads.notifyNativeReadiness()

                scope.consent.allowed = true
                scope.admobState(AdMobState.READY)
                Ads.notifyNativeReadiness()

                scope.admobState(AdMobState.FAILED)
                Ads.notifyNativeReadiness()

                scope.consent.allowed = false
                scope.admobState(AdMobState.READY)
                Ads.notifyNativeReadiness()

                assertEquals(
                    listOf(
                        NativeAvailability(),
                        NativeAvailability(failure = "consent_not_obtained"),
                        NativeAvailability(ready = true),
                        NativeAvailability(failure = "native_platform_initialization_failed"),
                        NativeAvailability(failure = "consent_not_obtained"),
                    ),
                    observed,
                )
            } finally {
                close.close()
            }
        }
    }

    fun testBiddingReadsEachProviderStateWhenTheOtherProviderFinishes() {
        withReadiness(
            BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST), testTopOnProvider()),
        ) { scope ->
            scope.stage("PROVIDER_INITIALIZING")
            scope.consent.allowed = true
            scope.admobState(AdMobState.READY)
            scope.topOnState(TopOnState.INITIALIZING)

            val observed = mutableListOf<Pair<NativeAvailability, NativeAvailability>>()
            val close = Ads.observeNativeReadiness {
                observed += (Ads.nativeAvailability(AdPlatform.ADMOB) to
                    Ads.nativeAvailability(AdPlatform.TOPON))
            }
            try {
                Ads.notifyNativeReadiness()
                scope.topOnState(TopOnState.READY)
                Ads.notifyNativeReadiness()
                scope.topOnState(TopOnState.FAILED)
                Ads.notifyNativeReadiness()

                assertEquals(
                    listOf(
                        NativeAvailability(ready = true) to NativeAvailability(),
                        NativeAvailability(ready = true) to NativeAvailability(ready = true),
                        NativeAvailability(ready = true) to NativeAvailability(failure = "native_platform_initialization_failed"),
                    ),
                    observed,
                )
            } finally {
                close.close()
            }
        }
    }

    fun testUnconfiguredPlatformAndListenerLifecycleAreSafe() {
        withReadiness(AdMobProviderConfig(AdMobIds.TEST)) { scope ->
            scope.stage("COMPLETE")
            scope.consent.allowed = true
            scope.admobState(AdMobState.READY)
            assertEquals(
                "native_platform_not_configured",
                Ads.nativeAvailability(AdPlatform.TOPON).failure,
            )

            var calls = 0
            val close = Ads.observeNativeReadiness { calls++ }
            Ads.notifyNativeReadiness()
            close.close()
            close.close()
            Ads.notifyNativeReadiness()
            assertEquals(1, calls)
        }
    }

    fun testRemovingLaterListenerDuringNotificationAndThrowingDoesNotBreakTheNextListener() {
        withReadiness(AdMobProviderConfig(AdMobIds.TEST)) {
            val calls = mutableListOf<String>()
            lateinit var closeLater: AutoCloseable
            val closeFirst = Ads.observeNativeReadiness {
                calls += "first"
                closeLater.close()
                error("listener failure")
            }
            closeLater = Ads.observeNativeReadiness { calls += "removed" }
            val closeSurvivor = Ads.observeNativeReadiness { calls += "survivor" }
            try {
                Ads.notifyNativeReadiness()
                Ads.notifyNativeReadiness()
                assertEquals(listOf("first", "survivor", "first", "survivor"), calls)
            } finally {
                closeFirst.close()
                closeLater.close()
                closeSurvivor.close()
            }
        }
    }

    private fun withReadiness(provider: AdProviderConfig, block: (ReadinessScope) -> Unit) = instrumentation.runOnMainSync {
        val scope = ReadinessScope(instrumentation.targetContext.applicationContext, provider)
        try {
            block(scope)
        } finally {
            scope.close()
        }
    }

    private fun testTopOnProvider() = TopOnProviderConfig(
        ids = TopOnIds(
            applicationId = "test-app-id",
            applicationKey = "test-app-key",
            appOpenPlacementId = "test-app-open",
            interstitialPlacementId = "test-interstitial",
            rewardedPlacementId = "test-rewarded",
        ),
    )

    private class ReadinessScope(context: Context, provider: AdProviderConfig) : AutoCloseable {
        val consent = MutableConsent(false)

        private val adsConfig = field(Ads, "config")
        private val adsStage = field(Ads, "initializationStage")
        private val adsConsent = field(Ads, "umpConsentManager")
        private val listeners = field(Ads, "nativeReadinessListeners")
        private val admob = field(AdMobAds, "state")
        private val topOn = field(TopOnAds, "state")
        private val originalConfig = adsConfig.get(Ads)
        private val originalStage = adsStage.get(Ads)
        private val originalConsent = adsConsent.get(Ads)
        private val originalListeners = listeners.get(Ads).let { it as java.util.concurrent.CopyOnWriteArrayList<() -> Unit> }.toList()
        private val originalAdMobState = admob.get(AdMobAds)
        private val originalTopOnState = topOn.get(TopOnAds)
        private var closed = false

        init {
            val manager = UmpConsentManager(context, UmpConsentConfig(enabled = true), false, "NativeReadinessTest")
            field(manager, "consentInformation").set(manager, consent.information)
            try {
                listeners.get(Ads).let {
                    it as java.util.concurrent.CopyOnWriteArrayList<() -> Unit>
                }.clear()
                adsConfig.set(Ads, AdsConfig(provider = provider, umpConsent = UmpConsentConfig(enabled = true)))
                adsStage.set(Ads, enumValue(adsStage.type, "WAITING_FOR_UMP"))
                adsConsent.set(Ads, manager)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun stage(name: String) = adsStage.set(Ads, enumValue(adsStage.type, name))

        fun admobState(value: AdMobState) = admob.set(AdMobAds, value)

        fun topOnState(value: TopOnState) = topOn.set(TopOnAds, value)

        override fun close() {
            if (closed) return
            closed = true
            adsConfig.set(Ads, originalConfig)
            adsStage.set(Ads, originalStage)
            adsConsent.set(Ads, originalConsent)
            admob.set(AdMobAds, originalAdMobState)
            topOn.set(TopOnAds, originalTopOnState)
            val currentListeners = listeners.get(Ads).let {
                it as java.util.concurrent.CopyOnWriteArrayList<() -> Unit>
            }
            currentListeners.clear()
            currentListeners.addAll(originalListeners)
        }
    }

    private class MutableConsent(initialAllowed: Boolean) : InvocationHandler {
        var allowed = initialAllowed
        val information = Proxy.newProxyInstance(
            ConsentInformation::class.java.classLoader,
            arrayOf(ConsentInformation::class.java),
            this,
        ) as ConsentInformation

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = when (method.name) {
            "getConsentStatus" -> if (allowed) {
                ConsentInformation.ConsentStatus.OBTAINED
            } else {
                ConsentInformation.ConsentStatus.REQUIRED
            }
            "canRequestAds" -> allowed
            "getPrivacyOptionsRequirementStatus" ->
                ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED
            "isConsentFormAvailable" -> false
            "toString" -> "MutableConsent"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected UMP call: ${method.name}")
        }
    }

    private companion object {
        fun field(owner: Any, name: String): Field = owner.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }

        fun enumValue(type: Class<*>, name: String): Any =
            type.enumConstants!!.single { (it as Enum<*>).name == name }

    }
}
