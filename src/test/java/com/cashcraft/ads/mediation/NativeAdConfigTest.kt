package com.cashcraft.ads.mediation

import com.cashcraft.ads.mediation.internal.BidCandidateSelector
import com.cashcraft.ads.mediation.internal.BidDecision
import com.cashcraft.ads.mediation.internal.toEventData
import com.cashcraft.ads.mediation.internal.withAdType
import org.junit.Assert.*
import org.junit.Test

class NativeAdConfigTest {
    private val topOn = TopOnIds("app", "key", "open", "interstitial", "rewarded")

    @Test fun `existing integrations leave native disabled`() {
        val legacy = AdMobIds("app", "open", "interstitial", "rewarded")
        assertNull(legacy.nativeId)
        assertNull(topOn.nativePlacementId)
        assertEquals("", AdMobProviderConfig(legacy).adUnitId(AdFormat.NATIVE))
        assertEquals("", TopOnProviderConfig(topOn).adUnitId(AdFormat.NATIVE))
    }

    @Test fun `blank native IDs disable native when other formats are configured`() {
        assertFalse(AdMobProviderConfig(AdMobIds.TEST.copy(nativeId = " ")).hasAdUnit(AdFormat.NATIVE))
        assertFalse(TopOnProviderConfig(topOn.copy(nativePlacementId = "")).hasAdUnit(AdFormat.NATIVE))
        assertEquals("ca-app-pub-3940256099942544/2247696110",
            AdMobProviderConfig(AdMobIds.TEST).adUnitId(AdFormat.NATIVE))
        assertEquals("native", TopOnProviderConfig(topOn.copy(nativePlacementId = "native")).adUnitId(AdFormat.NATIVE))
    }

    @Test fun `native is one analytics type and never earns mixed rewards`() {
        assertEquals("feed_native", "feed".withAdType(AdFormat.NATIVE))
        assertEquals("feed_native", "feed_native".withAdType(AdFormat.NATIVE))
        assertFalse(mixedRewardEarned(AdFormat.NATIVE, false, AdShowResult.Dismissed,
            InterstitialRewardPolicy.ON_DISMISSED))
        assertFalse(AutoAppOpenMode.APP_OPEN_OR_INTERSTITIAL.formats.contains(AdFormat.NATIVE))
    }

    @Test fun `native bid preserves unknown price and selects a renderable priced candidate`() {
        val admob = AdBidCandidate(AdPlatform.ADMOB, AdFormat.NATIVE, "google", true, null)
        val topon = AdBidCandidate(AdPlatform.TOPON, AdFormat.NATIVE, "topon", true, 0.001)
        val formats = listOf(AdFormat.NATIVE)
        val candidates = listOf(admob, topon)
        val selection = BidCandidateSelector.select(candidates, formats)
        assertEquals(topon, selection)
        val data = BidDecision(formats, candidates, selection).toEventData()
        assertNull(data.admobValue)
        assertEquals(AdFormat.NATIVE, data.winnerFormat)
        assertEquals(admob, BidCandidateSelector.select(listOf(admob, topon.copy(available = false)), formats))
    }
}
