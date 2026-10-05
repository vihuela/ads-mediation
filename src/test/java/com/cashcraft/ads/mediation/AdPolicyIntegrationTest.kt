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
        assertEquals(listOf(AdEventName.SCENE_SKIP), events.map { it.name })
        assertEquals("", events.single().sessionId)
        assertEquals("global_disabled", events.single().reason)
    }

    @Test fun `policy changed after admitted position closes that session with show fail`() {
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
        assertTrue(session.admit())
        val denied = checkNotNull(FullScreenShowGate.commit(attempt))
        session.showFailure(denied)
        attempt.complete()
        policy.complete()
        assertFalse(FullScreenShowGate.isAnyAdShowing)
        assertEquals(1, blocks.size)
        assertEquals(AdBlockReason.GLOBAL_DISABLED, blocks.single().reason)
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
        assertEquals("one", events.last().sessionId)
        assertEquals("global_disabled", events.last().reason)
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
        assertEquals(3, events.size) // Native is outside this fullscreen event contract.
        assertTrue(events.all { it.name == AdEventName.SCENE_SKIP && it.reason == "show_rate_limited" })
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
        assertEquals(0, events.count { it.name == AdEventName.IMPRESSION })
        session.revenue(valueMicros = 1250, currency = "USD")
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

    @Test fun `qualification observes policy without occupying the last show quota`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 1))
        val policy = AdPolicyAttempt(AdPolicyRequest("save", fullscreen = true, sceneType = AdSceneType.INTER))
        val attempt = FullScreenShowAttempt().also { it.policy = policy; attempts += it }
        val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB, AdMediationMode.ADMOB,
            AdFormat.INTERSTITIAL, "save", "unit", "internal", 1, attempt = attempt)
        assertTrue(session.admit())
        assertEquals(AdPolicyCheckResult.Passed, checker.check(AdPolicyRequest("other")))
        assertEquals(AdPolicyCheckResult.Passed, policy.reserve())
        session.showFailure("no_preloaded_ad")
        assertEquals(listOf(AdEventName.POSITION, AdEventName.SHOW_FAIL), events.map { it.name })
    }

    @Test fun `an existing own reservation does not become a telemetry skip`() {
        checker.policy = AdPolicy(frequency = AdFrequencyPolicy(enabled = true, dailyMaxShows = 1))
        val policy = AdPolicyAttempt(AdPolicyRequest("save", fullscreen = true))
        assertEquals(AdPolicyCheckResult.Passed, policy.reserve())
        val attempt = FullScreenShowAttempt().also { it.policy = policy; attempts += it }
        val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB, AdMediationMode.ADMOB,
            AdFormat.INTERSTITIAL, "save", "unit", "reserved", 1, attempt = attempt)
        assertTrue(session.admit())
        assertEquals(listOf(AdEventName.POSITION), events.map { it.name })
    }

    @Test fun `only configured platform switches cause platform skip and retries stay independent`() {
        checker.policy = AdPolicy(platforms = mapOf(AdPlatform.ADMOB to false))
        repeat(2) {
            val attempt = FullScreenShowAttempt().also {
                it.policy = AdPolicyAttempt(AdPolicyRequest("same", fullscreen = true)); attempts += it
            }
            val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB, AdMediationMode.ADMOB,
                AdFormat.INTERSTITIAL, "same", "unit", "internal-$it", 1, attempt = attempt)
            session.showFailure("no_preloaded_ad")
            session.showFailure("duplicate")
        }
        assertEquals(List(2) { AdEventName.SCENE_SKIP }, events.map { it.name })
        assertTrue(events.all { it.reason == "platform_disabled" && !it.analyticsParameters().containsKey("session_id") })
        assertTrue(Ads.fullScreenPlatformsDisabled(listOf(AdFormat.INTERSTITIAL)))
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST),
                TopOnProviderConfig(TopOnIds("app", "key", interstitialPlacementId = "topon"))),
            eventListener = { events += it }))
        assertFalse(Ads.fullScreenPlatformsDisabled(listOf(AdFormat.INTERSTITIAL)))
        checker.policy = checker.policy.copy(platforms = mapOf(AdPlatform.ADMOB to false, AdPlatform.TOPON to false))
        assertTrue(Ads.fullScreenPlatformsDisabled(listOf(AdFormat.INTERSTITIAL)))
    }

    @Test fun `selected main and interstitial fallback keep real bidding platform and original open position`() {
        ReflectionHelpers.setStaticField(Ads::class.java, "config", AdsConfig(
            BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST),
                TopOnProviderConfig(TopOnIds("app", "key", interstitialPlacementId = "inter"))),
            eventListener = { events += it }))
        for (platform in AdPlatform.entries) {
            events.clear()
            checker.policy = AdPolicy(platforms = mapOf(AdPlatform.ADMOB to (platform == AdPlatform.ADMOB)))
            val attempt = FullScreenShowAttempt().also {
                it.policy = AdPolicyAttempt(AdPolicyRequest("cold_start", fullscreen = true, sceneType = AdSceneType.OPEN))
                attempts += it
            }
            assertNull(FullScreenShowGate.reserve(attempt))
            val session = AdShowSession(AdEventListener(events::add), platform, AdMediationMode.BIDDING,
                AdFormat.INTERSTITIAL, "cold_start", "unit", "selected-${platform.name}", 1, attempt = attempt)
            assertTrue(session.admit())
            assertNull(FullScreenShowGate.commit(attempt))
            session.revenue(valueMicros = 100, currency = "USD")
            session.impression("network", "response")
            assertEquals(AdShowResult.Dismissed, session.dismissedResult())
            session.emit(AdEventName.DISMISS)
            session.emit(AdEventName.DISMISS)
            attempt.complete()
            assertEquals(listOf(AdEventName.POSITION, AdEventName.IMPRESSION,
                AdEventName.DISMISS), events.map { it.name })
            assertTrue(events.all { it.platform == platform && it.platformKnown && it.position == "cold_start" })
            assertTrue(events.none { it.analyticsParameters().containsKey("platform_known") })
        }
    }

    private fun typedPolicy(quotas: Map<AdSceneType, AdSceneQuota>) = AdPolicy(
        frequency = AdFrequencyPolicy(enabled = true,
            sceneQuotas = AdSceneType.entries.associateWith { quotas[it] ?: AdSceneQuota() }),
    )
}
