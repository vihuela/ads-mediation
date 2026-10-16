package com.cashcraft.ads.mediation

import org.junit.Assert.*
import org.junit.Test

class AdRevenueTest {
    @Test fun `old lambda and exhaustive sealed when remain compatible`() {
        val received = mutableListOf<AdRevenuePayload>()
        val listener = AdRevenueListener { received += it }
        val topOn = attributed()
        val adMob = AdMobRevenuePayload(
            "admob:response", 1L, AdMediationMode.ADMOB, AdFormat.INTERSTITIAL,
            "session", "position", "unit", 0L, "USD", null, null, null, null,
        )

        listener.onRevenuePaid(topOn)
        listener.onRevenuePaid(adMob)
        AdRevenueListener.NONE.onRevenuePaid(topOn)

        assertEquals(listOf(topOn, adMob), received)
        assertEquals(listOf(AdPlatform.TOPON, AdPlatform.ADMOB), received.map { payload ->
            when (payload) {
                is AdMobRevenuePayload -> payload.platform
                is TopOnRevenuePayload -> payload.platform
            }
        })
    }

    @Test fun `extended listener is assignable to legacy listener and receives both payloads`() {
        val receivedAttributed = mutableListOf<AdRevenuePayload>()
        val receivedUnattributed = mutableListOf<UnattributedTopOnRevenuePayload>()
        val listener = object : AdRevenueListenerWithUnattributed {
            override fun onRevenuePaid(payload: AdRevenuePayload) { receivedAttributed += payload }
            override fun onUnattributedRevenuePaid(payload: UnattributedTopOnRevenuePayload) {
                receivedUnattributed += payload
            }
        }
        val legacy: AdRevenueListener = listener
        val known = attributed()
        val unknown = unattributed()

        legacy.onRevenuePaid(known)
        (legacy as AdRevenueListenerWithUnattributed).onUnattributedRevenuePaid(unknown)

        assertEquals(listOf(known), receivedAttributed)
        assertEquals(listOf(unknown), receivedUnattributed)
    }

    @Test fun `unattributed payload retains revenue without inventing session identity`() {
        for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
            val payload = unattributed().copy(format = format)
            assertEquals(AdPlatform.TOPON, payload.platform)
            assertEquals(AdMediationMode.TOPON, payload.mediationMode)
            assertEquals(format, payload.format)
            assertEquals("topon:local-show-token", payload.eventId)
            assertEquals(1L, payload.occurredAtMillis)
            assertEquals("placement", payload.placementId)
            assertEquals(1_250L, payload.valueMicros)
            assertEquals("USD", payload.currencyCode)
            assertNull(payload.adNetwork)
            assertNull(payload.impressionId)
            assertNull(payload.precisionType)
            assertSame(adInfo, payload.adInfo)
        }
        assertFalse(AdRevenuePayload::class.java.isAssignableFrom(UnattributedTopOnRevenuePayload::class.java))
        val fields = UnattributedTopOnRevenuePayload::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("sessionId"))
        assertFalse(fields.contains("position"))
    }

    @Test fun `unattributed payload validates basic fields on construction and copy`() {
        val valid = unattributed()
        val invalid = listOf<() -> UnattributedTopOnRevenuePayload>(
            { valid.copy(eventId = "") },
            { valid.copy(eventId = " \t") },
            { valid.copy(occurredAtMillis = 0L) },
            { valid.copy(occurredAtMillis = -1L) },
            { valid.copy(placementId = "") },
            { valid.copy(placementId = " \t") },
            { valid.copy(valueMicros = -1L) },
            { valid.copy(currencyCode = "") },
            { valid.copy(currencyCode = " \t") },
        )
        invalid.forEach { create ->
            assertTrue(runCatching(create).exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0L, valid.copy(valueMicros = 0L).valueMicros)
        assertEquals(Long.MAX_VALUE, valid.copy(valueMicros = Long.MAX_VALUE).valueMicros)
    }

    private val adInfo = Any()

    private fun unattributed() = UnattributedTopOnRevenuePayload(
        "topon:local-show-token", 1L, AdMediationMode.TOPON, AdFormat.INTERSTITIAL,
        "placement", 1_250L, "USD", null, null, null, adInfo,
    )

    private fun attributed() = TopOnRevenuePayload(
        "topon:show", 1L, AdMediationMode.TOPON, AdFormat.INTERSTITIAL,
        "session", "position", "placement", 1_250L, "USD", null, "show", null, adInfo,
    )
}
