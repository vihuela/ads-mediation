package com.cashcraft.ads.mediation.internal.nativeads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.ResolvedNativeRequest
import com.cashcraft.ads.mediation.internal.BidCandidateSelector
import com.cashcraft.ads.mediation.internal.BidDecision

/** Each auction exclusively owns its candidates; only the winner ever gets a view. */
internal class NativeBiddingProvider(private val allowTemplate: Boolean) : NativeProvider {
    override fun load(activity: Activity, request: ResolvedNativeRequest, widthPx: Int, callbacks: NativeCallbacks): NativeLoad {
        val handler = Handler(Looper.getMainLooper())
        val inventoryGeneration = NativeAdCache.generation
        return NativeAuction(
            request, callbacks, Ads::nativeAvailability,
            startLoad = { candidate, listener ->
                NativeAdCache.load(activity, candidate, widthPx, allowTemplate, listener)
            },
            subscribe = Ads::observeNativeReadiness,
            dispatch = NativeMainThread::run,
            schedule = { action, delay -> handler.postDelayed(action, delay) },
            unschedule = handler::removeCallbacks,
            clock = SystemClock::elapsedRealtime,
            allowTemplate = allowTemplate,
            retain = { candidate, ad -> NativeAdCache.retain(candidate, widthPx, ad, inventoryGeneration) },
        ).also { it.start() }
    }
}

/** The SDK effects are injected so timeout, cancellation and synchronous callbacks are deterministic. */
internal class NativeAuction(
    private val request: ResolvedNativeRequest,
    private val callbacks: NativeCallbacks,
    private val availability: (AdPlatform) -> NativeAvailability,
    startLoad: (ResolvedNativeRequest, NativeCallbacks) -> NativeLoad,
    private val subscribe: (() -> Unit) -> AutoCloseable,
    private val dispatch: (() -> Unit) -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val clock: () -> Long,
    private val allowTemplate: Boolean = true,
    private val retain: (ResolvedNativeRequest, NativeAdHandle) -> Unit = { _, ad -> ad.destroy() },
) : NativeLoad {
    private class Candidate(val request: ResolvedNativeRequest) {
        val platform = requireNotNull(request.platform)
        var started = false
        var settled = false
        var reason: String? = null
        var operation: NativeLoad? = null
        var ad: NativeAdHandle? = null
        val pendingRevenue = mutableListOf<NativeRevenue>()
    }

    private val candidates = request.candidates().map(::Candidate)
    private var startLoad: ((ResolvedNativeRequest, NativeCallbacks) -> NativeLoad)? = startLoad
    private var subscription: AutoCloseable? = null
    private var started = false
    private var finished = false
    private var cancelled = false
    private var winner: Candidate? = null
    private var delivered = false
    // 所有权移出后仍忽略同对象重复成功；弱引用不会把已交付广告留在旧竞价中。
    private val received = java.util.WeakHashMap<NativeAdHandle, Unit>()
    private val timeout = Runnable {
        if (!finished && !cancelled) {
            candidates.filterNot { it.settled }.forEach {
                it.settled = true
                it.reason = "native_bid_timeout"
                if (it.started) callbacks.candidateFailed(it.platform, "native_bid_timeout")
            }
            finish()
        }
    }

    fun start() {
        if (started || cancelled) return
        if (!callbacks.isActive) { cancel(); return }
        started = true
        schedule(timeout, request.bidTimeoutMillis)
        val observation = subscribe { dispatch(::loadReadyCandidates) }
        if (finished || cancelled) observation.close() else subscription = observation
        loadReadyCandidates()
        // 两端先各领取一次现有库存，再决定；未命中的加载继续由共享库存补货。
        if (request.preferCachedAds && candidates.any { it.ad != null }) finish()
    }

    private fun loadReadyCandidates() {
        for (candidate in candidates) {
            if (finished || cancelled) return
            if (candidate.started || candidate.settled) continue
            val gate = availability(candidate.platform)
            if (gate.failure != null) {
                candidate.settled = true
                candidate.reason = gate.failure
            } else if (gate.ready) {
                candidate.started = true
                callbacks.loadStarted(candidate.platform)
                if (!callbacks.isActive) { cancel(); return }
                if (finished || cancelled) return
                try {
                    val operation = checkNotNull(startLoad).invoke(candidate.request, listener(candidate))
                    if (cancelled || (finished && candidate !== winner)) runCatching { operation.cancel() }
                    else candidate.operation = operation
                } catch (_: Exception) {
                    failed(candidate, "native_load_failed", null)
                }
            }
        }
        if (candidates.all { it.settled }) finish()
    }

    private fun listener(candidate: Candidate) = object : NativeCallbacks {
        override fun loaded(ad: NativeAdHandle) = dispatch {
            if (received.put(ad, Unit) != null) return@dispatch
            if (finished || cancelled || candidate.settled) {
                retainUnused(candidate, ad)
                return@dispatch
            }
            if (ad.isTemplate && !allowTemplate) {
                runCatching { ad.destroy() }
                failed(candidate, "unsupported_native_render_mode", null)
                return@dispatch
            }
            candidate.ad = ad
            candidate.settled = true
            callbacks.candidateLoaded(candidate.platform, ad)
            if (!callbacks.isActive) { cancel(); return@dispatch }
            if (candidates.all { it.settled }) finish()
        }
        override fun failed(reason: String, errorCode: String?) = dispatch { failed(candidate, reason, errorCode) }
        override fun impression(adSource: String?, responseId: String?, revenue: NativeRevenue?) = dispatch {
            if (isWinner(candidate)) callbacks.impression(adSource, responseId, revenue)
        }
        override fun clicked(adSource: String?, responseId: String?) = dispatch {
            if (isWinner(candidate)) callbacks.clicked(adSource, responseId)
        }
        override fun closed() = dispatch { if (isWinner(candidate)) callbacks.closed() }
        override fun overlayOpened() = dispatch { if (isWinner(candidate)) callbacks.overlayOpened() }
        override fun overlayClosed() = dispatch { if (winner === candidate && delivered) callbacks.overlayClosed() }
        override fun paid(revenue: NativeRevenue) = dispatch {
            if (winner === candidate && delivered) callbacks.paid(revenue)
            else if (!finished && !cancelled) candidate.pendingRevenue += revenue
        }
    }

    private fun isWinner(candidate: Candidate) = !cancelled && winner === candidate && delivered

    private fun failed(candidate: Candidate, reason: String, errorCode: String?) {
        if (isWinner(candidate)) { callbacks.failed(reason, errorCode); return }
        if (finished || cancelled || candidate.settled) return
        candidate.settled = true
        candidate.reason = reason
        callbacks.candidateFailed(candidate.platform, reason, errorCode)
        if (candidates.all { it.settled }) finish()
    }

    private fun finish() {
        if (finished || cancelled) return
        finished = true
        stopLoading(cancelOperations = false)
        candidates.forEach { candidate ->
            candidate.ad?.takeIf { !runCatching { it.isValid }.getOrDefault(false) ||
                it.expiresAtMillis?.let { expires -> clock() >= expires } == true }?.let {
                candidate.ad = null
                candidate.reason = "native_ad_expired"
                runCatching { it.destroy() }
            }
        }
        val admob = candidates.firstOrNull { it.platform == AdPlatform.ADMOB }
        val topon = candidates.firstOrNull { it.platform == AdPlatform.TOPON }
        val admobAvailable = admob?.ad != null
        val topOnAvailable = topon?.ad != null
        val selection = BidCandidateSelector.select(
            admobAvailable, runCatching { admob?.ad?.bidPriceUsd }.getOrNull(),
            topOnAvailable, runCatching { topon?.ad?.bidPriceUsd }.getOrNull(),
        )
        winner = candidates.firstOrNull { it.platform == selection?.winner }
        candidates.filterNot { it === winner }.forEach { candidate ->
            val operation = candidate.operation
            candidate.operation = null
            runCatching { operation?.cancel() }
            val ad = candidate.ad
            candidate.ad = null
            candidate.pendingRevenue.clear()
            ad?.let { runCatching { retain(candidate.request, it) }.onFailure { _ -> runCatching { it.destroy() } } }
        }
        if (selection != null) callbacks.bidResult(BidDecision(selection, admobAvailable, topOnAvailable,
            selection.admobPriceUsd, selection.toponPriceUsd))
        if (!callbacks.isActive) cancel()
        if (cancelled) return
        val selected = winner
        val ad = selected?.ad
        if (ad == null) {
            callbacks.failed(if (candidates.all { it.reason == "no_fill" }) "no_fill"
                else candidates.firstOrNull { it.reason == "native_bid_timeout" }?.reason ?: "native_no_bid_candidate")
        } else {
            delivered = true
            // 交付可同步触发曝光并销毁页面；先移出收益，避免取消清理吞掉原广告的合法回调。
            val pendingRevenue = selected.pendingRevenue.toList()
            selected.pendingRevenue.clear()
            callbacks.loaded(ad)
            pendingRevenue.forEach(callbacks::paid)
        }
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        stopLoading()
        candidates.forEach { candidate ->
            val ad = candidate.ad
            candidate.ad = null
            candidate.pendingRevenue.clear()
            if (candidate !== winner || !delivered) ad?.let { retainUnused(candidate, it) }
        }
    }

    /** 页面取消只结束等待；有效未渲染对象仍可按原期限交给后续页面。 */
    private fun retainUnused(candidate: Candidate, ad: NativeAdHandle) {
        runCatching { retain(candidate.request, ad) }.onFailure { runCatching { ad.destroy() } }
    }

    private fun stopLoading(cancelOperations: Boolean = true) {
        unschedule(timeout)
        subscription?.let { runCatching { it.close() } }
        subscription = null
        startLoad = null
        if (!cancelOperations) return
        candidates.forEach { candidate ->
            val operation = candidate.operation
            candidate.operation = null
            runCatching { operation?.cancel() }
        }
    }
}
