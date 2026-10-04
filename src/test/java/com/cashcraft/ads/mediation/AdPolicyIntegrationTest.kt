package com.cashcraft.ads.mediation

import com.cashcraft.ads.mediation.internal.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [ShadowMMKV::class])
class AdPolicyIntegrationTest {
    private val previous = listOf("config", "policyChecker", "policyUpdatePosted").associateWith {
        ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it)
    }
    private lateinit var checker: AdPolicyChecker
    private val blocks = mutableListOf<AdBlockInfo>()
    private val events = mutableListOf<AdEvent>()
    private val attempts = mutableListOf<FullScreenShowAttempt>()

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        ShadowMMKV.reset()
        checker = AdPolicyChecker(AdUsageStore(context, 1), { 1_000_000L }, { 1_000_000L })
        ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", checker)
        ReflectionHelpers.setStaticField(Ads::class.java, "policyUpdatePosted", true)
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            AdMobProviderConfig(AdMobIds.TEST), eventListener = { events += it },
            onAdBlocked = { blocks += it }, autoShowAppOpen = false,
        ))
    }

    @After fun restore() {
        attempts.forEach { it.complete() }
        previous.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
    }

    @Test fun `business rejection precedes host and provider lookup and reports only once`() {
        checker.policy = AdPolicy(enabled = false)
        val results = mutableListOf<AdShowResult>()
        val task = Ads.showInter("save", onResult = results::add)
        task.cancel()
        assertEquals(listOf(AdShowResult.Blocked(AdBlockReason.GLOBAL_DISABLED)), results)
        assertEquals(listOf(AdBlockInfo("save", AdBlockReason.GLOBAL_DISABLED)), blocks)
        assertTrue(events.isEmpty())
    }

    @Test fun `policy changed by a position listener blocks final handoff without show fail`() {
        val policy = AdPolicyAttempt(AdPolicyRequest("save", fullscreen = true))
        assertEquals(AdPolicyCheckResult.Passed, policy.check())
        val attempt = FullScreenShowAttempt().also { it.policy = policy; attempts += it }
        assertNull(FullScreenShowGate.reserve(attempt))
        val session = AdShowSession(
            listener = { event ->
                events += event
                if (event.name == AdEventName.POSITION) checker.policy = AdPolicy(enabled = false)
            }, platform = AdPlatform.ADMOB, mediationMode = AdMediationMode.ADMOB,
            format = AdFormat.INTERSTITIAL, position = "save", adUnitId = "test",
            sessionId = "one", number = 1, attempt = attempt,
        )
        val denied = checkNotNull(FullScreenShowGate.commit(attempt))
        session.showFailure(denied)
        attempt.complete()
        policy.complete()
        assertFalse(FullScreenShowGate.isAnyAdShowing)
        assertEquals(1, blocks.size)
        assertEquals(AdBlockReason.GLOBAL_DISABLED, blocks.single().reason)
        assertFalse(events.any { it.name == AdEventName.SHOW_FAIL })
        assertEquals(AdShowResult.Blocked(AdBlockReason.GLOBAL_DISABLED), policy.result(AdShowResult.Failed(denied)))
    }

    @Test fun `last allowed display is not rejected by its own quota and close affects next display`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(true, fullscreenGapSeconds = 120,
            dailyMaxShows = 1, dailyMaxClicks = 3))
        val policy = AdPolicyAttempt(AdPolicyRequest("open", fullscreen = true))
        assertEquals(AdPolicyCheckResult.Passed, policy.reserve())
        assertEquals(AdPolicyCheckResult.Passed, policy.check())
        policy.impression()
        policy.impression()
        assertEquals(AdPolicyCheckResult.Passed, policy.check())
        policy.complete()
        assertTrue(blocks.isEmpty())
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.FULLSCREEN_GAP),
            checker.check(AdPolicyRequest("next", fullscreen = true)))
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT),
            checker.check(AdPolicyRequest("reward", fullscreen = true, userInitiated = true)))
    }

    @Test fun `actual SDK impression arriving after terminal failure is still counted once`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 1))
        val attempt = FullScreenShowAttempt().also {
            it.policy = AdPolicyAttempt(AdPolicyRequest("open", fullscreen = true))
            attempts += it
        }
        val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB, AdMediationMode.ADMOB,
            AdFormat.APP_OPEN, "open", "test", "late", 1, attempt = attempt)
        session.showFailure("cancelled")
        attempt.complete()
        session.impression("network", "late-response")
        session.impression("network", "late-response")
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT),
            checker.check(AdPolicyRequest("next")))
        assertTrue(events.none { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `facade assigns every fullscreen entry its own quota before provider lookup`() {
        checker.policy = typedPolicy(AdSceneType.entries.associateWith { AdSceneQuota(true, 0, 3) })
        val results = mutableListOf<AdShowResult>()
        val unusedHost = android.app.Activity()
        Ads.showOpen("open", onResult = results::add)
        Ads.showInter("inter", onResult = results::add)
        Ads.showRewardedWhenReady(unusedHost, "reward") { results += it.showResult }
        Ads.showNativeFullScreen(unusedHost, "native_full", NativeLayout.Custom { error("must not render") },
            onResult = results::add)
        assertEquals(List(4) { AdShowResult.Blocked(AdBlockReason.DAILY_SHOW_LIMIT) }, results)
        assertEquals(4, blocks.size)
        assertTrue(events.isEmpty())
    }

    @Test fun `missing identity fails closed even when frequency is disabled`() {
        checker.policy = typedPolicy(emptyMap()).let { it.copy(frequency = it.frequency.copy(enabled = false)) }
        val results = mutableListOf<AdShowResult>()
        Ads.showNativeFullScreen(android.app.Activity(), "native", NativeLayout.Custom { error("must not render") },
            sceneType = null, onResult = results::add)
        assertEquals(listOf(AdShowResult.Blocked(AdBlockReason.INVALID_SCENE_TYPE)), results)
        assertTrue(events.isEmpty())
    }

    @Test fun `open served by interstitial preserves origin for impressions and repeated late clicks`() {
        checker.policy = typedPolicy(mapOf(
            AdSceneType.OPEN to AdSceneQuota(true, 2, 2),
            AdSceneType.INTER to AdSceneQuota(true, 2, 2),
        ))
        val attempt = FullScreenShowAttempt().also {
            it.policy = AdPolicyAttempt(AdPolicyRequest("cold_start", fullscreen = true, sceneType = AdSceneType.OPEN))
            attempts += it
        }
        assertEquals(AdPolicyCheckResult.Passed, attempt.policy!!.reserve())
        val observed = ReflectionHelpers.getStaticField<AdEventListener>(Ads::class.java, "observedEvents")
        val session = AdShowSession(observed, AdPlatform.TOPON, AdMediationMode.BIDDING,
            AdFormat.INTERSTITIAL, "cold_start", "test", "open-interstitial", 1, attempt = attempt)
        session.impression("network", "response")
        session.impression("network", "response")
        session.emit(AdEventName.CLICK)
        assertEquals(AdPolicyCheckResult.Passed,
            checker.check(AdPolicyRequest("hot_start", sceneType = AdSceneType.OPEN)))
        attempt.complete()
        session.emit(AdEventName.CLICK)
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_CLICK_LIMIT),
            checker.check(AdPolicyRequest("hot_start", sceneType = AdSceneType.OPEN)))
        assertEquals(AdPolicyCheckResult.Passed,
            checker.check(AdPolicyRequest("save", sceneType = AdSceneType.INTER)))
        assertEquals(2, events.count { it.name == AdEventName.CLICK })
        assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
    }

    @Test fun `native fallback adapter counts only the original inter opportunity`() {
        checker.policy = typedPolicy(mapOf(AdSceneType.INTER to AdSceneQuota(true, 1, 2)))
        val original = AdPolicyAttempt(AdPolicyRequest("save", fullscreen = true, sceneType = AdSceneType.INTER))
        val fallback = com.cashcraft.ads.mediation.internal.nativeads.NativeCardPolicyAdapter(original, owned = false)
        assertNull(fallback.reserve())
        fallback.impression()
        fallback.impression()
        fallback.click()
        original.complete()
        fallback.click()
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_CLICK_LIMIT),
            checker.check(AdPolicyRequest("back", sceneType = AdSceneType.INTER)))
        for (type in listOf(AdSceneType.NATIVE, AdSceneType.NATIVE_FULLSCREEN, AdSceneType.OPEN)) {
            assertEquals(AdPolicyCheckResult.Passed, checker.check(AdPolicyRequest("other", sceneType = type)))
        }
    }

    @Test fun `开屏原生兜底只消耗开屏场景配额`() {
        checker.policy = typedPolicy(AdSceneType.entries.associateWith { AdSceneQuota(true, 1, 1) })
        val original = AdPolicyAttempt(AdPolicyRequest("cold_start", fullscreen = true, sceneType = AdSceneType.OPEN))
        val fallback = com.cashcraft.ads.mediation.internal.nativeads.NativeCardPolicyAdapter(original, owned = false)
        assertNull(fallback.reserve())
        fallback.impression()
        fallback.impression()
        fallback.click()
        original.complete()
        assertEquals(AdPolicyCheckResult.Blocked(AdBlockReason.DAILY_CLICK_LIMIT),
            checker.check(AdPolicyRequest("next", sceneType = AdSceneType.OPEN)))
        for (type in AdSceneType.entries - AdSceneType.OPEN) {
            assertEquals(AdPolicyCheckResult.Passed, checker.check(AdPolicyRequest("other", sceneType = type)))
        }
        assertEquals(AdSceneType.NATIVE, NativeRequest("inline").sceneType)
    }

    @Test fun `native platform resolution and candidates retain explicit origin`() {
        val provider = BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST),
            TopOnProviderConfig(TopOnIds("app", "key", nativePlacementId = "native")))
        for (type in listOf(AdSceneType.OPEN, AdSceneType.INTER, AdSceneType.NATIVE_FULLSCREEN)) {
            val resolved = provider.resolveNativeRequest(NativeRequest("fallback", sceneType = type), fullScreen = true)
            assertEquals(type, resolved.sceneType)
            assertTrue(resolved.candidates().all { it.sceneType == type })
        }
    }

    private fun typedPolicy(quotas: Map<AdSceneType, AdSceneQuota>) = AdPolicy(
        frequency = AdFrequencyPolicy(enabled = true,
            sceneQuotas = AdSceneType.entries.associateWith { quotas[it] ?: AdSceneQuota() }),
    )
}
