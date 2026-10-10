package com.cashcraft.ads.mediation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMixedShowResultTest {
    @Test
    fun `ordinary interstitial dismissal does not earn a reward by default`() {
        assertFalse(mixedRewardEarned(AdFormat.INTERSTITIAL, false, AdShowResult.Dismissed, InterstitialRewardPolicy.NONE))
    }

    @Test
    fun `explicit business policy permits interstitial dismissal but never a failure`() {
        assertTrue(mixedRewardEarned(AdFormat.INTERSTITIAL, false, AdShowResult.Dismissed, InterstitialRewardPolicy.ON_DISMISSED))
        assertFalse(mixedRewardEarned(AdFormat.INTERSTITIAL, false, AdShowResult.Failed("dismissed_before_impression"), InterstitialRewardPolicy.ON_DISMISSED))
    }

    @Test
    fun `rewarded format always requires SDK reward callback`() {
        for (policy in InterstitialRewardPolicy.entries) {
            assertFalse(mixedRewardEarned(AdFormat.REWARDED, false, AdShowResult.Dismissed, policy))
            assertTrue(mixedRewardEarned(AdFormat.REWARDED, true, AdShowResult.Dismissed, policy))
        }
    }

    @Test
    fun `app open or absent candidate cannot grant reward`() {
        for (format in listOf(null, AdFormat.APP_OPEN)) {
            assertFalse(mixedRewardEarned(format, false, AdShowResult.Dismissed, InterstitialRewardPolicy.ON_DISMISSED))
        }
    }
}
