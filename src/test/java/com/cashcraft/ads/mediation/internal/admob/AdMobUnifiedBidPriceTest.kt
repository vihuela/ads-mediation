package com.cashcraft.ads.mediation.admob

import com.cashcraft.ads.mediation.AdBidCandidate
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.internal.BidCandidateSelector
import java.io.File
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class AdMobUnifiedBidPriceTest {
    private val config = AdMobNextGenBidPrice.parse(
        File("src/main/assets/google_next_gen_preload_reflection_paths.json").readText(),
    ).getValue("1.2.1").copy(
        sdkRootClass = BidPriceTestRoot::class.java.name,
        sdkRootMethod = "getInstance", providerField = "provider", providerGetMethod = "get",
        formatMapFields = listOf("APP_OPEN", "INTERSTITIAL", "REWARDED").associateWith { "managers" },
        managerQueueField = "queue", queueItemResultField = "result", preloadResultAdField = "ad",
        internalAdConfigurationMethod = "configuration", configurationPriceField = "price",
        priceValueMicrosField = "micros", priceCurrencyCodeField = "currency",
        nativeAdPaths = listOf(listOf("price")),
    )

    private fun prices(leaf: Any, override: ReflectionConfig = config, version: String = "1.2.1"): List<Double?> {
        val queue = ArrayDeque(listOf(BidPriceTestItem(BidPriceTestResult(BidPriceTestAd(leaf)))))
        BidPriceTestRoot.service = BidPriceTestService(BidPriceTestProvider(BidPriceTestRegistry(
            mapOf("test" to BidPriceTestManager(queue)),
        )))
        val configs = mapOf("1.2.1" to override)
        val fullScreen = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED).map {
            AdMobNextGenBidPrice.readPreloadPrice(it, "test", version, configs)
        }
        assertEquals("Reading a bid must not consume the cached ad", 1, queue.size)
        return fullScreen + AdMobNextGenBidPrice.readNativePrice(
            BidPriceTestConfig(leaf), version, configs,
        )
    }

    @Test fun `all formats preserve USD micros and genuine zero`() {
        for (micros in listOf(0L, 1L, 1234L, Long.MAX_VALUE)) {
            assertEquals(List(4) { micros / 1_000_000.0 }, prices(BidPriceTestPrice(micros, " usd ")))
        }
    }

    @Test fun `non USD and unknown currencies remain null in every format`() {
        for (currency in listOf("CNY", "EUR", "JPY", "", "  ", null, 123)) {
            assertEquals(List<Double?>(4) { null }, prices(BidPriceTestPrice(1_000_000L, currency)))
        }
    }

    @Test fun `invalid amounts never become a valid zero price`() {
        for (amount in listOf(-1L, Long.MIN_VALUE, 123, 1.25, Double.NaN, Double.POSITIVE_INFINITY, "123", null)) {
            assertEquals(List<Double?>(4) { null }, prices(BidPriceTestPrice(amount, "USD")))
        }
    }

    @Test fun `missing fields configuration and unknown SDK versions fail closed`() {
        assertEquals(List<Double?>(4) { null }, prices(Any()))
        val leaf = BidPriceTestPrice(123L, "USD")
        for (override in listOf(config.copy(priceCurrencyCodeField = null),
            config.copy(priceCurrencyCodeField = "missing"), config.copy(priceValueMicrosField = "missing"))) {
            assertEquals(List<Double?>(4) { null }, prices(leaf, override))
        }
        assertEquals(List<Double?>(4) { null }, prices(leaf, version = "1.2.2"))
    }

    @Test fun `unconvertible bid cannot beat a known USD bid and remains available as fallback`() {
        for (price in prices(BidPriceTestPrice(1_000_000L, "EUR"))) {
            val google = AdBidCandidate(AdPlatform.ADMOB, AdFormat.INTERSTITIAL, "google", true, price)
            val topOn = AdBidCandidate(AdPlatform.TOPON, AdFormat.INTERSTITIAL, "topon", true, 0.001)
            assertEquals(topOn, BidCandidateSelector.select(listOf(google, topOn), listOf(AdFormat.INTERSTITIAL)))
            assertEquals(google, BidCandidateSelector.select(listOf(google), listOf(AdFormat.INTERSTITIAL)))
        }
    }
}

class BidPriceTestRoot {
    companion object {
        lateinit var service: BidPriceTestService
        @JvmStatic fun getInstance() = service
    }
}
data class BidPriceTestService(val provider: BidPriceTestProvider)
class BidPriceTestProvider(private val registry: BidPriceTestRegistry) { fun get() = registry }
data class BidPriceTestRegistry(val managers: Map<String, BidPriceTestManager>)
data class BidPriceTestManager(val queue: java.util.Queue<BidPriceTestItem>)
data class BidPriceTestItem(val result: BidPriceTestResult)
data class BidPriceTestResult(val ad: BidPriceTestAd)
class BidPriceTestAd(private val leaf: Any) { fun configuration() = BidPriceTestConfig(leaf) }
data class BidPriceTestConfig(val price: Any)
data class BidPriceTestPrice(val micros: Any?, val currency: Any?)
