package com.cashcraft.ads.mediation

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AdPolicyAttempt
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.BidCandidateSelector
import com.cashcraft.ads.mediation.internal.BidDecision
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.nativeads.*
import java.lang.ref.WeakReference
import java.util.UUID

/** A single cached Native, presented like an interstitial. Restored Activities never reload it. */
class NativeFullScreenActivity : FragmentActivity() {
    private var session: NativeFullScreenSession? = null
    private var card: AdsNativeView? = null
    /** Original business position, available to the host's custom layout factory. */
    val adPosition: String? get() = session?.request?.position
    internal val nativeRequest: ResolvedNativeRequest? get() = session?.request
    internal val policyAttempt: AdPolicyAttempt? get() = session?.policyAttempt
    internal val onNativeImpression: (() -> Unit)? get() = session?.let { { it.onImpression() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = NativeFullScreenSession.current
        if (savedInstanceState != null || pending == null ||
            intent.getStringExtra(SESSION_ID) != pending.id || !pending.attach(this)) {
            finish()
            return
        }
        session = pending
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Only the host Close button may dismiss the ad through user navigation.
        onBackPressedDispatcher.addCallback(this) { /* Consume system back, including gestures. */ }
        card = AdsNativeView(this, this, NativeRequest(pending.request.position, mainType = pending.policyAttempt?.request?.mainType), pending.layout,
            onStateChanged = pending::onStateChanged).also { root.addView(it, ViewGroup.LayoutParams(-1, -1)) }
    }

    internal fun loadNative(callbacks: NativeCallbacks): NativeLoad {
        session?.deliver(callbacks) ?: callbacks.failed("native_session_missing")
        return NativeLoad { }
    }

    override fun onDestroy() {
        card?.destroy()
        card = null
        session?.complete()
        session = null
        super.onDestroy()
    }

    internal companion object { const val SESSION_ID = "native_full_screen_session" }
}

/** Retains only the unrendered winner during the Activity handoff; no global Activity callbacks. */
internal class NativeFullScreenSession(
    val request: ResolvedNativeRequest,
    val layout: NativeLayout.Custom,
    private var ad: NativeAdHandle?,
    private val attempt: FullScreenShowAttempt,
    host: Activity,
    private val sceneValid: () -> Boolean,
    private var onResult: ((AdShowResult) -> Unit)?,
    private val decision: BidDecision? = null,
    private val availability: () -> NativeAvailability = {
        Ads.nativeAvailability(request.candidates().firstOrNull { it.platform == decision?.selection?.winner } ?: request)
    },
    private val trace: (String) -> Unit = {},
) {
    val policyAttempt: AdPolicyAttempt? get() = attempt.policy
    val id = UUID.randomUUID().toString()
    private val host = WeakReference(host)
    private var presenter = WeakReference<NativeFullScreenActivity>(null)
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var delivered = false
    val impression = java.util.concurrent.atomic.AtomicBoolean(false)
    private var failure = "native_closed_before_impression"
    private var completed = false
    private val deadline = Runnable { finish("native_handoff_timeout") }
    private val lifecycle = object : AdLifecycleMonitor.Listener {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === this@NativeFullScreenSession.host.get()) finish("activity_not_available")
        }
    }

    fun attach(activity: NativeFullScreenActivity): Boolean {
        if (completed || attached) return false
        sceneFailure()?.let { finish(it); return false }
        attached = true
        presenter = WeakReference(activity)
        return true
    }

    fun deliver(callbacks: NativeCallbacks) {
        val selected = ad
        ad = null // Ownership passes once. Resuming cannot load a second ad.
        val invalid = sceneFailure() ?: availability().let {
            it.failure ?: if (!it.ready) "native_not_ready" else null
        }
        val valid = selected != null && runCatching {
            selected.isValid && selected.expiresAtMillis?.let { SystemClock.elapsedRealtime() < it } != false
        }.getOrDefault(false)
        if (completed || invalid != null || !valid) {
            runCatching { selected?.destroy() }
            callbacks.failed(invalid ?: "native_ad_unavailable")
            return
        }
        try {
            decision?.let(callbacks::bidResult)
            delivered = true
            callbacks.loaded(checkNotNull(selected))
        } catch (error: Exception) {
            runCatching { selected?.destroy() }
            throw error
        }
    }

    fun onImpression() {
        attempt.policy?.impression()
        if (impression.compareAndSet(false, true)) trace("全屏原生已确认曝光，交接记录=$id。")
    }

    fun onStateChanged(state: NativeState) {
        when (state) {
            NativeState.Loaded -> handler.removeCallbacks(deadline)
            is NativeState.Failed -> finish(state.reason)
            is NativeState.Blocked -> finish(state.reason.code)
            // DESTROY_ON_HIDE and SDK close both release the only ad. Do not leave an empty Activity.
            NativeState.Idle -> if (delivered) finish("native_no_longer_visible")
            else -> Unit
        }
    }

    fun finish(reason: String) {
        if (completed) return
        failure = reason
        val activity = presenter.get()
        if (activity != null && !activity.isDestroyed) activity.finish() else complete()
    }

    fun complete() {
        if (completed) return
        completed = true
        handler.removeCallbacks(deadline)
        AdLifecycleMonitor.removeListener(lifecycle)
        runCatching { ad?.destroy() }
        ad = null
        presenter.clear()
        host.clear()
        if (current === this) current = null
        attempt.complete()
        val callback = onResult
        onResult = null
        val shown = impression.get()
        val endReason = if (shown && failure == "native_closed_before_impression") "dismissed" else failure
        val endMessage = "全屏原生结束：${if (shown) "已确认曝光" else "未确认曝光"}；${endReason.flowReason()}（原因码=$endReason）；交接记录=$id。"
        trace(endMessage)
        Ads.nativeLog(request.position) { endMessage }
        val result = if (shown) AdShowResult.Dismissed else AdShowResult.Failed(failure)
        runCatching { callback?.invoke(attempt.policy?.result(result) ?: result) }
    }

    private fun sceneFailure(): String? = when {
        host.get()?.let { it.isFinishing || it.isDestroyed } != false -> "activity_not_available"
        else -> try { if (sceneValid()) null else "scene_invalid" }
            catch (_: Exception) { "scene_validation_failed" }
    }

    companion object {
        var current: NativeFullScreenSession? = null
            private set

        fun start(activity: Activity, position: String, layout: NativeLayout.Custom,
            sceneValid: () -> Boolean,
            attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
            trace: (String) -> Unit = {},
            onResult: (AdShowResult) -> Unit): NativeFullScreenSession? {
            if (attempt.policy == null) attempt.policy = AdPolicyAttempt(AdPolicyRequest(position, fullscreen = true,
                mainType = AdMainType.NATIVE_FULLSCREEN))
            fun fail(reason: String): NativeFullScreenSession? {
                attempt.complete()
                onResult(attempt.policy!!.result(AdShowResult.Failed(reason)))
                return null
            }
            attempt.policy!!.check().let {
                if (it is AdPolicyCheckResult.Blocked) return fail(it.reason.code)
            }
            val request = NativeRequest(position, mainType = attempt.policy!!.request.mainType)
            request.failureReason()?.let { return fail(it) }
            val resolved = Ads.resolveNativeRequest(request, fullScreen = true) ?: return fail("sdk_not_initialized")
            resolved.failureReason()?.let { return fail(it) }
            val waitingGuard = attempt.guard
            attempt.guard = {
                waitingGuard?.invoke() ?: try { if (sceneValid()) null else "scene_invalid" }
                catch (_: Exception) { "scene_validation_failed" }
            }
            FullScreenShowGate.tryAcquire(activity, Ads.nativeAvailability(resolved).failure, attempt)
                ?.let { return fail(it) }
            val generation = NativeAdCache.generation
            var decision: BidDecision? = null
            val cached = selectCachedNative(resolved,
                take = { runCatching { NativeAdCache.takeCached(it, EmptyNativeCallbacks) }.getOrNull() },
                retain = { candidate, ad -> NativeAdCache.retain(candidate, 0, ad, generation) },
                onBid = { decision = it },
            ) ?: return fail("no_preloaded_ad")
            FullScreenShowGate.commit(activity, Ads.nativeAvailability(cached.first).failure, attempt)
                ?.let { cached.second.destroy(); return fail(it) }
            val session = NativeFullScreenSession(resolved, layout, cached.second, attempt, activity, sceneValid, onResult, decision,
                trace = trace)
            trace("选中全屏原生：${cached.first.platform.flowName()}，报价=${decision?.selection?.priceUsd.flowPrice()} 美元/次展示；交接记录=${session.id}。")
            current = session
            Ads.nativeLog(position) { "全屏兜底：已独占领取原生缓存 | ${cached.first.platform.flowName()}" }
            AdLifecycleMonitor.addListener(session.lifecycle)
            session.handler.postDelayed(session.deadline, 3_000L)
            try {
                activity.startActivity(Intent(activity, NativeFullScreenActivity::class.java)
                    .putExtra(NativeFullScreenActivity.SESSION_ID, session.id))
            } catch (_: Exception) {
                session.finish("native_activity_start_failed")
            }
            return session
        }
    }
}

internal fun selectCachedNative(
    request: ResolvedNativeRequest,
    take: (ResolvedNativeRequest) -> NativeAdHandle?,
    retain: (ResolvedNativeRequest, NativeAdHandle) -> Unit,
    onBid: (BidDecision) -> Unit = {},
): Pair<ResolvedNativeRequest, NativeAdHandle>? {
    val candidates = request.candidates().mapNotNull { candidate -> take(candidate)?.let { candidate to it } }
    val admob = candidates.firstOrNull { it.first.platform == AdPlatform.ADMOB }
    val topon = candidates.firstOrNull { it.first.platform == AdPlatform.TOPON }
    val selection = BidCandidateSelector.select(admob != null, runCatching { admob?.second?.bidPriceUsd }.getOrNull(),
        topon != null, runCatching { topon?.second?.bidPriceUsd }.getOrNull())
    val winner = selection?.winner
    onBid(BidDecision(selection, admob != null, topon != null, selection?.admobPriceUsd, selection?.toponPriceUsd))
    candidates.filterNot { it.first.platform == winner }.forEach { (candidate, ad) ->
        runCatching { retain(candidate, ad) }.onFailure { runCatching { ad.destroy() } }
    }
    return candidates.firstOrNull { it.first.platform == winner }
}

private object EmptyNativeCallbacks : NativeCallbacks {
    override fun loaded(ad: NativeAdHandle) = Unit
    override fun failed(reason: String, errorCode: String?) = Unit
    override fun impression(adSource: String?, responseId: String?) = Unit
    override fun clicked(adSource: String?, responseId: String?) = Unit
    override fun closed() = Unit
    override fun overlayOpened() = Unit
    override fun overlayClosed() = Unit
    override fun paid(revenue: NativeRevenue) = Unit
}
