package com.cashcraft.ads.mediation.admob

import org.junit.Assert.assertEquals
import org.junit.Test

class AdMobNextGenBidPriceTest {
    @Test
    fun `static 1_2_1 reflection path parses`() {
        val repository = AdMobNextGenBidPrice.parse(
            """
            {
              "versions": {
                "1.2.1": {
                  "preloading": {
                    "sdkRoot": {
                      "className": "ads_mobile_sdk.gt0",
                      "method": "a",
                      "providerField": "P0",
                      "providerGetMethod": "get"
                    },
                    "formatMapFields": {
                      "APP_OPEN": "y",
                      "INTERSTITIAL": "A",
                      "REWARDED": "C"
                    },
                    "queuePath": {
                      "managerQueueField": "B",
                      "queueItemResultField": "a",
                      "preloadResultAdField": "a",
                      "internalAdConfigurationMethod": "b",
                      "configurationPriceField": "m"
                    },
                    "priceFields": { "valueMicros": "b" }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val config = repository.getValue("1.2.1")
        assertEquals("ads_mobile_sdk.gt0", config.sdkRootClass)
        assertEquals("A", config.formatMapFields.getValue("INTERSTITIAL"))
        assertEquals("b", config.priceValueMicrosField)
    }

    @Test
    fun `static 1_2_1 reflection path matches the resolved GMA classes`() {
        assertEquals(
            "ads_mobile_sdk.ht0",
            sdkClass("ads_mobile_sdk.gt0").getDeclaredMethod("a").returnType.name,
        )
        assertEquals(
            "ads_mobile_sdk.vi2",
            sdkClass("ads_mobile_sdk.fb0").getDeclaredField("P0").type.name,
        )
        assertEquals(
            "java.lang.Object",
            sdkClass("ads_mobile_sdk.ui2").getDeclaredMethod("get").returnType.name,
        )
        listOf("y", "A", "C").forEach { fieldName ->
            assertEquals(
                "java.util.LinkedHashMap",
                sdkClass("ads_mobile_sdk.qg2").getDeclaredField(fieldName).type.name,
            )
        }
        assertEquals(
            "java.util.Queue",
            sdkClass("ads_mobile_sdk.d5").getDeclaredField("B").type.name,
        )
        assertEquals(
            "ads_mobile_sdk.ug2",
            sdkClass("ads_mobile_sdk.vg2").getDeclaredField("a").type.name,
        )
        assertEquals(
            "ads_mobile_sdk.h91",
            sdkClass("ads_mobile_sdk.ug2").getDeclaredField("a").type.name,
        )
        assertEquals(
            "ads_mobile_sdk.a2",
            sdkClass("ads_mobile_sdk.h91").getDeclaredMethod("b").returnType.name,
        )
        assertEquals(
            "ads_mobile_sdk.i9",
            sdkClass("ads_mobile_sdk.a2").getDeclaredField("m").type.name,
        )
        assertEquals(
            Long::class.javaPrimitiveType,
            sdkClass("ads_mobile_sdk.i9").getDeclaredField("b").type,
        )
    }

    private fun sdkClass(name: String): Class<*> = Class.forName(name)
}
