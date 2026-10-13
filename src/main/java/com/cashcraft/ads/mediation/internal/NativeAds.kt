package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState

/** All ownership changes run on the main thread. Native ads never acquire the full-screen gate. */
internal object NativeAds {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var slots: Map<AdPlatform, Slot> = emptyMap()
    private val rendered = mutableMapOf<ViewGroup, NativeAdHandle>()
    private val pendingContainers = mutableSetOf<ViewGroup>()
    private lateinit var config: AdsConfig
    private lateinit var application: Application

    fun initialize(application: Application, config: AdsConfig) {
        if (::config.isInitialized) return
        this.application = application
        this.config = config
        val providers = when (val provider = config.provider) {
            is AdMobProviderConfig -> listOf(provider)
            is TopOnProviderConfig -> listOf(provider)
            is BiddingProviderConfig -> listOf(provider.admob, provider.topon)
        }
        slots = providers.filter { it.adUnitId(AdFormat.NATIVE).isNotBlank() }.associate { provider ->
            provider.platform to Slot(
                provider.platform,
                provider.adUnitId(AdFormat.NATIVE),
                AdEventDispatcher(application, provider.platform, config.provider.mediationMode,
                    config.eventListener, config.loggingEnabled, config.logTag),
            )
        }
        preload()
    }

    fun preload() {
        slots.values.forEach(::load)
    }

    fun isReady(platform: AdPlatform): Boolean = if (Looper.myLooper() == Looper.getMainLooper()) {
        slots[platform]?.cache?.peek() != null
    } else {
        slots[platform]?.cache?.hasFreshValue == true
    }

    fun bidAdSource(platform: AdPlatform): String? = runCatching { slots[platform]?.cache?.peek()?.source }.getOrNull()

    fun bidPrice(platform: AdPlatform): Double? = slots[platform]?.cache?.peek()?.priceUsd

    fun show(
        activity: Activity,
        container: ViewGroup,
        position: String,
        renderers: NativeAdRenderers,
        onResult: (NativeAdShowResult) -> Unit,
    ) {
        if (!pendingContainers.add(container)) {
            // Do not dispatch another event: listeners can synchronously re-enter this same path.
            onResult(NativeAdShowResult.Failed("native_container_in_use"))
            return
        }
        try {
            showInternal(activity, container, position, renderers) {
                pendingContainers.remove(container)
                onResult(it)
            }
        } finally {
            pendingContainers.remove(container)
        }
    }

    private fun showInternal(
        activity: Activity,
        container: ViewGroup,
        position: String,
        renderers: NativeAdRenderers,
        onResult: (NativeAdShowResult) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            fail(position, "activity_not_available", onResult)
            return
        }
        if (rendered.containsKey(container)) {
            fail(position, "native_container_in_use", onResult)
            return
        }
        preload()
        val candidates = slots.values.map { slot ->
            val ad = slot.cache.peek()
            AdBidCandidate(slot.platform, AdFormat.NATIVE, slot.id,
                ad != null && ad.canRender(renderers), ad?.priceUsd.validPriceOrNull())
        }
        val selection = BidCandidateSelector.select(candidates, listOf(AdFormat.NATIVE))
        val slot = selection?.let { slots[it.platform] }
        if (slot == null) {
            val reason = when {
                slots.isEmpty() -> "native_not_configured"
                slots.values.any { it.cache.peek()?.canRender(renderers) == false } -> "native_renderer_missing"
                else -> "no_preloaded_ad"
            }
            fail(position, reason, onResult, BidDecision(listOf(AdFormat.NATIVE), candidates, null))
            return
        }
        val ad = slot.cache.take() ?: run {
            fail(position, "no_preloaded_ad", onResult)
            return
        }
        val session = slot.events.begin(AdFormat.NATIVE, position, slot.id)
        if (config.provider is BiddingProviderConfig) {
            session.bidResult(BidDecision(listOf(AdFormat.NATIVE), candidates, selection, runCatching { ad.source }.getOrNull()).toEventData())
        }
        var ownedView: View? = null
        var impressed = false
        val handle = NativeAdHandleImpl(slot.platform, session.sessionId)
        val owner = activity
        val lifecycle = object : AdLifecycleMonitor.Listener {
            override fun onActivityDestroyed(activity: Activity) {
                if (owner === activity) handle.destroy()
            }
            override fun onActivityPaused(activity: Activity) {
                if (owner === activity && !handle.isDestroyed) runCatching { ad.pause() }
            }
            override fun onActivityResumed(activity: Activity) {
                if (owner === activity && !handle.isDestroyed) runCatching { ad.resume() }
            }
        }
        handle.cleanup = {
            rendered.remove(container, handle)
            AdLifecycleMonitor.removeListener(lifecycle)
            ownedView?.let { view -> runCatching { (view.parent as? ViewGroup)?.removeView(view) } }
            ownedView = null
            runCatching { ad.destroy() }
            if (impressed) session.emit(AdEventName.DISMISS)
            else session.showFailure("destroyed_before_impression")
        }
        // Reserve before invoking user renderer code, which may re-enter Ads.showNative.
        rendered[container] = handle
        AdLifecycleMonitor.addListener(lifecycle)
        val failure = runCatching {
            ad.render(activity, renderers, session, { !handle.isDestroyed }, {
                if (!handle.isDestroyed) {
                    impressed = true
                    session.impression(it.first, it.second)
                }
            }, handle::destroy) { view -> ownedView = view }
            check(!handle.isDestroyed && !activity.isDestroyed && !activity.isFinishing) {
                "native_destroyed_during_render"
            }
            val view = checkNotNull(ownedView)
            check(view.parent == null) { "native_view_already_attached" }
            container.addView(view)
        }.exceptionOrNull()
        if (failure != null) {
            session.showFailure("native_render_failed", cause = failure)
            handle.destroy()
        }
        load(slot)
        // Keep host callbacks outside the render try/catch; an app exception is not an ad failure.
        onResult(if (failure == null) NativeAdShowResult.Rendered(handle)
            else NativeAdShowResult.Failed("native_render_failed"))
    }

    private fun fail(
        position: String,
        reason: String,
        callback: (NativeAdShowResult) -> Unit,
        decision: BidDecision? = null,
    ) {
        val slot = slots.values.firstOrNull()
        val events = slot?.events ?: AdEventDispatcher(application, config.provider.platform,
            config.provider.mediationMode, config.eventListener, config.loggingEnabled, config.logTag)
        val session = events.begin(AdFormat.NATIVE, position, slot?.id.orEmpty())
        if (decision != null && config.provider is BiddingProviderConfig) session.bidResult(decision.toEventData())
        session.showFailure(reason)
        callback(NativeAdShowResult.Failed(reason))
    }

    private fun load(slot: Slot) {
        if (!Ads.consentSnapshot.canRequestAds) return
        val providerReady = when (slot.platform) {
            AdPlatform.ADMOB -> AdMobAds.state == AdMobState.READY
            AdPlatform.TOPON -> TopOnAds.state == TopOnState.READY
        }
        if (!providerReady) return
        val token = slot.cache.beginLoad() ?: return
        val session = slot.events.beginLoad(AdFormat.NATIVE, slot.id, 1)
        val timeout = Runnable {
            if (slot.cache.failed(token)) session.failed("timeout", null, "native_load_timeout", null)
        }
        handler.postDelayed(timeout, 60_000L)
        val loaded: (NativeCreative) -> Unit = { ad -> onMain {
            handler.removeCallbacks(timeout)
            if (slot.cache.loaded(token, ad)) {
                session.loaded(ad.source, ad.responseId)
                handler.postDelayed({ slot.cache.peek() }, NativeAdCache.MAX_AGE_MILLIS)
            }
        } }
        val failed: (String, String?, String?) -> Unit = { result, code, reason -> onMain {
            handler.removeCallbacks(timeout)
            if (slot.cache.failed(token)) session.failed(result, code, reason, null)
        } }
        try {
            when (slot.platform) {
                AdPlatform.ADMOB -> loadAdMobNative(slot.id, loaded, failed)
                AdPlatform.TOPON -> loadTopOnNative(application, slot.id, loaded, failed)
            }
        } catch (error: Exception) {
            failed("error", null, error.message)
        }
    }

    private class Slot(val platform: AdPlatform, val id: String, val events: AdEventDispatcher) {
        val cache = NativeAdCache(SystemClock::elapsedRealtime, { ad: NativeCreative -> runCatching { ad.destroy() }; Unit },
            NativeCreative::isValid)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }
}

internal class NativeAdHandleImpl(
    override val platform: AdPlatform,
    override val sessionId: String,
    private val runOnMain: (() -> Unit) -> Unit = ::nativeOnMain,
) : NativeAdHandle {
    @Volatile override var isDestroyed: Boolean = false
        private set
    var cleanup: (() -> Unit)? = null

    override fun destroy() = runOnMain {
        if (isDestroyed) return@runOnMain
        isDestroyed = true
        val action = cleanup
        cleanup = null
        action?.invoke()
    }
}
