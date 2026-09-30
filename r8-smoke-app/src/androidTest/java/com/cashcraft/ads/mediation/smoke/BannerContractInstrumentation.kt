package com.cashcraft.ads.mediation.smoke

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.cashcraft.ads.mediation.*
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Uses the platform runner, real SDK test ads and public callback getters; no mocking dependency.
 * Injected callback cases test our delivery contract, not actual impressions or earned revenue.
 */
class BannerContractInstrumentation : Instrumentation() {
    private lateinit var activity: BannerContractActivity
    private lateinit var app: BannerContractApplication
    private val events = CopyOnWriteArrayList<AdEvent>()
    private val revenues = CopyOnWriteArrayList<AdRevenuePayload>()
    private val views = mutableListOf<AdsBannerView>()
    private var eventAction: ((AdEvent) -> Unit)? = null
    @Volatile private var callbackFailure: Throwable? = null
    private var passed = 0
    private var testOfflineRecovery = false

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, BannerContractApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testOfflineRecovery = arguments?.getString("offline") == "true"
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            activity = startActivitySync(Intent(targetContext, BannerContractActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BannerContractActivity
            app = activity.application as BannerContractApplication
            main {
                app.onBannerEvent = { events += it; eventAction?.invoke(it) }
                app.onBannerRevenue = { revenues += it }
            }
            await("real window eligibility") { activity.hasWindowFocus() &&
                activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.host.width > 0 }
            case("initialization waiting and owner replacement do not leak requests") { initializationGate() }
            case("real load has listeners before Ready and starts invisibly") { realLoad() }
            case("initial inactive, hidden and zero width gate requests") { gates() }
            case("Loading callback deactivation prevents mount and request") { loadingReentry() }
            case("destroy during actual request cannot revive or affect replacement") { inFlightDestroy() }
            case("deactivation during actual request isolates the restarted generation") { inFlightRestart() }
            case("callback configuration failure releases owned views and permits a new cycle") { configurationFailure() }
            case("expired SDK object is destroyed without clearing the replacement") { expiredObject() }
            case("Ready and global callback exceptions preserve delivery and cleanup") { throwingCallbacks() }
            case("queued known paid survives destruction without reviving UI") { queuedCallbacks() }
            case("interleaved owner, visibility and detach restore one instance") { pauseAndHeight() }
            if (testOfflineRecovery) case("failed instance survives pause and SDK recovery without a local retry") {
                failedPauseRecovery()
            }
        } catch (error: Throwable) { failure = error }
        finally {
            main {
                views.forEach(AdsBannerView::destroy)
                if (::app.isInitialized) { app.onBannerEvent = null; app.onBannerRevenue = null }
                if (::activity.isInitialized) activity.finish()
            }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (failure == null) "\nPASS: $passed Banner device contract cases\n"
                else "\nFAIL after $passed cases: ${failure.stackTraceToString()}\n")
        })
    }

    private fun case(name: String, block: () -> Unit) {
        block()
        main { views.forEach(AdsBannerView::destroy); activity.host.removeAllViews(); eventAction = null }
        waitForIdleSync()
        passed++
        sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
    }

    private fun create(position: String, owner: LifecycleOwner = activity): AdsBannerView = main {
        AdsBannerView(activity, owner, BannerRequest(AdPlatform.ADMOB,
            "ca-app-pub-3940256099942544/9214589741", position, BannerSize.Standard320x50), active = false)
            .also { views += it; activity.host.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
    }

    private fun count(view: AdsBannerView, name: AdEventName) =
        events.count { it.position == "${view.request.position}_banner" && it.name == name }

    private fun start(view: AdsBannerView, state: (BannerState) -> Unit = {}) = main {
        view.onState = state
        view.setActive(true)
    }

    private fun ready(view: AdsBannerView) = await("filled ${view.request.position}") {
        check(events.none { it.position == "${view.request.position}_banner" &&
            it.name == AdEventName.LOAD_RESULT && it.result != "filled" }) { "Test ad failed: ${view.request.position}" }
        count(view, AdEventName.LOAD_RESULT) == 1 && view.childCount == 1 &&
            view.getChildAt(0).visibility == View.VISIBLE
    }

    private fun realLoad() {
        val view = create("contract_real")
        var checkedListeners = false
        var checkedInvisible = false
        var checkedBeforeResult = false
        main { eventAction = { event ->
            if (event.position == "contract_real_banner" && event.name == AdEventName.LOAD_REQUEST) record {
                check(view.childCount == 1 && view.getChildAt(0).visibility == View.INVISIBLE)
                check(view.getChildAt(0).width > 0 && view.getChildAt(0).height > 0)
                checkedInvisible = true
            }
            if (event.position == "contract_real_banner" && event.name == AdEventName.LOAD_RESULT) record {
                val child = view.getChildAt(0) as AdView
                check(child.visibility == View.INVISIBLE)
                checkNotNull(checkNotNull(child.getBannerAd()).adEventCallback)
                checkedBeforeResult = true
            }
        } }
        start(view) { state -> if (state == BannerState.Ready) record {
            val child = view.getChildAt(0) as AdView
            check(child.visibility == View.INVISIBLE)
            val ad = checkNotNull(child.getBannerAd())
            checkNotNull(ad.adEventCallback); checkNotNull(ad.bannerAdRefreshCallback)
            checkedListeners = true
        } }
        await("real exposure and revenue") { count(view, AdEventName.IMPRESSION) == 1 && count(view, AdEventName.PAID) == 1 }
        main { check(checkedInvisible && checkedListeners && checkedBeforeResult); check(count(view, AdEventName.LOAD_REQUEST) == 1) }
    }

    private fun initializationGate() {
        val oldOwner = main { PageOwner() }
        val old = create("contract_init_old", oldOwner)
        var waiting = false
        start(old) { if (it == BannerState.Waiting) waiting = true }
        await("not-yet-initialized provider") { waiting && old.width > 0 }
        main { check(old.childCount == 0); oldOwner.registry.currentState = Lifecycle.State.DESTROYED }
        val current = create("contract_init_new")
        start(current)
        main { check(current.childCount == 0); app.initializeAds() }
        ready(current)
        main { check(old.childCount == 0 && count(old, AdEventName.LOAD_REQUEST) == 0)
            check(count(current, AdEventName.LOAD_REQUEST) == 1) }
    }

    private fun gates() {
        val view = create("contract_gates")
        await("inactive layout") { view.width > 0 }
        main { check(count(view, AdEventName.POSITION) == 0); view.visibility = View.INVISIBLE; view.setActive(true) }
        waitForIdleSync()
        main { check(count(view, AdEventName.LOAD_REQUEST) == 0 && view.childCount == 0)
            view.layoutParams = view.layoutParams.apply { width = 0 } }
        await("zero width") { view.width == 0 }
        main { view.visibility = View.VISIBLE }
        waitForIdleSync()
        main { check(count(view, AdEventName.LOAD_REQUEST) == 0)
            view.setActive(false); view.setActive(true)
            view.layoutParams = view.layoutParams.apply { width = -1 } }
        ready(view)
        main { check(count(view, AdEventName.LOAD_REQUEST) == 1) }
    }

    private fun loadingReentry() {
        val view = create("contract_loading_reentry")
        var reached = false
        start(view) { state -> if (state == BannerState.Loading) {
            reached = true
            view.setActive(false)
            error("Expected host exception after deactivation")
        } }
        await("Loading callback") { reached }
        main { check(view.childCount == 0); check(count(view, AdEventName.LOAD_REQUEST) == 0) }
        start(view)
        ready(view)
        main { check(count(view, AdEventName.LOAD_REQUEST) == 1); check(count(view, AdEventName.POSITION) == 2) }
    }

    private fun inFlightDestroy() {
        val old = create("contract_inflight_old")
        var destroyed = 0
        main { eventAction = { event ->
            if (event.position == "contract_inflight_old_banner" && event.name == AdEventName.LOAD_REQUEST) {
                old.destroy(); old.destroy()
            }
        } }
        start(old) { if (it == BannerState.Destroyed) { destroyed++; error("Expected destroy callback exception") } }
        await("actual request then destruction") { count(old, AdEventName.LOAD_REQUEST) == 1 && destroyed == 1 }
        val replacement = create("contract_inflight_new")
        start(replacement); ready(replacement)
        main {
            check(old.childCount == 0 && destroyed == 1)
            check(count(old, AdEventName.IMPRESSION) == 0)
            check(count(replacement, AdEventName.LOAD_REQUEST) == 1)
        }
    }

    private fun throwingCallbacks() {
        val view = create("contract_throwing")
        val states = mutableListOf<BannerState>()
        main {
            eventAction = { if (it.position == "contract_throwing_banner") error("Expected event listener exception") }
            app.onBannerRevenue = { revenues += it; error("Expected revenue listener exception") }
        }
        start(view) { state ->
            states += state
            if (state == BannerState.Ready) record {
                val child = view.getChildAt(0) as AdView
                check(child.visibility == View.INVISIBLE)
                val callback = checkNotNull(checkNotNull(child.getBannerAd()).adEventCallback)
                callback.onAdPaid(AdValue(PrecisionType.UNKNOWN, 0, "USD"))
            }
            error("Expected UI state callback exception")
        }
        await("exception-isolated actual exposure and paid") {
            count(view, AdEventName.IMPRESSION) == 1 && count(view, AdEventName.PAID) == 1 &&
                revenues.count { it.position == "contract_throwing_banner" } == 1
        }
        main {
            check(view.getChildAt(0).visibility == View.VISIBLE)
            view.setActive(false); view.destroy(); view.destroy()
            check(view.childCount == 0)
            check(states.containsAll(listOf(BannerState.Loading, BannerState.Ready, BannerState.Inactive, BannerState.Destroyed)))
            check(states.count { it == BannerState.Destroyed } == 1)
            app.onBannerRevenue = { revenues += it }
        }
    }

    private fun inFlightRestart() {
        val view = create("contract_restart")
        var abandoned: View? = null
        main { eventAction = { event ->
            if (event.position == "contract_restart_banner" && event.name == AdEventName.LOAD_REQUEST &&
                count(view, AdEventName.LOAD_REQUEST) == 1) {
                abandoned = view.getChildAt(0)
                view.setActive(false); view.setActive(true)
            }
        } }
        start(view); ready(view)
        main { check(abandoned != null && abandoned?.parent == null && view.getChildAt(0) !== abandoned)
            check(count(view, AdEventName.LOAD_REQUEST) == 2 && count(view, AdEventName.POSITION) == 2) }
    }

    private fun configurationFailure() {
        val view = create("contract_config_failure")
        start(view); ready(view)
        val fake = main {
            val child = view.getChildAt(0) as AdView
            CallbackAd(checkNotNull(child.getBannerAd()).getResponseInfo(), failRefreshBinding = true)
        }
        var failed = false
        main {
            view.onState = { if (it == AdShowResult.Failed("banner_callback_configuration_failed")) failed = true }
            ownLoadCallback(view).onAdLoaded(fake.ad)
        }
        await("configuration failure cleanup") { failed && fake.destroyCount == 1 }
        main { check(view.childCount == 0); view.setActive(true); view.requestLayout() }
        waitForIdleSync()
        main { check(count(view, AdEventName.LOAD_REQUEST) == 1); view.setActive(false); view.setActive(true) }
        await("new cycle after configuration failure") { count(view, AdEventName.LOAD_RESULT) == 2 && view.childCount == 1 }
        main { check(count(view, AdEventName.LOAD_REQUEST) == 2 && fake.destroyCount == 1)
            check(events.filter { it.position == "contract_config_failure_banner" &&
                it.name == AdEventName.LOAD_RESULT }.all { it.result == "filled" }) }
    }

    private fun expiredObject() {
        val view = create("contract_expired")
        start(view); ready(view)
        val (callback, fake) = main { ownLoadCallback(view) to
            CallbackAd(checkNotNull((view.getChildAt(0) as AdView).getBannerAd()).getResponseInfo()) }
        main { view.setActive(false); view.setActive(true) }
        await("replacement generation loaded") { count(view, AdEventName.LOAD_RESULT) == 2 &&
            view.childCount == 1 && view.getChildAt(0).visibility == View.VISIBLE }
        val replacement = main { view.getChildAt(0) }
        main { callback.onAdLoaded(fake.ad) }
        await("expired delivered object released") { fake.destroyCount == 1 }
        main { check(view.getChildAt(0) === replacement && replacement.visibility == View.VISIBLE)
            check(count(view, AdEventName.LOAD_REQUEST) == 2 && count(view, AdEventName.LOAD_RESULT) == 2)
            check(events.filter { it.position == "contract_expired_banner" &&
                it.name == AdEventName.LOAD_RESULT }.all { it.result == "filled" }) }
    }

    /** White-box access only to this repository's callback factory and current generation.
     * SDK objects use their documented public interface; no SDK implementation fields are read.
     */
    @Suppress("UNCHECKED_CAST")
    private fun ownLoadCallback(view: AdsBannerView): AdLoadCallback<BannerAd> {
        fun field(name: String) = AdsBannerView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
        val companion = checkNotNull(field("Companion"))
        val relay = checkNotNull(field("events"))
        return companion.javaClass.getDeclaredMethod("loadCallback", WeakReference::class.java,
            java.lang.Long.TYPE, relay.javaClass).apply { isAccessible = true }
            .invoke(companion, WeakReference(view), field("generation"), relay) as AdLoadCallback<BannerAd>
    }

    private class CallbackAd(info: ResponseInfo, failRefreshBinding: Boolean = false) {
        var destroyCount = 0
        private var eventCallback: BannerAdEventCallback? = null
        private var refreshCallback: BannerAdRefreshCallback? = null
        val ad = Proxy.newProxyInstance(BannerAd::class.java.classLoader, arrayOf(BannerAd::class.java)) { _, method, args ->
            when (method.name) {
                "getResponseInfo" -> info
                "setAdEventCallback" -> { eventCallback = args?.get(0) as BannerAdEventCallback?; null }
                "getAdEventCallback" -> eventCallback
                "setBannerAdRefreshCallback" -> {
                    if (failRefreshBinding) error("Injected public callback configuration failure")
                    refreshCallback = args?.get(0) as BannerAdRefreshCallback?; null
                }
                "getBannerAdRefreshCallback" -> refreshCallback
                "destroy" -> { destroyCount++; null }
                else -> error("Unexpected public test-double call: ${method.name}")
            }
        } as BannerAd
    }

    private fun queuedCallbacks() {
        val view = create("contract_queued")
        var destroyed = 0
        start(view) { state ->
            if (state == BannerState.Destroyed) destroyed++
            if (state == BannerState.Ready) record {
                val child = view.getChildAt(0) as AdView
                check(child.visibility == View.INVISIBLE)
                val callback = checkNotNull(checkNotNull(child.getBannerAd()).adEventCallback)
                // Capture identity while alive; our public SDK callback posts delivery to main.
                callback.onAdPaid(AdValue(PrecisionType.UNKNOWN, 0, "USD"))
                callback.onAdPaid(AdValue(PrecisionType.UNKNOWN, 0, "USD"))
                callback.onAdImpression(); callback.onAdClicked()
                view.destroy(); view.destroy()
            }
        }
        await("known paid delivered after destruction") { destroyed == 1 && count(view, AdEventName.PAID) == 1 }
        main {
            check(view.childCount == 0 && count(view, AdEventName.IMPRESSION) == 0 && count(view, AdEventName.CLICK) == 0)
            check(revenues.count { it.position == "contract_queued_banner" } == 1)
        }
    }

    private fun pauseAndHeight() {
        val owner = main { PageOwner() }
        val view = create("contract_pause", owner)
        start(view); ready(view)
        val child = main { view.getChildAt(0) }
        main {
            owner.registry.currentState = Lifecycle.State.STARTED
            activity.host.visibility = View.GONE
            activity.host.removeView(view)
            activity.host.visibility = View.VISIBLE
            activity.host.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        await("re-attached while owner paused") { view.isAttachedToWindow && view.width > 0 }
        main {
            check(child.visibility == View.INVISIBLE)
            view.visibility = View.INVISIBLE
            owner.registry.currentState = Lifecycle.State.RESUMED
            check(child.visibility == View.INVISIBLE)
            view.visibility = View.VISIBLE
        }
        await("same child restored") { child.visibility == View.VISIBLE }
        val height = main { child.height + 20 }
        main { child.minimumHeight = height }
        await("native content height remeasured") { view.height >= height }
        main { check(view.getChildAt(0) === child); check(count(view, AdEventName.LOAD_REQUEST) == 1)
            owner.registry.currentState = Lifecycle.State.DESTROYED; check(view.childCount == 0) }
    }

    private fun record(block: () -> Unit) { runCatching(block).onFailure { callbackFailure = it } }

    private fun failedPauseRecovery() {
        check(shell("settings get global wifi_on").trim() == "1") { "Offline case requires Wi-Fi initially on" }
        check(shell("settings get global mobile_data").trim() == "0") { "Offline case requires mobile data already off" }
        val owner = main { PageOwner() }
        val view = create("contract_failed_pause", owner)
        val connectivity = targetContext.getSystemService(ConnectivityManager::class.java)
        try {
            shell("svc wifi disable")
            await("network offline") { connectivity.activeNetwork == null }
            start(view)
            await("first load failure", 90_000) { count(view, AdEventName.LOAD_RESULT) == 1 }
            main {
                check(events.single { it.position == "contract_failed_pause_banner" &&
                    it.name == AdEventName.LOAD_RESULT }.result == "failed")
            }
            val child = main { checkNotNull(view.getChildAt(0)) }
            main {
                owner.registry.currentState = Lifecycle.State.STARTED
                activity.host.removeView(view)
                view.visibility = View.INVISIBLE
                activity.host.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
            await("failed view re-attached") { view.isAttachedToWindow && view.width > 0 }
            main {
                owner.registry.currentState = Lifecycle.State.RESUMED
                check(child.visibility == View.INVISIBLE)
                view.visibility = View.VISIBLE
            }
            await("failed placeholder visible") { child.visibility == View.VISIBLE }
            main { check(view.getChildAt(0) === child); check(count(view, AdEventName.LOAD_REQUEST) == 1) }
            shell("svc wifi enable")
            await("SDK recovery exposure and revenue", 120_000) {
                count(view, AdEventName.IMPRESSION) == 1 && count(view, AdEventName.PAID) == 1
            }
            main {
                check(view.getChildAt(0) === child)
                check(count(view, AdEventName.LOAD_REQUEST) == 1 && count(view, AdEventName.LOAD_RESULT) == 1)
            }
        } finally { shell("svc wifi enable") }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun await(label: String, timeoutMillis: Long = 40_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            callbackFailure?.let { throw it }
            if (main(predicate)) return
            SystemClock.sleep(50)
        }
        error("Timed out: $label; " + main {
            views.joinToString { "${it.request.position}: width=${it.width}/${it.measuredWidth}, " +
                "visibility=${it.visibility}, shown=${it.isShown}, attached=${it.isAttachedToWindow}, " +
                "window=${it.windowVisibility}, focus=${it.hasWindowFocus()}, children=${it.childCount}, " +
                "child=${it.getChildAt(0)?.let { child -> "${child.width}x${child.height}/${child.isLayoutRequested}" }}" } +
                "; activity=${activity.lifecycle.currentState}"
        })
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private class PageOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}

/** Test APK only: initialization is deliberately deferred to exercise waiting page owners. */
class BannerContractApplication : Application() {
    var onBannerEvent: ((AdEvent) -> Unit)? = null
    var onBannerRevenue: ((AdRevenuePayload) -> Unit)? = null

    fun initializeAds() = Ads.initialize(this, AdsConfig(
        provider = AdMobProviderConfig(AdMobIds.TEST),
        umpConsent = UmpConsentConfig(enabled = false),
        autoShowAppOpen = false,
        loggingEnabled = true,
        eventListener = AdEventListener { if (it.format == AdFormat.BANNER) onBannerEvent?.invoke(it) },
        revenueListener = AdRevenueListener { if (it.format == AdFormat.BANNER) onBannerRevenue?.invoke(it) },
    ))
}
