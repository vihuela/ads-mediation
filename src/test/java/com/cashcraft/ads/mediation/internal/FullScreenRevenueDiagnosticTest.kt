@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation.internal

import android.os.Bundle
import android.util.Log
import com.cashcraft.ads.mediation.AdEvent
import com.cashcraft.ads.mediation.AdEventListener
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.AdRevenuePayload
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.admob.AdMobConfig
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FullScreenRevenueDiagnosticTest {
    @Test fun `TopOn invalid revenue diagnoses only a confirmed session identity`() {
        val eventField = TopOnAds::class.java.getDeclaredField("events").apply { isAccessible = true }
        val previousEvents = eventField.get(null)
        val events = mutableListOf<AdEvent>()
        val listener = AdEventListener(events::add)
        val current = AdShowSession(listener, AdPlatform.TOPON, AdMediationMode.TOPON,
            AdFormat.APP_OPEN, "position", "unit", "current-session", 1)
        val original = AdShowSession(listener, AdPlatform.TOPON, AdMediationMode.TOPON,
            AdFormat.APP_OPEN, "position", "unit", "original-session", 1)
        val diagnose = TopOnAds::class.java.getDeclaredMethod("invalidRevenue", AdShowSession::class.java,
            AdFormat::class.java, java.lang.Double::class.java).apply { isAccessible = true }
        try {
            eventField.set(null, AdEventDispatcher(RuntimeEnvironment.getApplication(),
                AdPlatform.TOPON, AdMediationMode.TOPON, listener, true, TAG))
            for ((owner, expected) in listOf(current to "current-session", original to "original-session",
                null to null)) {
                for (amount in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
                    ShadowLog.clear()
                    diagnose.invoke(TopOnAds, owner, AdFormat.APP_OPEN, amount)
                    val diagnostic = ShadowLog.getLogsForTag(TAG).single()
                    assertEquals(Log.WARN, diagnostic.type)
                    assertTrue(diagnostic.msg.contains("reason=invalid_revenue platform=topon format=app_open"))
                    assertTrue(diagnostic.msg, diagnostic.msg.contains("session_id=$expected "))
                    assertTrue(events.isEmpty())
                }
            }
        } finally {
            eventField.set(null, previousEvents)
            ShadowLog.clear()
        }
    }

    @Test fun `AdMob invalid full screen payments diagnose without delivery or consuming valid payment`() {
        val events = mutableListOf<AdEvent>()
        val revenues = mutableListOf<AdRevenuePayload>()
        val fields = listOf("events", "config").associateWith { name ->
            AdMobAds::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previous = fields.mapValues { (_, field) -> field.get(null) }
        try {
            fields.getValue("events").set(null, AdEventDispatcher(RuntimeEnvironment.getApplication(),
                AdPlatform.ADMOB, AdMediationMode.ADMOB, AdEventListener(events::add), true, TAG))
            fields.getValue("config").set(null, AdMobConfig(AdMobIds.TEST,
                revenueListener = AdRevenueListener(revenues::add)))
            val response = ResponseInfo(null, "response", Bundle(), null, emptyList())
            for (format in listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)) {
                val session = AdShowSession(AdEventListener(events::add), AdPlatform.ADMOB,
                    AdMediationMode.ADMOB, format, "position", "unit", "session-${format.analyticsValue}", 1)
                session.admit()
                events.clear()
                revenues.clear()
                for ((amount, currency) in listOf(-1L to "USD", 1L to "", 1L to "  ")) {
                    ShadowLog.clear()
                    paid(session, AdValue(PrecisionType.UNKNOWN, amount, currency), response)
                    val diagnostics = ShadowLog.getLogsForTag(TAG).filter { it.msg.contains("reason=invalid_revenue") }
                    assertEquals(1, diagnostics.size)
                    val diagnostic = diagnostics.single()
                    assertEquals(Log.WARN, diagnostic.type)
                    assertTrue(diagnostic.msg.contains("platform=admob"))
                    assertTrue(diagnostic.msg.contains("format=${format.analyticsValue}"))
                    assertTrue(diagnostic.msg.contains("session_id=${session.sessionId} "))
                    assertTrue(events.isEmpty())
                    assertTrue(revenues.isEmpty())
                }
                paid(session, AdValue(PrecisionType.UNKNOWN, 0L, "USD"), response)
                assertEquals(listOf(AdEventName.IMPRESSION), events.map { it.name })
                assertEquals(0L, revenues.single().valueMicros)
                assertEquals(session.sessionId, revenues.single().sessionId)
            }
        } finally {
            fields.forEach { (name, field) -> field.set(null, previous[name]) }
            ShadowLog.clear()
        }
    }

    private fun paid(session: AdShowSession, value: AdValue, response: ResponseInfo) {
        ReflectionHelpers.callInstanceMethod<Any?>(AdMobAds, "paid",
            ReflectionHelpers.ClassParameter.from(AdShowSession::class.java, session),
            ReflectionHelpers.ClassParameter.from(AdValue::class.java, value),
            ReflectionHelpers.ClassParameter.from(ResponseInfo::class.java, response))
    }

    private companion object {
        const val TAG = "FullScreenRevenueDiagnosticTest"
    }
}
