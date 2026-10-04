package com.cashcraft.ads.mediation

import android.app.Activity
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.cashcraft.ads.mediation.internal.AdPolicyAttempt
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.logMaterial
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.BannerReadiness
import com.cashcraft.ads.mediation.internal.BannerSlot
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.admob.AdMobBannerEvents
import com.cashcraft.ads.mediation.internal.admob.BannerResponse
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.lang.ref.WeakReference
import kotlin.math.ceil

/**
 * A page-owned AdMob Banner. Activity, page owner and request are fixed for this View's lifetime.
 * Use INVISIBLE for temporary hiding, setActive(false) to end a business cycle, and destroy() for
 * final release. TopOn requests fail explicitly in this release. All host calls require main.
 */
@SuppressLint("ViewConstructor") // A page owner and explicit Banner request are mandatory; no XML constructor.
class AdsBannerView(
    activity: Activity,
    lifecycleOwner: LifecycleOwner,
    val request: BannerRequest,
    active: Boolean = true,
    onState: (BannerState) -> Unit = {},
) : FrameLayout(activity) {
    private var owner: LifecycleOwner? = lifecycleOwner
    private var businessActive = active
    private var destroyed = false
    private var constructed = false
    private var attached = false
    private var evaluating = false
    private var evaluateAgain = false
    private var dispatcher: AdEventDispatcher? = null
    private var slot: BannerSlot? = null
    private var events: AdMobBannerEvents? = null
    private var adView: AdView? = null
    private var bannerAd: BannerAd? = null
    // The loader stays off-screen. SDK auto-refresh belongs to the same show cycle
    // and does not consume additional daily show quota.
    private var loadView: AdView? = null
    private var policyAttempt: AdPolicyAttempt? = null
    private var policyReserved = false
    private var policyBlocked: AdBlockReason? = null
    private var releasing = false
    private var generation = 0L
    private var requestedSize: RequestSize? = null
    private var failed = false
    private var state: BannerState? = null
    private var removeReadinessObserver: (() -> Unit)? = null
    private var removePolicyObserver: (() -> Unit)? = null
    private var stateCallback: (BannerState) -> Unit = onState
    private var destroyListener: (() -> Unit)? = null

    var onState: (BannerState) -> Unit
        get() = stateCallback
        set(value) {
            requireMain()
            if (!destroyed) stateCallback = value
        }

    private val pageObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) destroy() else evaluate()
    }
    private val activityObserver = object : AdLifecycleMonitor.Listener {
        override fun onActivityResumed(activity: Activity) { if (context === activity) evaluate() }
        override fun onActivityPaused(activity: Activity) { if (context === activity) evaluate() }
        override fun onActivityDestroyed(activity: Activity) { if (context === activity) destroy() }
    }
    private val layoutObserver = ViewTreeObserver.OnGlobalLayoutListener { evaluate() }

    init {
        requireMain()
        constructed = true
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            destroy()
        } else {
            lifecycleOwner.lifecycle.addObserver(pageObserver)
            if (!destroyed) {
                AdLifecycleMonitor.addListener(activityObserver)
                // Registration can call back synchronously; dispose it if that callback destroys us.
                val remove = Ads.observeBannerReadiness(request.platform, ::evaluate)
                if (destroyed) remove() else removeReadinessObserver = remove
                val removePolicy = Ads.addPolicyListener { main.post { evaluate() } }
                if (destroyed) removePolicy() else removePolicyObserver = removePolicy
            }
        }
    }

    fun setActive(active: Boolean) {
        requireMain()
        if (destroyed || businessActive == active) return
        businessActive = active
        if (!active) endSlot()
        evaluate()
    }

    /** A host-controlled new opportunity; SDK refresh never creates a policy opportunity. */
    fun refresh() {
        requireMain()
        if (destroyed) return
        endSlot()
        evaluate()
    }

    /** Binding cleanup must survive replacement of the public state callback. */
    internal fun setOnDestroyed(listener: () -> Unit) {
        requireMain()
        if (destroyed) listener() else destroyListener = listener
    }

    fun destroy() {
        requireMain()
        if (destroyed) return
        destroyed = true
        endSlot()
        removeReadinessObserver?.invoke()
        removeReadinessObserver = null
        removePolicyObserver?.invoke()
        removePolicyObserver = null
        owner?.lifecycle?.removeObserver(pageObserver)
        owner = null
        AdLifecycleMonitor.removeListener(activityObserver)
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(layoutObserver)
        dispatcher = null
        val callback = onState
        // Clear before notification so reentrant calls cannot retain or revive a page callback.
        stateCallback = {}
        state = BannerState.Destroyed
        val releaseBinding = destroyListener
        destroyListener = null
        releaseBinding?.invoke()
        notify(callback, BannerState.Destroyed)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (!destroyed) viewTreeObserver.addOnGlobalLayoutListener(layoutObserver)
        evaluate()
    }

    override fun onDetachedFromWindow() {
        attached = false
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(layoutObserver)
        evaluate()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        evaluate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        evaluate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        evaluate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (constructed && request.platform == AdPlatform.ADMOB) {
            val widthDp = ((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) /
                resources.displayMetrics.density).toInt()
            if (widthDp > 0 && request.sizeError(widthDp) == null) {
                // Measurement also runs while INVISIBLE: an initially hidden slot keeps legal space.
                val placeholder = pixelsCoveringDp(requestedAdSize(widthDp).height) + paddingTop + paddingBottom
                if (minimumHeight != placeholder) minimumHeight = placeholder
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Some interop hosts remeasure without laying out an unchanged outer size. Complete the
        // requested native child layout so asynchronous SDK content cannot remain at its old bounds.
        post {
            if (!destroyed && isAttachedToWindow && isLayoutRequested &&
                width == measuredWidth && height == measuredHeight) {
                layout(left, top, right, bottom)
            }
        }
    }

    private fun requestedAdSize(widthDp: Int): AdSize = request.resolveAdSize(context as Activity, widthDp)

    private fun pixelsCoveringDp(dp: Int): Int = ceil(dp * resources.displayMetrics.density.toDouble()).toInt()

    private fun evaluate() {
        if (!constructed || destroyed || releasing) return
        requireMain()
        if (evaluating) { evaluateAgain = true; return }
        evaluating = true
        try {
            do {
                evaluateAgain = false
                evaluateOnce()
            } while (evaluateAgain && !destroyed)
        } finally { evaluating = false }
    }

    private fun evaluateOnce() {
        if (!businessActive) { updateState(BannerState.Inactive); return }
        if (policyBlocked != null) return
        if (!checkPolicy()) return
        request.supportError()?.let { updateState(AdShowResult.Failed(it)); return }
        val readiness = Ads.bannerReadiness(request.platform)
        if (readiness == BannerReadiness.NOT_CONFIGURED || readiness == BannerReadiness.FAILED) {
            adView?.visibility = INVISIBLE
            updateState(AdShowResult.Failed("provider_${readiness.name.lowercase()}"))
            return
        }
        if (slot == null) {
            val available = dispatcher ?: Ads.bannerEvents(request.platform)?.also { dispatcher = it }
            available?.beginBannerSlot(request.position, request.adUnitId, Ads.bannerRevenueListener) { slot = it }
            if (destroyed || !businessActive || slot?.isEnded == true) return
        }
        if (readiness != BannerReadiness.READY || !eligible()) {
            adView?.visibility = INVISIBLE
            if (adView == null) updateState(BannerState.Waiting)
            return
        }
        val density = resources.displayMetrics.density
        val widthDp = ((width - paddingLeft - paddingRight) / density).toInt()
        if (widthDp <= 0) { updateState(BannerState.Waiting); return }
        request.sizeError(widthDp)?.let {
            releaseAd()
            updateState(AdShowResult.Failed(it))
            return
        }
        val size = requestedAdSize(widthDp)
        val key = RequestSize(
            if (request.size == BannerSize.Standard320x50) 320 else widthDp,
            pixelsCoveringDp(size.height),
            if (request.size != BannerSize.Standard320x50) resources.configuration.orientation else 0,
            resources.displayMetrics.densityDpi,
        )
        if (requestedSize != null && requestedSize != key) releaseAd()
        if (adView == null) {
            if (failed) return
            if (minimumHeight != key.heightPx + paddingTop + paddingBottom) {
                minimumHeight = key.heightPx + paddingTop + paddingBottom
            }
            requestedSize = key
            load(size)
        } else {
            showIfEligible()
        }
    }

    private fun eligible(): Boolean {
        val activity = context as Activity
        return !destroyed && businessActive && attached && isShown && windowVisibility == VISIBLE &&
            hasWindowFocus() && !activity.isFinishing && !activity.isDestroyed &&
            (if (activity is LifecycleOwner) activity.lifecycle.currentState == Lifecycle.State.RESUMED
                else AdLifecycleMonitor.currentActivity === activity) &&
            owner?.lifecycle?.currentState == Lifecycle.State.RESUMED &&
            Ads.bannerReadiness(request.platform) == BannerReadiness.READY && width > paddingLeft + paddingRight
    }

    private fun load(size: AdSize) {
        if (!checkPolicy()) return
        val currentSlot = slot?.takeUnless { it.isEnded } ?: return
        val currentGeneration = ++generation
        val currentSize = requestedSize
        try {
            val view = AdView(context as Activity)
            adView = view // Take ownership before builder/configuration can throw.
            view.visibility = INVISIBLE
            view.minimumWidth = pixelsCoveringDp(size.width)
            view.minimumHeight = pixelsCoveringDp(size.height)
            val sdkRequest = BannerAdRequest.Builder(request.adUnitId, size).build()
            val load = checkNotNull(dispatcher).createBannerLoad(currentSlot)
            val currentPolicy = checkNotNull(policyAttempt)
            currentPolicy.logMaterial(AdFormat.BANNER, request.platform)
            val relay = AdMobBannerEvents(currentSlot, load, Ads.bannerLogger(),
                policyImpression = currentPolicy::impression,
                policyClick = currentPolicy::click)
            events = relay
            updateState(BannerState.Loading)
            if (!isCurrent(currentGeneration)) return
            if (!eligible()) { releaseAd(); return }
            // Prepare the current empty View invisibly; callbacks are installed before exposure.
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            view.doOnLayout {
                view.post {
                    if (!isCurrent(currentGeneration)) return@post
                    if (!eligible()) { releaseAd(); return@post }
                    if (!checkPolicy(reserve = true) || !isCurrent(currentGeneration)) return@post
                    val preloadedAd = AdMobAds.pollBanner(request, size)
                    val loader = if (preloadedAd == null) AdView(context as Activity).also { loadView = it } else null
                    val callback = loadCallback(WeakReference(this), currentGeneration, relay) { ad ->
                        // Public AdView contract: unregister cancels in-progress loadAd requests; destroy
                        // the empty loader before this ad can enter the visible display container.
                        val detached = loader?.unregisterBannerAd()
                        if (loader != null && detached !== ad) {
                            detached?.destroy()
                            error("banner_transfer_identity_changed")
                        }
                        loader?.destroy()
                        if (loadView === loader) loadView = null
                        if (isCurrent(currentGeneration) && checkPolicy(reserve = true)) {
                            view.registerBannerAd(ad, context as Activity)
                        }
                    }
                    val error = runCatching {
                        if (preloadedAd == null) checkNotNull(loader).loadAd(sdkRequest, callback)
                        else {
                            relay.suppressLoadTracking()
                            callback.onAdLoaded(preloadedAd)
                        }
                    }.exceptionOrNull()
                    if (preloadedAd == null) load.request()
                    if (error != null && isCurrent(currentGeneration)) {
                        relay.failed("load_exception", error.message, null)
                        if (isCurrent(currentGeneration)) {
                            releaseAd()
                            // Keep the failed size so layout/visibility cannot act as a retry loop.
                            requestedSize = currentSize
                            failed = true
                            updateState(AdShowResult.Failed("load_exception"))
                        }
                    }
                }
            }
            // A width change can create the child from a global-layout callback. Request its
            // first traversal outside that callback so the pending load cannot wait indefinitely.
            post { if (isCurrent(currentGeneration)) requestLayout() }
        } catch (error: Exception) {
            releaseAd()
            requestedSize = currentSize
            failed = true
            updateState(AdShowResult.Failed("banner_configuration_failed: ${error.message}"))
        }
    }

    private fun onLoaded(generation: Long, ad: BannerAd) {
        if (!isCurrent(generation)) { ad.destroy(); return }
        bannerAd = ad
        failed = false
        updateState(BannerState.Ready)
        if (isCurrent(generation)) showIfEligible()
    }

    private fun onFailed(generation: Long, reason: String) {
        if (!isCurrent(generation)) return
        val size = requestedSize
        releaseAd()
        requestedSize = size
        failed = true
        updateState(AdShowResult.Failed(reason))
    }

    private fun onConfigurationFailed(generation: Long) {
        if (!isCurrent(generation)) return
        val size = requestedSize
        releaseAd()
        requestedSize = size
        failed = true
        updateState(AdShowResult.Failed("banner_callback_configuration_failed"))
    }

    private fun showIfEligible() {
        val view = adView ?: return
        if (!eligible()) { view.visibility = INVISIBLE; return }
        if (bannerAd == null && !failed) return
        if (!checkPolicy(reserve = true)) return
        if (view.parent == null) addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (view.visibility != VISIBLE) {
            view.visibility = VISIBLE
            view.requestLayout()
        }
    }

    private fun isCurrent(value: Long): Boolean = !destroyed && businessActive && generation == value && adView != null

    private fun checkPolicy(reserve: Boolean = false): Boolean {
        val current = policyAttempt ?: AdPolicyAttempt(AdPolicyRequest(request.position, mainType = request.mainType)).also { policyAttempt = it }
        if (current.hasImpression) return true
        // Reuse the same reservation, excluding this opportunity from its own pending quota.
        val result = if (reserve || policyReserved) current.reserve() else current.check()
        if (result is AdPolicyCheckResult.Blocked) {
            policyBlocked = result.reason
            releaseAd()
            slot?.end()
            updateState(AdShowResult.Blocked(result.reason))
            return false
        }
        if (!Ads.isPlatformEnabled(request.platform)) {
            slot?.end()
            slot = null
            releaseAd()
            failed = true
            updateState(AdShowResult.Failed("ad_platform_disabled"))
            return false
        }
        if (reserve) policyReserved = true
        return true
    }

    private fun endSlot() {
        slot?.end()
        slot = null
        releaseAd()
        policyBlocked = null
    }

    private fun releaseAd() {
        releasing = true
        generation++
        events?.end()
        events = null
        val previousPolicy = policyAttempt
        policyAttempt = null
        policyReserved = false
        val previousLoader = loadView
        loadView = null
        runCatching { previousLoader?.destroy() }
        val previous = adView
        adView = null
        bannerAd = null
        requestedSize = null
        failed = false
        if (previous != null) {
            if (previous.parent === this) removeView(previous)
            runCatching { previous.destroy() }
        }
        try { previousPolicy?.complete() } finally { releasing = false }
    }

    private fun updateState(value: BannerState) {
        if (destroyed || state == value) return
        state = value
        notify(onState, value)
    }

    private fun notify(callback: (BannerState) -> Unit, value: BannerState) {
        runCatching { callback(value) }.onFailure {
            Ads.bannerLogger().bannerDiagnostic(slot?.slotId.orEmpty(), "state_callback_failed", null, it.message)
        }
    }

    private data class RequestSize(val widthDp: Int, val heightPx: Int, val orientation: Int, val densityDpi: Int)

    private companion object {
        val main = Handler(Looper.getMainLooper())
        fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Banner requires the main thread" }

        fun snapshot(ad: BannerAd?): BannerResponse = runCatching {
            val info = ad?.getResponseInfo() ?: return@runCatching BannerResponse(null)
            val source = info.loadedAdSourceResponseInfo
            BannerResponse(info.responseId?.trim()?.takeIf(String::isNotEmpty), source?.name,
                source?.adapterClassName ?: info.adapterClassName)
        }.getOrDefault(BannerResponse(null))

        fun loadCallback(
            owner: WeakReference<AdsBannerView>,
            generation: Long,
            relay: AdMobBannerEvents,
            registerBanner: ((BannerAd) -> Unit)? = null,
        ) =
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    val response = snapshot(ad)
                    main.post {
                        val host = owner.get()
                        if (host == null || !host.isCurrent(generation)) { ad.destroy(); return@post }
                        relay.prepareLoaded(response)
                        val configurationError = runCatching {
                            installCallbacks(ad, relay, owner, generation)
                            registerBanner?.invoke(ad)
                        }.exceptionOrNull()
                        if (configurationError != null) {
                            relay.failed("callback_configuration_failed", configurationError.message, response.id)
                            host.onConfigurationFailed(generation)
                            runCatching { ad.destroy() }
                            return@post
                        }
                        relay.loaded(response)
                        host.onLoaded(generation, ad)
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val code = adError.code.toString()
                    val reason = adError.message
                    val response = adError.responseInfo?.responseId
                    main.post {
                        relay.failed(code, reason, response)
                        owner.get()?.onFailed(generation, reason)
                    }
                }
            }

        fun installCallbacks(
            ad: BannerAd,
            relay: AdMobBannerEvents,
            owner: WeakReference<AdsBannerView>,
            generation: Long,
        ) {
            val weakAd = WeakReference(ad)
            ad.adEventCallback = object : BannerAdEventCallback {
                override fun onAdImpression() {
                    val response = snapshot(weakAd.get())
                    main.post { relay.impression(response) }
                }
                override fun onAdClicked() {
                    val response = snapshot(weakAd.get())
                    main.post { relay.click(response) }
                }
                override fun onAdPaid(value: AdValue) {
                    val response = snapshot(weakAd.get())
                    val micros = value.valueMicros
                    val currency = value.currencyCode
                    val precision = value.precisionType.name
                    val atMillis = System.currentTimeMillis()
                    main.post { relay.paid(response, micros, currency, precision, atMillis) }
                }
                // Full-screen callbacks describe landing content, not the Banner body closing.
            }
            ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                override fun onAdRefreshed() {
                    val response = snapshot(weakAd.get())
                    main.post {
                        relay.refreshed(response)
                        owner.get()?.takeIf { it.isCurrent(generation) }?.adView?.requestLayout()
                    }
                }
                override fun onAdFailedToRefresh(adError: LoadAdError) {
                    val code = adError.code.toString()
                    val reason = adError.message
                    main.post { relay.refreshFailed(code, reason) }
                }
            }
        }
    }
}
