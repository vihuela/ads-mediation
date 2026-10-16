@file:Suppress("DEPRECATION")

package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.Handler
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnRevenueTracker
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.TUShowConfig
import com.thinkup.interstitial.api.TUInterstitialListener
import com.thinkup.rewardvideo.api.TURewardVideoListener
import com.thinkup.splashad.api.TUSplashAdEZListener
import com.thinkup.splashad.api.TUSplashAdExtraInfo
import com.thinkup.splashad.api.TUSplashAd
import com.thinkup.splashad.api.TUSplashSkipInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers
import java.lang.ref.WeakReference
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ShadowTopOnSplashAd::class])
class TopOnRevenueIsolationTest {
    @Test fun `A closes B starts then A token-only revenue and duplicates never consume B payment`() {
        formats.forEach { format -> isolated {
            val (a, tokenA) = start(format, "A")
            show(a, TestTopOnAdInfo("A-id", "A-network", token = tokenA))
            close(format)
            val (b, tokenB) = start(format, "B")
            events.clear()
            paid(format, TestTopOnAdInfo(null, "A-network", token = tokenA))
            paid(format, TestTopOnAdInfo("A-id", "A-network", token = tokenA))
            paid(format, TestTopOnAdInfo("A-id", "A-network"))
            assertEquals(listOf("A"), revenues.map { it.sessionId })
            assertFalse(isPaid(b))
            assertFalse(events.any { it.sessionId == "B" && it.name == AdEventName.IMPRESSION })
            paid(format, TestTopOnAdInfo(null, "B-network", token = tokenB))
            paid(format, TestTopOnAdInfo("B-id", "B-network", token = tokenB))
            assertEquals(listOf("A", "B"), revenues.map { it.sessionId })
            assertEquals(2, revenues.map { it.eventId }.distinct().size)
            assertTrue(unknown.isEmpty())
        } }
    }

    @Test fun `unidentified late A revenue preserves amount without consuming B or inventing impression`() {
        formats.forEach { format -> isolated {
            start(format, "A")
            close(format)
            val (b, tokenB) = start(format, "B")
            events.clear()
            for (id in listOf(null, "", "  ")) paid(format, TestTopOnAdInfo(id, "A-network"))
            assertEquals(3, unknown.size)
            assertEquals(3, unknown.map { it.eventId }.distinct().size)
            assertTrue(unknown.all { it.valueMicros == 250_000L && it.format == format })
            assertFalse(isPaid(b))
            assertTrue(events.isEmpty())
            paid(format, TestTopOnAdInfo(null, "B-network", token = tokenB))
            assertEquals("B", revenues.single().sessionId)
        } }
    }

    @Test fun `revenue before show callback is attributed by pre-registered token and aliased once`() {
        formats.forEach { format -> isolated {
            val (a, token) = start(format, "A")
            paid(format, TestTopOnAdInfo(null, "network", token = token))
            show(a, TestTopOnAdInfo("id", "network", token = token))
            paid(format, TestTopOnAdInfo("id", "network"))
            assertEquals(1, revenues.size)
            assertEquals("A", revenues.single().sessionId)
            assertEquals("topon:${format.analyticsValue}:4:unit:show:$token", revenues.single().eventId)
        } }
    }

    @Test fun `showId-only revenue waits for token-confirmed show then flushes once`() {
        formats.forEach { format -> isolated {
            val (a, token) = start(format, "A")
            paid(format, TestTopOnAdInfo("id", "network"))
            paid(format, TestTopOnAdInfo("id", "network"))
            assertTrue(revenues.isEmpty())
            show(a, TestTopOnAdInfo("id", "network", token = token))
            advanceTimeout()
            assertEquals(1, revenues.size)
            assertTrue(unknown.isEmpty())
        } }
    }

    @Test fun `an entire tokenless show never attributes revenue with either listener or callback order`() {
        for (format in formats) for (legacyListener in listOf(false, true)) {
            for (revenueFirst in listOf(false, true)) isolated {
                if (legacyListener) {
                    val config = ReflectionHelpers.getStaticField<AdsConfig>(TopOnAds::class.java, "commonConfig")
                    ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig",
                        config.copy(revenueListener = AdRevenueListener(revenues::add)))
                }
                val (a, _) = start(format, "A")
                val info = TestTopOnAdInfo("A-id", "A-network")
                if (revenueFirst) paid(format, info)
                show(a, info)
                if (!revenueFirst) paid(format, info)
                assertFalse(a.hasImpression)
                assertTrue(events.none { it.name == AdEventName.SHOW })
                assertTrue(revenues.isEmpty())
                assertTrue(unknown.isEmpty())
                assertFalse(isPaid(a))
                close(format)
                advanceTimeout()
                assertTrue(revenues.isEmpty())
                assertFalse(isPaid(a))
                assertTrue(events.none { it.name == AdEventName.IMPRESSION })
                if (legacyListener) {
                    assertTrue(unknown.isEmpty())
                } else {
                    val orphan = unknown.single()
                    assertEquals("A-id", orphan.impressionId)
                    assertEquals(format, orphan.format)
                    assertEquals("unit", orphan.placementId)
                    assertEquals(250_000L, orphan.valueMicros)
                    assertEquals("A-network", orphan.adNetwork)
                }
            }
        }
    }

    @Test fun `unknown showId timeout or eviction delivers once even if later token proves ownership`() {
        isolated {
            val (a, token) = start(AdFormat.REWARDED, "A")
            paid(a.format, TestTopOnAdInfo("id", "network"))
            advanceTimeout()
            assertEquals(1, unknown.size)
            show(a, TestTopOnAdInfo("id", "network", token = token))
            paid(a.format, TestTopOnAdInfo(null, "network", token = token))
            paid(a.format, TestTopOnAdInfo("id", "network"))
            advanceTimeout()
            assertEquals(1, unknown.size)
            assertTrue(revenues.isEmpty())
        }
        isolated {
            repeat(33) { paid(AdFormat.REWARDED, TestTopOnAdInfo("orphan-$it", "network")) }
            assertEquals(1, unknown.size)
            advanceTimeout()
            assertEquals(33, unknown.size)
            assertTrue(revenues.isEmpty())
        }
    }

    @Test fun `late show callback cannot remap A identity onto B`() {
        formats.forEach { format -> isolated {
            val (_, tokenA) = start(format, "A")
            close(format)
            val (b, tokenB) = start(format, "B")
            events.clear()
            show(b, TestTopOnAdInfo("late-A-id", "A-network", token = tokenA))
            assertFalse(b.hasImpression)
            paid(format, TestTopOnAdInfo("late-A-id", "A-network"))
            assertEquals("A", revenues.single().sessionId)
            assertFalse(isPaid(b))
            paid(format, TestTopOnAdInfo(null, "B-network", token = tokenB))
            assertEquals(listOf("A", "B"), revenues.map { it.sessionId })
        } }
    }

    @Test fun `late tokenless A show and revenue cannot bind to B before or after B show confirmation`() {
        for (format in formats) for (bShown in listOf(false, true)) isolated {
            start(format, "A")
            close(format)
            val (b, tokenB) = start(format, "B")
            val bInfo = TestTopOnAdInfo("B-id", "B-network", amount = 0.5, token = tokenB)
            if (bShown) show(b, bInfo)
            val lateA = TestTopOnAdInfo("late-A-id", "A-network")
            show(b, lateA)
            val tracker = ReflectionHelpers.getStaticField<TopOnRevenueTracker>(TopOnAds::class.java, "revenueTracker")
            assertNull(tracker.session(TopOnRevenueTracker.Identity("late-A-id", null, format, "unit")))
            val shown = events.filter { it.name == AdEventName.SHOW }
            assertEquals(if (bShown) 1 else 0, shown.size)
            if (bShown) assertEquals("B-network", shown.single().adSource)
            assertEquals(bShown, b.hasImpression)
            assertFalse(ReflectionHelpers.getStaticField<Map<String, String>>(TopOnAds::class.java,
                "showSessionsByImpressionId").containsKey("late-A-id"))
            events.clear()
            paid(format, lateA)
            assertTrue(revenues.isEmpty())
            assertTrue(unknown.isEmpty())
            assertFalse(isPaid(b))
            advanceTimeout()
            assertTrue(revenues.isEmpty())
            assertFalse(isPaid(b))
            assertTrue(events.isEmpty())
            val orphan = unknown.single()
            assertEquals("late-A-id", orphan.impressionId)
            assertEquals(format, orphan.format)
            assertEquals("unit", orphan.placementId)
            assertEquals(250_000L, orphan.valueMicros)
            assertEquals("A-network", orphan.adNetwork)
            show(b, bInfo)
            assertEquals(if (bShown) 0 else 1, events.count { it.name == AdEventName.SHOW })
            assertTrue(events.filter { it.name == AdEventName.SHOW }.all { it.adSource == "B-network" })
            paid(format, TestTopOnAdInfo("B-id", "B-network", amount = 0.5))
            paid(format, lateA)
            advanceTimeout()
            assertEquals(listOf("B"), revenues.map { it.sessionId })
            assertEquals(500_000L, revenues.single().valueMicros)
            assertEquals("B-id", revenues.single().impressionId)
            assertEquals("B-network", revenues.single().adNetwork)
            assertTrue(isPaid(b))
            assertEquals(1, unknown.size)
        }
    }

    @Test fun `a tokenless show accepts an id confirmed by revenue without revenue fabricating a show`() {
        formats.forEach { format -> isolated {
            val (a, token) = start(format, "A")
            val tokenless = TestTopOnAdInfo("A-id", "A-network")
            show(a, tokenless)
            assertFalse(a.hasImpression)
            paid(format, TestTopOnAdInfo("A-id", "A-network", token = token))
            assertEquals("A", revenues.single().sessionId)
            assertFalse(a.hasImpression)
            assertTrue(events.none { it.name == AdEventName.SHOW })
            repeat(2) { show(a, tokenless) }
            assertTrue(a.hasImpression)
            assertEquals(1, events.count { it.name == AdEventName.SHOW })
            assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
            assertEquals(1, revenues.size)
            assertTrue(unknown.isEmpty())
        } }
    }

    @Test fun `tokenless close preserves settlement and releases the gate with or without confirmed exposure`() {
        for (format in formats) for (confirmed in listOf(false, true)) isolated {
            val (a, token) = start(format, "A")
            show(a, TestTopOnAdInfo("A-id", "A-network", token = token.takeIf { confirmed }))
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            dismiss(a, TestTopOnAdInfo("A-id", "A-network"))
            val expected = if (confirmed) AdShowResult.Dismissed
                else AdShowResult.Failed("dismissed_before_impression")
            val result = results.single()
            assertEquals(expected, if (result is AdRewardResult) result.showResult else result)
            if (result is AdRewardResult) assertFalse(result.rewardEarned)
            assertEquals(if (confirmed) 1 else 0, events.count { it.name == AdEventName.DISMISS })
            assertEquals(if (confirmed) 0 else 1, events.count { it.name == AdEventName.SHOW_FAIL })
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            assertNull(ReflectionHelpers.getStaticField<Any?>(FullScreenShowGate::class.java, "owner"))
            assertNull(ReflectionHelpers.getStaticField<Any?>(TopOnAds::class.java, activeField(format)))
            dismiss(a, TestTopOnAdInfo("A-id", "A-network"))
            show(a, TestTopOnAdInfo("A-id", "A-network", token = token))
            assertEquals(1, results.size)
            assertEquals(if (confirmed) 1 else 0, events.count { it.name == AdEventName.SHOW })
            start(format, "B")
        }
    }

    @Test fun `tokenless reward keeps earned status independently of exposure confirmation and close result`() {
        for (confirmed in listOf(false, true)) isolated {
            val (a, token) = start(AdFormat.REWARDED, "A")
            show(a, TestTopOnAdInfo("A-id", "A-network", token = token.takeIf { confirmed }))
            val listener = ReflectionHelpers.getStaticField<TURewardVideoListener>(TopOnAds::class.java,
                "rewardedListener")
            repeat(2) { listener.onReward(TestTopOnAdInfo("A-id", "A-network")) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, events.count { it.name == AdEventName.REWARD })
            assertEquals(confirmed, a.hasImpression)
            assertTrue(results.isEmpty())
            dismiss(a, TestTopOnAdInfo("A-id", "A-network"))
            val result = results.single() as AdRewardResult
            assertTrue(result.rewardEarned)
            assertEquals("A", result.sessionId)
            assertEquals(if (confirmed) AdShowResult.Dismissed
                else AdShowResult.Failed("dismissed_before_impression"), result.showResult)
            listener.onReward(TestTopOnAdInfo("A-id", "A-network"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, events.count { it.name == AdEventName.REWARD })
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            start(AdFormat.REWARDED, "B")
        }
    }

    @Test fun `unidentified shows cannot claim exposure while a token without showId remains sufficient`() {
        for (format in formats) for (id in listOf(null, "", "  ", "unknown-id")) isolated {
            val (a, token) = start(format, "A")
            show(a, TestTopOnAdInfo(id, "unidentified-network"))
            assertFalse(a.hasImpression)
            assertFalse(a.hasTerminalEvent)
            assertTrue(events.none { it.name == AdEventName.SHOW || it.name == AdEventName.IMPRESSION })
            assertTrue(ReflectionHelpers.getStaticField<Map<String, String>>(TopOnAds::class.java,
                "showSessionsByImpressionId").isEmpty())
            show(a, TestTopOnAdInfo(null, "A-network", token = token))
            assertTrue(a.hasImpression)
            assertEquals("A-network", events.single { it.name == AdEventName.SHOW }.adSource)
        }
    }

    @Test fun `unconfirmed splash times out at two seconds releases its container and gate and retains revenue`() {
        isolated { withSplash { session, token, container ->
            show(session, TestTopOnAdInfo("A-id", "unidentified-network"))
            assertFalse(session.hasImpression)
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
            assertTrue(results.isEmpty())
            assertNotNull(container.parent)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(listOf(AdShowResult.Failed("show_callback_timeout")), results)
            assertEquals("show_callback_timeout", events.single { it.name == AdEventName.SHOW_FAIL }.reason)
            assertNull(container.parent)
            assertNull(ReflectionHelpers.getStaticField<Any?>(TopOnAds::class.java, "activeAppOpen"))
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            assertNull(ReflectionHelpers.getStaticField<Any?>(FullScreenShowGate::class.java, "owner"))
            show(session, TestTopOnAdInfo("A-id", "A-network", token = token))
            dismiss(session, TestTopOnAdInfo("A-id", "A-network"))
            paid(session.format, TestTopOnAdInfo("A-id", "A-network", token = token))
            assertEquals(session.sessionId, revenues.single().sessionId)
            assertEquals(250_000L, revenues.single().valueMicros)
            assertTrue(events.none { it.name == AdEventName.SHOW || it.name == AdEventName.IMPRESSION })
            assertEquals(1, results.size)
            start(AdFormat.APP_OPEN, "B")
        } }
    }

    @Test fun `confirmed splash survives the old timeout after normal or initially unidentified exposure`() {
        for (unidentifiedFirst in listOf(false, true)) isolated { withSplash { session, token, container ->
            if (unidentifiedFirst) show(session, TestTopOnAdInfo("unknown-id", "unidentified-network"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            show(session, TestTopOnAdInfo("A-id", "A-network", token = token))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001))
            assertTrue(results.isEmpty())
            assertTrue(FullScreenShowGate.isAnyAdShowing)
            assertNotNull(container.parent)
            assertEquals(1, events.count { it.name == AdEventName.SHOW })
            assertEquals(1, events.count { it.name == AdEventName.IMPRESSION })
            assertTrue(events.none { it.name == AdEventName.SHOW_FAIL })
            dismiss(session, TestTopOnAdInfo("A-id", "A-network"))
            assertEquals(listOf(AdShowResult.Dismissed), results)
            assertNull(container.parent)
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            assertNull(ReflectionHelpers.getStaticField<Any?>(FullScreenShowGate::class.java, "owner"))
        } }
    }

    @Test fun `unconfirmed splash close settles before timeout and does not settle twice`() {
        isolated { withSplash { session, _, container ->
            show(session, TestTopOnAdInfo("A-id", "unidentified-network"))
            dismiss(session, TestTopOnAdInfo("A-id", "unidentified-network"))
            assertEquals(listOf(AdShowResult.Failed("dismissed_before_impression")), results)
            assertNull(container.parent)
            assertFalse(FullScreenShowGate.isAnyAdShowing)
            assertNull(ReflectionHelpers.getStaticField<Any?>(FullScreenShowGate::class.java, "owner"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_001))
            assertEquals(1, results.size)
            assertEquals(1, events.count { it.name == AdEventName.SHOW_FAIL })
            assertTrue(events.none { it.name == AdEventName.SHOW || it.name == AdEventName.DISMISS })
        } }
    }

    @Test fun `conflicting identities or wrong placement and format cannot consume valid owners`() {
        isolated {
            val (a, tokenA) = start(AdFormat.REWARDED, "A")
            show(a, TestTopOnAdInfo("A-id", "network", token = tokenA))
            close(a.format)
            val (b, tokenB) = start(AdFormat.REWARDED, "B")
            paid(a.format, TestTopOnAdInfo("A-id", "network", token = tokenB))
            paid(a.format, TestTopOnAdInfo("A-id", "network", token = tokenB))
            paid(a.format, TestTopOnAdInfo(null, "network", token = tokenB, placement = "wrong-unit"))
            paid(AdFormat.INTERSTITIAL, TestTopOnAdInfo(null, "network", token = tokenB))
            paid(a.format, TestTopOnAdInfo("A-id", "network", token = "null"))
            assertEquals(4, unknown.size)
            assertFalse(isPaid(a))
            assertFalse(isPaid(b))
            paid(a.format, TestTopOnAdInfo("A-id", "network"))
            paid(b.format, TestTopOnAdInfo(null, "network", token = tokenB))
            assertEquals(listOf("A", "B"), revenues.map { it.sessionId })
        }
    }

    @Test fun `invalid revenue does not consume token zero is accepted and unknown works without active show`() {
        isolated {
            val (a, token) = start(AdFormat.REWARDED, "A")
            for (amount in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
                paid(a.format, TestTopOnAdInfo(null, "network", amount, token))
            }
            assertFalse(isPaid(a))
            assertTrue(revenues.isEmpty())
            paid(a.format, TestTopOnAdInfo(null, "network", 0.0, token))
            assertEquals(0L, revenues.single().valueMicros)
            close(a.format)
            paid(a.format, TestTopOnAdInfo(null, "network"))
            assertEquals(250_000L, unknown.single().valueMicros)
        }
    }

    @Test fun `repeated business session IDs have distinct show tokens and payment ids`() {
        isolated {
            val (_, tokenA) = start(AdFormat.REWARDED, "same")
            close(AdFormat.REWARDED)
            val (_, tokenB) = start(AdFormat.REWARDED, "same")
            assertNotEquals(tokenA, tokenB)
            paid(AdFormat.REWARDED, TestTopOnAdInfo(null, "network", token = tokenA))
            paid(AdFormat.REWARDED, TestTopOnAdInfo(null, "network", token = tokenB))
            assertEquals(2, revenues.size)
            assertNotEquals(revenues[0].eventId, revenues[1].eventId)
        }
    }

    @Test fun `legacy lambda accepts confirmed payments and ignores unknown without blocking next payment`() {
        isolated {
            val config = ReflectionHelpers.getStaticField<AdsConfig>(TopOnAds::class.java, "commonConfig")
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig",
                config.copy(revenueListener = AdRevenueListener(revenues::add)))
            val (b, token) = start(AdFormat.REWARDED, "B")
            paid(b.format, TestTopOnAdInfo(null, "unknown-network"))
            assertFalse(isPaid(b))
            paid(b.format, TestTopOnAdInfo(null, "B-network", token = token))
            assertEquals("B", revenues.single().sessionId)
        }
    }

    @Test fun `unconsumed legacy orphan can still deliver after ownership is confirmed`() {
        isolated {
            val config = ReflectionHelpers.getStaticField<AdsConfig>(TopOnAds::class.java, "commonConfig")
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig",
                config.copy(revenueListener = AdRevenueListener(revenues::add)))
            val (a, token) = start(AdFormat.REWARDED, "A")
            paid(a.format, TestTopOnAdInfo("id", "network"))
            advanceTimeout()
            show(a, TestTopOnAdInfo("id", "network", token = token))
            paid(a.format, TestTopOnAdInfo("id", "network"))
            assertEquals("A", revenues.single().sessionId)
        }
    }

    @Test fun `evicted owner becomes unattributed but previously paid token remains deduplicated`() {
        for (alreadyPaid in listOf(false, true)) isolated {
            val (a, token) = start(AdFormat.REWARDED, "A")
            if (alreadyPaid) paid(a.format, TestTopOnAdInfo(null, "A-network", token = token))
            close(a.format)
            repeat(TopOnRevenueTracker.MAX_SHOWS) {
                start(a.format, "next-$it")
                close(a.format)
            }
            paid(a.format, TestTopOnAdInfo(null, "A-network", token = token))
            paid(a.format, TestTopOnAdInfo(null, "A-network", token = token))
            assertEquals(if (alreadyPaid) 1 else 0, revenues.size)
            assertEquals(if (alreadyPaid) 0 else 1, unknown.size)
        }
    }

    @Test fun `throwing unknown listener cannot break subsequent confirmed payment`() {
        isolated {
            val config = ReflectionHelpers.getStaticField<AdsConfig>(TopOnAds::class.java, "commonConfig")
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig",
                config.copy(revenueListener = object : AdRevenueListenerWithUnattributed {
                    override fun onRevenuePaid(payload: AdRevenuePayload) { revenues += payload }
                    override fun onUnattributedRevenuePaid(payload: UnattributedTopOnRevenuePayload) {
                        error("host failure")
                    }
                }))
            val (b, token) = start(AdFormat.REWARDED, "B")
            paid(b.format, TestTopOnAdInfo(null, "unknown-network"))
            assertFalse(isPaid(b))
            paid(b.format, TestTopOnAdInfo(null, "B-network", token = token))
            assertEquals("B", revenues.single().sessionId)
        }
    }

    @Test fun `orphan ids in different formats or placements keep distinct payment keys`() {
        isolated {
            val first = TestTopOnAdInfo("shared-id", "network")
            paid(AdFormat.REWARDED, first)
            paid(AdFormat.INTERSTITIAL, TestTopOnAdInfo("shared-id", "network"))
            paid(AdFormat.REWARDED, TestTopOnAdInfo("shared-id", "network", placement = "other-unit"))
            advanceTimeout()
            assertEquals(3, unknown.map { it.eventId }.distinct().size)
            paid(AdFormat.REWARDED, first)
            advanceTimeout()
            assertEquals(3, unknown.size)
        }
    }

    @Test fun `already delivered orphan duplicates cannot evict a fresh pending payment`() {
        isolated {
            repeat(32) { paid(AdFormat.REWARDED, TestTopOnAdInfo("orphan-$it", "network")) }
            advanceTimeout()
            val (a, token) = start(AdFormat.REWARDED, "A")
            paid(a.format, TestTopOnAdInfo("new-id", "network"))
            repeat(32) { paid(a.format, TestTopOnAdInfo("orphan-$it", "network")) }
            assertEquals(32, unknown.size)
            show(a, TestTopOnAdInfo("new-id", "network", token = token))
            assertEquals("A", revenues.single().sessionId)
        }
    }

    @Test fun `a paid conflicting identity stays quarantined after its owners are evicted`() {
        isolated {
            val (a, tokenA) = start(AdFormat.REWARDED, "A")
            show(a, TestTopOnAdInfo("A-id", "network", token = tokenA))
            close(a.format)
            val (b, tokenB) = start(a.format, "B")
            val conflicting = TestTopOnAdInfo("A-id", "network", token = tokenB)
            paid(b.format, conflicting)
            close(b.format)
            repeat(TopOnRevenueTracker.MAX_SHOWS) {
                start(a.format, "next-$it")
                close(a.format)
            }
            paid(a.format, conflicting)
            assertEquals(1, unknown.size)
            assertTrue(revenues.isEmpty())
        }
    }

    @Test fun `conflict cannot bind A id to B after only A is evicted even if its first amount was invalid`() {
        for (amount in listOf(-1.0, 0.25)) isolated {
            val (a, tokenA) = start(AdFormat.REWARDED, "A")
            show(a, TestTopOnAdInfo("A-id", "network", token = tokenA))
            close(a.format)
            val (b, tokenB) = start(a.format, "B")
            paid(b.format, TestTopOnAdInfo("A-id", "network", amount, tokenB))
            close(b.format)
            repeat(TopOnRevenueTracker.MAX_SHOWS - 1) {
                start(a.format, "next-$it")
                close(a.format)
            }
            paid(a.format, TestTopOnAdInfo("A-id", "network", token = tokenB))
            assertFalse(isPaid(b))
            assertEquals(1, unknown.size)
            paid(b.format, TestTopOnAdInfo(null, "network", token = tokenB))
            assertEquals("B", revenues.single().sessionId)
        }
    }

    private val formats = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)
    private val events = mutableListOf<AdEvent>()
    private val revenues = mutableListOf<AdRevenuePayload>()
    private val unknown = mutableListOf<UnattributedTopOnRevenuePayload>()
    private val results = mutableListOf<Any>()
    private val attempts = mutableListOf<FullScreenShowAttempt>()

    private fun isolated(block: () -> Unit) {
        val fields = listOf("events", "commonConfig", "config", "activeAppOpen", "activeInterstitial",
            "activeRewarded", "revenueTracker", "state", "application", "appOpenAd", "splashContainer").associateWith { name ->
            TopOnAds::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previous = fields.mapValues { (_, field) -> field.get(null) }
        val maps = listOf("showSessionsByImpressionId", "pendingRevenueByImpressionId").associateWith {
            ReflectionHelpers.getStaticField<MutableMap<Any, Any>>(TopOnAds::class.java, it)
        }
        val previousMaps = maps.mapValues { (_, map) -> map.toMap() }
        val gateFields = listOf("owner", "showing").associateWith {
            FullScreenShowGate::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
        val previousGate = gateFields.mapValues { (_, field) -> field.get(null) }
        try {
            events.clear(); revenues.clear(); unknown.clear(); results.clear()
            maps.values.forEach { it.clear() }
            gateFields.getValue("owner").set(null, null)
            gateFields.getValue("showing").set(null, false)
            fields.filterKeys { it.startsWith("active") }.values.forEach { it.set(null, null) }
            fields.getValue("revenueTracker").set(null, TopOnRevenueTracker())
            val provider = TopOnProviderConfig(TopOnIds("test-app", "test-key"))
            fields.getValue("config").set(null, provider)
            fields.getValue("commonConfig").set(null, AdsConfig(provider,
                revenueListener = object : AdRevenueListenerWithUnattributed {
                    override fun onRevenuePaid(payload: AdRevenuePayload) { revenues += payload }
                    override fun onUnattributedRevenuePaid(payload: UnattributedTopOnRevenuePayload) { unknown += payload }
                }))
            fields.getValue("events").set(null, AdEventDispatcher(RuntimeEnvironment.getApplication(),
                AdPlatform.TOPON, AdMediationMode.TOPON, AdEventListener(events::add), false, "RevenueTest"))
            block()
        } finally {
            attempts.forEach { it.complete() }; attempts.clear()
            val handler = ReflectionHelpers.getStaticField<Handler>(TopOnAds::class.java, "mainHandler")
            maps.getValue("pendingRevenueByImpressionId").values.forEach {
                handler.removeCallbacks(ReflectionHelpers.getField(it, "timeout"))
            }
            fields.forEach { (name, field) -> field.set(null, previous[name]) }
            maps.forEach { (name, map) -> map.clear(); map.putAll(previousMaps.getValue(name)) }
            gateFields.forEach { (name, field) -> field.set(null, previousGate[name]) }
        }
    }

    private fun start(format: AdFormat, id: String): Pair<AdShowSession, String> {
        val session = AdShowSession(AdEventListener(events::add), AdPlatform.TOPON, AdMediationMode.TOPON,
            format, "position-$id", "unit", id, 1)
        attempts += session.attempt
        assertTrue(session.admit())
        assertNull(FullScreenShowGate.reserve(session.attempt))
        assertNull(FullScreenShowGate.commit(session.attempt))
        val rewarded = format == AdFormat.REWARDED
        val type = TopOnAds::class.java.declaredClasses.single {
            it.simpleName == if (rewarded) "ActiveRewardedShow" else "ActiveShow"
        }
        val constructor = type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        val callback: (Any) -> Unit = { results += it }
        val active = if (rewarded) constructor.newInstance(session, callback, false, true)
            else constructor.newInstance(session, callback, true)
        ReflectionHelpers.setStaticField(TopOnAds::class.java, activeField(format), active)
        val config = ReflectionHelpers.callInstanceMethod<TUShowConfig>(TopOnAds, "showConfig",
            ReflectionHelpers.ClassParameter.from(AdShowSession::class.java, session))
        return session to config.showCustomExt
    }

    private fun withSplash(block: (AdShowSession, String, FrameLayout) -> Unit) {
        val lifecycleFields = listOf("resumedActivity", "isAppInForeground").associateWith {
            ReflectionHelpers.getStaticField<Any?>(AdLifecycleMonitor::class.java, it)
        }
        val adsFields = listOf("policyChecker", "policyUpdatePosted").associateWith {
            ReflectionHelpers.getStaticField<Any?>(Ads::class.java, it)
        }
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        var session: AdShowSession? = null
        try {
            val application = RuntimeEnvironment.getApplication()
            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "resumedActivity", WeakReference(host.get()))
            ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, "isAppInForeground", true)
            ReflectionHelpers.setStaticField(Ads::class.java, "policyChecker", null)
            ReflectionHelpers.setStaticField(Ads::class.java, "policyUpdatePosted", true)
            val provider = TopOnProviderConfig(TopOnIds("test-app", "test-key", appOpenPlacementId = "unit"))
            val config = ReflectionHelpers.getStaticField<AdsConfig>(TopOnAds::class.java, "commonConfig")
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "config", provider)
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "commonConfig", config.copy(provider = provider))
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "application", application)
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "state", TopOnState.READY)
            val listener = ReflectionHelpers.getStaticField<TUSplashAdEZListener>(TopOnAds::class.java, "splashListener")
            ReflectionHelpers.setStaticField(TopOnAds::class.java, "appOpenAd", TUSplashAd(application, "unit", listener, 5_000))
            ShadowTopOnSplashAd.lastConfig = null
            TopOnAds.showAppOpen(host.get(), "position-A") { results += it }
            assertTrue(results.isEmpty())
            val active = ReflectionHelpers.getStaticField<Any>(TopOnAds::class.java, "activeAppOpen")
            val current = ReflectionHelpers.getField<AdShowSession>(active, "session")
            session = current
            attempts += current.attempt
            val container = ReflectionHelpers.getStaticField<FrameLayout>(TopOnAds::class.java, "splashContainer")
            block(current, checkNotNull(ShadowTopOnSplashAd.lastConfig).showCustomExt, container)
        } finally {
            session?.attempt?.complete()
            host.pause().stop().destroy()
            lifecycleFields.forEach { (name, value) -> ReflectionHelpers.setStaticField(AdLifecycleMonitor::class.java, name, value) }
            adsFields.forEach { (name, value) -> ReflectionHelpers.setStaticField(Ads::class.java, name, value) }
        }
    }

    private fun activeField(format: AdFormat): String = when (format) {
        AdFormat.APP_OPEN -> "activeAppOpen"
        AdFormat.INTERSTITIAL -> "activeInterstitial"
        else -> "activeRewarded"
    }

    private fun show(session: AdShowSession, info: TUAdInfo) {
        when (session.format) {
            AdFormat.APP_OPEN -> ReflectionHelpers.getStaticField<TUSplashAdEZListener>(TopOnAds::class.java,
                "splashListener").onAdShow(info)
            AdFormat.INTERSTITIAL -> ReflectionHelpers.getStaticField<TUInterstitialListener>(TopOnAds::class.java,
                "interstitialListener").onInterstitialAdShow(info)
            else -> ReflectionHelpers.getStaticField<TURewardVideoListener>(TopOnAds::class.java,
                "rewardedListener").onRewardedVideoAdPlayStart(info)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun close(format: AdFormat) {
        val method = when (format) {
            AdFormat.APP_OPEN -> "finishAppOpenDismissed"
            AdFormat.INTERSTITIAL -> "finishInterstitialDismissed"
            else -> "finishRewardedDismissed"
        }
        ReflectionHelpers.callInstanceMethod<Any?>(TopOnAds, method)
    }

    private fun dismiss(session: AdShowSession, info: TUAdInfo) {
        when (session.format) {
            AdFormat.APP_OPEN -> ReflectionHelpers.getStaticField<TUSplashAdEZListener>(TopOnAds::class.java,
                "splashListener").onAdDismiss(info, TUSplashAdExtraInfo(0, null))
            AdFormat.INTERSTITIAL -> ReflectionHelpers.getStaticField<TUInterstitialListener>(TopOnAds::class.java,
                "interstitialListener").onInterstitialAdClose(info)
            else -> ReflectionHelpers.getStaticField<TURewardVideoListener>(TopOnAds::class.java,
                "rewardedListener").onRewardedVideoAdClosed(info)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun paid(format: AdFormat, info: TUAdInfo) {
        ReflectionHelpers.callInstanceMethod<Any?>(TopOnAds, "revenuePaid",
            ReflectionHelpers.ClassParameter.from(TUAdInfo::class.java, info),
            ReflectionHelpers.ClassParameter.from(AdFormat::class.java, format))
    }

    private fun isPaid(session: AdShowSession): Boolean =
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(session, "paid").get()
    private fun advanceTimeout() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)) }
}

/** Replaces SDK requests only; provider callbacks and its timeout remain real. */
@Implements(TUSplashAd::class, isInAndroidSdk = false)
class ShadowTopOnSplashAd {
    @Implementation fun __constructor__(context: Context, placement: String, listener: TUSplashAdEZListener, timeout: Int) = Unit
    @Implementation fun isAdReady(): Boolean = true
    @Implementation fun loadAd() = Unit
    @Implementation fun show(activity: Activity, container: ViewGroup, skipInfo: TUSplashSkipInfo?, config: TUShowConfig) {
        lastConfig = config
    }

    companion object {
        var lastConfig: TUShowConfig? = null
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
        @JvmStatic @Resetter fun reset() { lastConfig = null }
    }
}
