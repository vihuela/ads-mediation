@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation.internal

import android.os.Looper
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnRevenueTracker
import com.thinkup.core.api.TUAdConst
import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.AdError
import com.thinkup.rewardvideo.api.TURewardVideoListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TopOnShowFailureSourceTest {
    @Test fun `late close and play failed callbacks cannot supply the next show's failure source`() {
        for (id in listOf(null, "", "   ", "unrecognized-id", "old-id")) {
            for (close in listOf(false, true)) {
                for (confirmedSource in listOf(null, "current-network")) {
                    isolated {
                        val (_, current) = nextShow()
                        if (confirmedSource != null) {
                            confirm(current, "current-id")
                            listener.onRewardedVideoAdPlayClicked(info("current-id", confirmedSource))
                            drain()
                        }
                        val info = info(id, "late-network")
                        if (close) listener.onRewardedVideoAdClosed(info)
                        else listener.onRewardedVideoAdPlayFailed(ShowError(), info)
                        drain()
                        if (!current.hasTerminalEvent) fail(current)
                        assertFailureSource(current, confirmedSource)
                        assertFalse(current.hasImpression)
                    }
                }
            }
        }
    }

    @Test fun `play failed callback with null info preserves unknown or confirmed current source`() {
        for (confirmedSource in listOf(null, "current-network")) {
            isolated {
                val (_, current) = nextShow()
                if (confirmedSource != null) {
                    confirm(current, "current-id")
                    listener.onRewardedVideoAdPlayClicked(info("current-id", confirmedSource))
                    drain()
                }
                listener.onRewardedVideoAdPlayFailed(ShowError(), null)
                drain()
                assertFailureSource(current, confirmedSource)
            }
        }
    }

    @Test fun `late clicked and reward callbacks cannot supply the next show's failure source`() {
        for (id in listOf(null, "", "   ", "unrecognized-id", "old-id")) {
            for (reward in listOf(false, true)) {
                isolated {
                    val (old, current) = nextShow()
                    val info = info(id, "late-network")
                    if (reward) listener.onReward(info) else listener.onRewardedVideoAdPlayClicked(info)
                    drain()
                    fail(current)
                    assertFailureSource(current, null)
                    assertFalse(current.hasImpression)
                    if (reward && id == "old-id") {
                        assertEquals(old.sessionId, events.single { it.name == AdEventName.REWARD }.sessionId)
                    }
                }
            }
        }
    }

    @Test fun `valid and invalid late revenue cannot supply the next show's failure source`() {
        for (id in listOf(null, "", "   ", "unrecognized-id", "old-id")) {
            for (amount in listOf(0.25, -1.0, Double.NaN)) {
                isolated {
                    val (old, current) = nextShow()
                    paid(current, info(id, "late-paid-network", amount))
                    fail(current)
                    assertFailureSource(current, null)
                    assertFalse(current.hasImpression)
                    if (amount == 0.25 && id == "old-id") {
                        assertEquals(old.sessionId, revenues.single().sessionId)
                        assertEquals("late-paid-network", revenues.single().adNetwork)
                        assertEquals(250_000L, revenues.single().valueMicros)
                    }
                    if (amount != 0.25 || id != "old-id") assertTrue(revenues.isEmpty())
                }
            }
        }
    }

    @Test fun `known old identity confirms source only on the original session`() {
        for (revenue in listOf(false, true)) {
            isolated {
                val (old, current) = nextShow()
                if (revenue) paid(current, info("old-id", "confirmed-old-network"))
                else listener.onReward(info("old-id", "confirmed-old-network"))
                drain()
                // A was dismissed, so inspect its retained source without fabricating a second terminal event.
                assertEquals(if (revenue) "original-network" else "confirmed-old-network",
                    ReflectionHelpers.getField<String?>(old, "confirmedAdSource"))
                if (revenue) {
                    assertEquals(old.sessionId, revenues.single().sessionId)
                    assertEquals("confirmed-old-network", revenues.single().adNetwork)
                }
                fail(current)
                assertFailureSource(current, null)
            }
        }
    }

    @Test fun `matching current identity can confirm source without an exposure callback`() {
        for (reward in listOf(false, true)) {
            isolated {
                val (_, current) = nextShow()
                confirm(current, "current-id")
                val info = info(" current-id ", "current-network")
                if (reward) listener.onReward(info) else listener.onRewardedVideoAdPlayClicked(info)
                drain()
                assertFalse(current.hasImpression)
                fail(current)
                assertFailureSource(current, "current-network")
            }
        }
    }

    @Test fun `equal business session IDs do not make different show instances interchangeable`() {
        isolated {
            val (old, current) = nextShow(sameBusinessId = true)
            assertEquals(old.sessionId, current.sessionId)
            assertNotSame(old, current)
            listener.onRewardedVideoAdPlayClicked(info("old-id", "late-click-network"))
            listener.onReward(info("old-id", "late-reward-network"))
            drain()
            paid(current, info("old-id", "late-paid-network"))
            assertEquals("late-reward-network", ReflectionHelpers.getField<String?>(old, "confirmedAdSource"))
            assertNull(ReflectionHelpers.getField<String?>(current, "confirmedAdSource"))
            assertEquals("late-paid-network", revenues.single().adNetwork)
            fail(current)
            assertFailureSource(current, null)
        }
    }

    @Test fun `confirmed current source survives every late identity and revenue validity`() {
        for (id in listOf(null, "", "   ", "unrecognized-id", "old-id")) {
            for (amount in listOf(0.25, -1.0)) {
                isolated {
                    val (_, current) = nextShow()
                    confirm(current, "current-id")
                    listener.onRewardedVideoAdPlayClicked(info("current-id", "current-network"))
                    drain()
                    listener.onRewardedVideoAdPlayClicked(info(id, "late-network"))
                    listener.onReward(info(id, "late-network"))
                    drain()
                    paid(current, info(id, "late-paid-network", amount))
                    fail(current)
                    assertFailureSource(current, "current-network")
                }
            }
        }
    }

    @Test fun `valid token revenue stays attributed and deduplicated without changing failure source`() {
        for (id in listOf(null, "", "   ", "unrecognized-id", "current-id")) {
            for (confirmedSource in listOf(null, "current-network")) {
                isolated {
                    val (_, current) = nextShow()
                    val token = tracker.register(current)
                    tokens[current] = token
                    if (id == "current-id") confirm(current, id)
                    // Seed the source independently so an unknown revenue ID remains unmapped.
                    current.recordAdSource(confirmedSource)
                    for (amount in listOf(-1.0, Double.NaN)) {
                        paid(current, TestTopOnAdInfo(id, "invalid-paid-network", amount, token))
                        assertTrue(revenues.isEmpty())
                        assertEquals(confirmedSource,
                            ReflectionHelpers.getField<String?>(current, "confirmedAdSource"))
                    }
                    val paidInfo = TestTopOnAdInfo(id, "paid-network", 0.25, token)
                    paid(current, paidInfo)
                    paid(current, paidInfo)
                    val payload = revenues.single()
                    assertEquals(current.sessionId, payload.sessionId)
                    assertEquals(current.position, payload.position)
                    assertEquals(current.adUnitId, payload.placementId)
                    assertEquals("USD", payload.currencyCode)
                    assertEquals(250_000L, payload.valueMicros)
                    assertEquals("paid-network", payload.adNetwork)
                    assertFalse(current.hasImpression)
                    fail(current)
                    assertFailureSource(current, confirmedSource)
                }
            }
        }
    }

    @Test fun `valid token callbacks need an already mapped show ID to confirm failure source`() {
        for (id in listOf(null, "", "   ", "unrecognized-id", "current-id")) {
            for (callback in listOf("click", "close", "reward", "play-failed")) {
                for (confirmedSource in listOf(null, "current-network")) {
                    isolated {
                        val (_, current) = nextShow()
                        val token = tracker.register(current)
                        tokens[current] = token
                        if (confirmedSource != null) {
                            confirm(current, "current-id")
                            listener.onRewardedVideoAdPlayClicked(
                                TestTopOnAdInfo("current-id", confirmedSource, token = token))
                            drain()
                            assertEquals(confirmedSource,
                                ReflectionHelpers.getField<String?>(current, "confirmedAdSource"))
                        } else if (id == "current-id") {
                            confirm(current, id)
                        }
                        val callbackInfo = TestTopOnAdInfo(id, "callback-network", token = token)
                        when (callback) {
                            "click" -> listener.onRewardedVideoAdPlayClicked(callbackInfo)
                            "close" -> listener.onRewardedVideoAdClosed(callbackInfo)
                            "reward" -> listener.onReward(callbackInfo)
                            "play-failed" -> listener.onRewardedVideoAdPlayFailed(ShowError(), callbackInfo)
                        }
                        drain()
                        val expected = if (id == "current-id") "callback-network" else confirmedSource
                        assertEquals(expected,
                            ReflectionHelpers.getField<String?>(current, "confirmedAdSource"))
                        assertFalse(current.hasImpression)
                        if (!current.hasTerminalEvent) fail(current)
                        assertFailureSource(current, expected)
                    }
                }
            }
        }
    }

    private val events = mutableListOf<AdEvent>()
    private val revenues = mutableListOf<AdRevenuePayload>()
    private val attempts = mutableListOf<FullScreenShowAttempt>()
    private val listener: TURewardVideoListener
        get() = ReflectionHelpers.getStaticField(TopOnAds::class.java, "rewardedListener")
    private val tracker: TopOnRevenueTracker
        get() = ReflectionHelpers.getStaticField(TopOnAds::class.java, "revenueTracker")
    private val tokens = java.util.IdentityHashMap<AdShowSession, String>()
    private fun confirm(session: AdShowSession, id: String) {
        val token = tokens.getOrPut(session) { tracker.register(session) }
        assertTrue(tracker.confirm(TopOnRevenueTracker.Identity(id, token, session.format, session.adUnitId), session))
    }

    private fun isolated(block: () -> Unit) {
        val fields = listOf("events", "commonConfig", "config", "activeAppOpen", "activeInterstitial",
            "activeRewarded", "revenueTracker").associateWith { name ->
            TopOnAds::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previous = fields.mapValues { (_, field) -> field.get(null) }
        val maps = listOf("showSessionsByImpressionId",
            "pendingRevenueByImpressionId").associateWith { name ->
            ReflectionHelpers.getStaticField<MutableMap<String, Any>>(TopOnAds::class.java, name)
        }
        val previousMaps = maps.mapValues { (_, map) -> map.toMap() }
        val gateFields = listOf("owner", "showing").associateWith { name ->
            FullScreenShowGate::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previousGate = gateFields.mapValues { (_, field) -> field.get(null) }
        try {
            tokens.clear()
            fields.getValue("revenueTracker").set(null, TopOnRevenueTracker())
            events.clear()
            revenues.clear()
            maps.values.forEach { it.clear() }
            gateFields.getValue("owner").set(null, null)
            gateFields.getValue("showing").set(null, false)
            for (name in listOf("activeAppOpen", "activeInterstitial", "activeRewarded")) {
                fields.getValue(name).set(null, null)
            }
            // Disabled placements prevent callback cleanup from starting SDK loads.
            val provider = TopOnProviderConfig(TopOnIds("test-app", "test-key"))
            fields.getValue("config").set(null, provider)
            fields.getValue("commonConfig").set(null, AdsConfig(provider,
                revenueListener = AdRevenueListener(revenues::add)))
            fields.getValue("events").set(null, AdEventDispatcher(RuntimeEnvironment.getApplication(),
                AdPlatform.TOPON, AdMediationMode.TOPON, AdEventListener(events::add), false, "TopOnSourceTest"))
            block()
        } finally {
            attempts.forEach { it.complete() }
            attempts.clear()
            val handler = ReflectionHelpers.getStaticField<android.os.Handler>(TopOnAds::class.java, "mainHandler")
            maps.getValue("pendingRevenueByImpressionId").values.forEach {
                handler.removeCallbacks(ReflectionHelpers.getField(it, "timeout"))
            }
            fields.forEach { (name, field) -> field.set(null, previous[name]) }
            maps.forEach { (name, map) -> map.clear(); map.putAll(previousMaps.getValue(name)) }
            gateFields.forEach { (name, field) -> field.set(null, previousGate[name]) }
        }
    }

    private fun nextShow(sameBusinessId: Boolean = false): Pair<AdShowSession, AdShowSession> {
        val old = session("old-session")
        activate(old)
        confirm(old, "old-id")
        listener.onRewardedVideoAdPlayStart(info("old-id", "original-network"))
        drain()
        listener.onRewardedVideoAdClosed(info("old-id", "original-network"))
        drain()
        assertTrue(old.attempt.isCommitted)
        assertEquals(old.sessionId, events.single { it.name == AdEventName.DISMISS }.sessionId)
        val current = session(if (sameBusinessId) old.sessionId else "current-session")
        activate(current)
        events.clear()
        return old to current
    }

    private fun session(id: String): AdShowSession = AdShowSession(AdEventListener(events::add),
        AdPlatform.TOPON, AdMediationMode.TOPON, AdFormat.REWARDED, "position", "unit", id, 1).also {
        attempts += it.attempt
        assertTrue(it.admit())
        assertNull(FullScreenShowGate.reserve(it.attempt))
        assertNull(FullScreenShowGate.commit(it.attempt))
    }

    private fun activate(session: AdShowSession) {
        val type = TopOnAds::class.java.declaredClasses.single { it.simpleName == "ActiveRewardedShow" }
        val constructor = type.getDeclaredConstructor(AdShowSession::class.java,
            Function1::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val callback: (AdRewardResult) -> Unit = {}
        ReflectionHelpers.setStaticField(TopOnAds::class.java, "activeRewarded",
            constructor.newInstance(session, callback, false, true))
    }

    private fun paid(current: AdShowSession, info: TUAdInfo) {
        ReflectionHelpers.callInstanceMethod<Any?>(TopOnAds, "revenuePaid",
            ReflectionHelpers.ClassParameter.from(TUAdInfo::class.java, info),
            ReflectionHelpers.ClassParameter.from(AdFormat::class.java, AdFormat.REWARDED))
        drain()
    }

    private fun fail(current: AdShowSession) {
        TopOnAds::class.java.getDeclaredMethod("finishRewardedFailed", String::class.java,
            String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(TopOnAds, "show_failed", "test", "ad_error", null)
        drain()
        assertTrue(current.hasTerminalEvent)
    }

    private fun assertFailureSource(session: AdShowSession, source: String?) {
        val failure = events.single { it.name == AdEventName.SHOW_FAIL && it.sessionId == session.sessionId }
        assertEquals(source, failure.adSource)
        assertEquals(source ?: "unknow", failure.analyticsParameters()["ad_source"])
    }

    private fun drain() { shadowOf(Looper.getMainLooper()).idle() }

    private fun info(id: String?, source: String, revenue: Double = 0.25): TUAdInfo =
        TestTopOnAdInfo(id, source, revenue)

    private class ShowError : AdError("test", "show_failed", "", "")

}
