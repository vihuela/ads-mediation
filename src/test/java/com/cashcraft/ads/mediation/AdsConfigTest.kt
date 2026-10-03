package com.cashcraft.ads.mediation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsConfigTest {
    @Test
    fun `Banner cannot fall back to any full screen placement`() {
        val admob = AdMobProviderConfig(AdMobIds.TEST)
        val topon = testTopOnProvider()
        listOf(admob, topon, BiddingProviderConfig(admob, topon)).forEach { provider ->
            assertThrows(IllegalArgumentException::class.java) { provider.adUnitId(AdFormat.BANNER) }
        }
    }

    @Test
    fun `every format preloads two ads by default`() {
        val preload = AdMobPreloadConfig()

        assertEquals(2, preload.appOpen)
        assertEquals(2, preload.interstitial)
        assertEquals(2, preload.rewarded)
    }

    @Test
    fun `logging follows the module build type by default`() {
        val config = AdsConfig(provider = AdMobProviderConfig(AdMobIds.TEST))

        assertEquals(BuildConfig.DEBUG, config.loggingEnabled)
        assertEquals("AdsMediation", config.logTag)
    }

    @Test
    fun `ump consent is enabled before every provider by default`() {
        val adMobConsent = AdsConfig(
            provider = AdMobProviderConfig(ids = AdMobIds.TEST),
        ).umpConsent
        val topOnConsent = AdsConfig(
            provider = testTopOnProvider(),
        ).umpConsent

        assertEquals(true, adMobConsent.enabled)
        assertEquals(false, adMobConsent.tagForUnderAgeOfConsent)
        assertEquals(true, topOnConsent.enabled)
        assertEquals(false, topOnConsent.tagForUnderAgeOfConsent)
    }

    @Test
    fun `legacy AdMob consent configuration becomes the global UMP configuration`() {
        val config = AdsConfig(
            provider = AdMobProviderConfig(
                ids = AdMobIds.TEST,
                consent = UmpConsentConfig(enabled = false, tagForUnderAgeOfConsent = true),
            ),
        )

        assertEquals(false, config.umpConsent.enabled)
        assertEquals(true, config.umpConsent.tagForUnderAgeOfConsent)
    }

    @Test
    fun `bidding uses the direct AdMob consent configuration as its global UMP gate`() {
        val consent = UmpConsentConfig(enabled = false, tagForUnderAgeOfConsent = true)
        val config = AdsConfig(
            provider = BiddingProviderConfig(
                admob = AdMobProviderConfig(ids = AdMobIds.TEST, consent = consent),
                topon = testTopOnProvider(),
            ),
        )

        assertEquals(consent, config.umpConsent)
    }

    @Test
    fun `official demo ids are the injected defaults`() {
        assertEquals("ca-app-pub-3940256099942544~3347511713", AdMobIds.TEST.applicationId)
        assertEquals("ca-app-pub-3940256099942544/9257395921", AdMobIds.TEST.appOpenId)
        assertEquals("ca-app-pub-3940256099942544/1033173712", AdMobIds.TEST.interstitialId)
        assertEquals("ca-app-pub-3940256099942544/5224354917", AdMobIds.TEST.rewardedId)
    }

    @Test
    fun `invalid buffer sizes are rejected at configuration time`() {
        assertThrows(IllegalArgumentException::class.java) { AdMobPreloadConfig(rewarded = -1) }
        assertThrows(IllegalArgumentException::class.java) { AdMobPreloadConfig(interstitial = 16) }
        assertThrows(IllegalArgumentException::class.java) { AdMobPreloadConfig(banner = -1) }
    }

    @Test
    fun `omitted ids allow rewarded only and Banner only without changing full configurations`() {
        val rewardedOnly = AdMobProviderConfig(AdMobIds("app-id", rewardedId = "rewarded-id"))
        val bannerOnly = AdMobProviderConfig(AdMobIds("app-id"))
        for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
            assertTrue(AdMobProviderConfig(AdMobIds.TEST).isFormatEnabled(format))
            assertTrue(testTopOnProvider().isFormatEnabled(format))
            assertEquals(format == AdFormat.REWARDED, rewardedOnly.isFormatEnabled(format))
            assertFalse(bannerOnly.isFormatEnabled(format))
        }
        assertTrue(bannerOnly.isFormatEnabled(AdFormat.BANNER))
        assertThrows(IllegalArgumentException::class.java) { AdMobIds(" ") }
        assertThrows(IllegalArgumentException::class.java) { AdMobIds("app-id", rewardedId = " ") }
        assertThrows(IllegalArgumentException::class.java) { TopOnIds("app-id", " ") }
        assertThrows(IllegalArgumentException::class.java) { TopOnIds("app-id", "key", appOpenPlacementId = " ") }
    }

    @Test
    fun `zero buffers disable full screen formats but Banner requests remain usable`() {
        val provider = AdMobProviderConfig(AdMobIds.TEST, AdMobPreloadConfig(0, 0, 0, 0))
        AdFormat.entries.forEach { format ->
            assertEquals(format == AdFormat.BANNER, provider.isFormatEnabled(format))
        }
        assertEquals(0, provider.preload.banner)
    }

    @Test
    fun `bidding enables only the formats provided by each participant`() {
        val admob = AdMobProviderConfig(AdMobIds("admob-app", rewardedId = "admob-reward"))
        val topon = TopOnProviderConfig(TopOnIds("topon-app", "key", interstitialPlacementId = "topon-interstitial"))
        val bidding = BiddingProviderConfig(admob, topon)
        assertFalse(bidding.isFormatEnabled(AdFormat.APP_OPEN))
        assertTrue(bidding.isFormatEnabled(AdFormat.INTERSTITIAL))
        assertTrue(bidding.isFormatEnabled(AdFormat.REWARDED))
        assertFalse(bidding.isFormatEnabled(AdPlatform.ADMOB, AdFormat.INTERSTITIAL))
        assertTrue(bidding.isFormatEnabled(AdPlatform.TOPON, AdFormat.INTERSTITIAL))
        assertTrue(bidding.isFormatEnabled(AdPlatform.ADMOB, AdFormat.REWARDED))
        assertFalse(bidding.isFormatEnabled(AdPlatform.TOPON, AdFormat.REWARDED))
        assertFalse(topon.isFormatEnabled(AdFormat.BANNER))
        assertFalse(admob.isFormatEnabled(AdPlatform.TOPON, AdFormat.REWARDED))
    }

    @Test
    fun `TopOn maps the same three formats to placement ids`() {
        val provider = testTopOnProvider()

        assertEquals("splash-id", provider.adUnitId(AdFormat.APP_OPEN))
        assertEquals("interstitial-id", provider.adUnitId(AdFormat.INTERSTITIAL))
        assertEquals("rewarded-id", provider.adUnitId(AdFormat.REWARDED))
    }

    @Test
    fun `providers reject native format for full screen ids`() {
        val providers = listOf<AdProviderConfig>(
            AdMobProviderConfig(AdMobIds.TEST),
            testTopOnProvider(),
            BiddingProviderConfig(AdMobProviderConfig(AdMobIds.TEST), testTopOnProvider()),
        )

        providers.forEach { provider ->
            val error = assertThrows(IllegalStateException::class.java) {
                provider.adUnitId(AdFormat.NATIVE)
            }
            assertEquals("unsupported_ad_format", error.message)
        }
    }

    @Test
    fun `bidding keeps AdMob as deterministic fallback for unchanged failure events`() {
        val provider = BiddingProviderConfig(
            admob = AdMobProviderConfig(AdMobIds.TEST),
            topon = testTopOnProvider(),
        )

        assertEquals(AdPlatform.ADMOB, provider.platform)
        assertEquals(AdMobIds.TEST.interstitialId, provider.adUnitId(AdFormat.INTERSTITIAL))
        assertEquals(AdMediationMode.BIDDING, provider.mediationMode)
    }

    @Test
    fun `each provider exposes a stable mediation mode`() {
        assertEquals(
            AdMediationMode.ADMOB,
            AdMobProviderConfig(AdMobIds.TEST).mediationMode,
        )
        assertEquals(AdMediationMode.TOPON, testTopOnProvider().mediationMode)
    }

    @Test
    fun `Native 广告位仅从初始化配置解析`() {
        val page = NativeRequest("home", topOnTemplateAspectRatio = 2f, bidTimeoutMillis = 1234, preferCachedAds = true)
        val google = AdMobProviderConfig(AdMobIds.TEST)
        val topOn = testTopOnProvider().let { it.copy(ids = it.ids.copy(nativePlacementId = "native-topon")) }
        assertEquals(listOf(AdPlatform.ADMOB), google.resolveNativeRequest(page).candidates().map { it.platform })
        assertEquals(listOf("native-topon"), topOn.resolveNativeRequest(page).candidates().map { it.adUnitId })
        val both = BiddingProviderConfig(google, topOn).resolveNativeRequest(page)
        assertEquals(true, both.isBidding)
        assertEquals(1234L, both.bidTimeoutMillis)
        assertTrue(both.preferCachedAds)
        assertTrue(both.candidates().all { it.preferCachedAds })
        assertEquals(2f, both.candidates().last().topOnTemplateAspectRatio)
        assertEquals(listOf(AdMobIds.TEST.nativeId, "native-topon"), both.candidates().map { it.adUnitId })
        val onlyGoogle = BiddingProviderConfig(google, testTopOnProvider()).resolveNativeRequest(page)
        assertEquals(false, onlyGoogle.isBidding)
        assertEquals(1, onlyGoogle.candidates().size)
        val onlyTopOn = BiddingProviderConfig(google.copy(ids = google.ids.copy(nativeId = null)), topOn)
            .resolveNativeRequest(page)
        assertEquals(AdPlatform.TOPON, onlyTopOn.candidates().single().platform)
        assertEquals("native_not_configured", testTopOnProvider().resolveNativeRequest(page).failureReason())
        assertEquals("native_not_configured", google.copy(ids = google.ids.copy(nativeId = null))
            .resolveNativeRequest(page).failureReason())
        assertEquals("invalid_position", page.copy(position = " ").failureReason())
        assertEquals("invalid_template_ratio", page.copy(topOnTemplateAspectRatio = Float.NaN).failureReason())
        assertEquals("invalid_native_bid_timeout", page.copy(bidTimeoutMillis = 0).failureReason())
        assertThrows(IllegalArgumentException::class.java) { google.ids.copy(nativeId = " ") }
        assertThrows(IllegalArgumentException::class.java) { topOn.ids.copy(nativePlacementId = " ") }
        assertEquals(listOf("position", "topOnTemplateAspectRatio", "bidTimeoutMillis", "preferCachedAds"),
            NativeRequest::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name })
    }

    private fun testTopOnProvider() = TopOnProviderConfig(
        ids = TopOnIds(
            applicationId = "app-id",
            applicationKey = "app-key",
            appOpenPlacementId = "splash-id",
            interstitialPlacementId = "interstitial-id",
            rewardedPlacementId = "rewarded-id",
        ),
    )
}
