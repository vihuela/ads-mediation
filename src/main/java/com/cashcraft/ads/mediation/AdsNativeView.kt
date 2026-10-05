package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.cashcraft.ads.mediation.internal.AdPolicyAttempt
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.nativeads.NativeCardPolicyAdapter
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle
import com.cashcraft.ads.mediation.internal.nativeads.NativeAvailability
import com.cashcraft.ads.mediation.internal.nativeads.NativeBiddingProvider
import com.cashcraft.ads.mediation.internal.nativeads.NativeCardController
import com.cashcraft.ads.mediation.internal.nativeads.NativeInteractions
import com.cashcraft.ads.mediation.internal.nativeads.NativeMainThread
import com.cashcraft.ads.mediation.internal.nativeads.NativePositionRegistry
import com.cashcraft.ads.mediation.internal.nativeads.createDefaultNativeLayout

/** 外层 View 只持有当前连接；平台 View 和广告由唯一 position 记录持有。公开操作须在主线程。 */
class AdsNativeView(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    val request: NativeRequest,
    val layout: NativeLayout = NativeLayout.Default,
    active: Boolean = true,
    visible: Boolean = true,
    onStateChanged: (NativeState) -> Unit = {},
    val retentionPolicy: NativeRetentionPolicy = NativeRetentionPolicy.DESTROY_ON_HIDE,
) : FrameLayout(activity) {
    // 保留旧接入的尾随 lambda 调用形式。
    constructor(
        activity: Activity, lifecycleOwner: LifecycleOwner, request: NativeRequest,
        layout: NativeLayout = NativeLayout.Default, active: Boolean = true, visible: Boolean = true,
        onStateChanged: (NativeState) -> Unit,
    ) : this(activity, lifecycleOwner, request, layout, active, visible, onStateChanged, NativeRetentionPolicy.DESTROY_ON_HIDE)

    private var entry: NativePositionRegistry.Entry<Presentation>? = null
    private var terminalState: NativeState = NativeState.Idle
    private var activeValue = active
    private var visibleValue = visible
    private var callback = onStateChanged
    private var attached = false
    private var aggregatedVisible = false
    private var released = false

    val state: NativeState get() = if (released) terminalState else entry?.value?.controller?.state ?: terminalState

    init {
        checkMainThread()
        val old = positions[request.position]
        if (old?.connection != null) {
            Ads.nativeLog(request.position, warning = true) { "位置冲突：已有容器占用，拒绝当前连接" }
            terminalState = NativeState.Failed("native_position_occupied")
            runCatching { callback(terminalState) }
        } else {
            val record = if (old != null && old.value.matches(activity, lifecycleOwner, request, layout, retentionPolicy)) {
                check(positions.attach(old, this))
                entry = old
                old.value
            } else {
                old?.value?.destroy()
                Presentation(activity, lifecycleOwner, request, layout, retentionPolicy).also {
                    entry = checkNotNull(positions.claim(request.position, it, this))
                    it.entry = checkNotNull(entry)
                }
            }
            record.start()
            if (ownsConnection()) {
                record.update(active, visible)
                // 新连接即使仍是 Loaded，也交付当前状态给新 UI。
                if (record.controller.state == NativeState.Loaded) runCatching { callback(state) }
            }
        }
    }

    /** 同时应用资格与最新回调，避免重组产生中间的可展示状态。 */
    fun update(active: Boolean, visible: Boolean, onStateChanged: (NativeState) -> Unit) {
        checkMainThread()
        if (released) return
        callback = onStateChanged
        activeValue = active
        visibleValue = visible
        if (ownsConnection()) {
            entry?.value?.update(active, visible)
            refresh()
        }
    }

    fun setActive(active: Boolean) = update(active, visibleValue, callback)
    fun setVisible(visible: Boolean) = update(activeValue, visible, callback)
    fun setOnStateChanged(onStateChanged: (NativeState) -> Unit) {
        checkMainThread()
        if (!released) callback = onStateChanged
    }
    fun retry() { checkMainThread(); if (ownsConnection()) entry?.value?.controller?.retry() }

    /** 显式销毁只影响本连接所属记录，旧外层迟到调用不能销毁新连接。 */
    fun destroy() {
        checkMainThread()
        if (released) return
        if (ownsConnection()) entry?.value?.destroy()
        finishConnection()
    }

    /** Compose 退出：只有明确页级 owner 才能在组合外继续观察最终销毁。 */
    fun release() {
        checkMainThread()
        if (released) return
        val current = entry
        if (current == null || !positions.owns(current, this)) { finishConnection(); return }
        val record = current.value
        val canRetain = retentionPolicy == NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE &&
            lifecycleOwner !== activity && lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED &&
            !activity.isDestroyed && !activity.isFinishing
        if (!canRetain) { destroy(); return }
        // 先断开旧业务回调与外层引用，再暂停原平台对象。
        positions.detach(current, this)
        callback = {}
        released = true
        terminalState = NativeState.Destroyed
        entry = null
        record.detachPlatformView()
        record.update(false, visibleValue)
        if (record.controller.state != NativeState.Loaded) record.destroy()
    }

    private fun finishConnection() {
        released = true
        terminalState = NativeState.Destroyed
        callback = {}
        entry = null
    }

    private fun ownsConnection(): Boolean = !released && entry?.let { positions.owns(it, this) } == true
    private fun refresh() { if (ownsConnection()) entry?.value?.refresh() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        refresh()
    }
    override fun onDetachedFromWindow() {
        attached = false
        refresh()
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        refresh()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        refresh()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refresh()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        refresh()
    }

    private val contentWidth: Int get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)
    private fun canDisplay(): Boolean = !activity.isFinishing && !activity.isDestroyed &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        attached && aggregatedVisible && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && hasWindowFocus() && contentWidth > 0

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AdsNativeView must be used on the main thread" }
    }

    private class Presentation(
        private val activity: Activity,
        private val owner: LifecycleOwner,
        private val request: NativeRequest,
        private val layout: NativeLayout,
        private val policy: NativeRetentionPolicy,
    ) {
        lateinit var entry: NativePositionRegistry.Entry<Presentation>
        private val connection: AdsNativeView? get() = entry.connection as? AdsNativeView
        private var started = false
        private var destroyed = false
        private var subscription: AutoCloseable? = null
        private var removePolicyListener: (() -> Unit)? = null
        private val handler = Handler(Looper.getMainLooper())
        private var platformView: View? = null
        private var requestedWidth = 0
        private var activityResumed = (activity as? LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) ?: activity.hasWindowFocus()
        private val ownerObserver = LifecycleEventObserver { _, _ ->
            if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) destroy() else refresh()
        }
        // 页级 owner 仍存活时，Activity 最终销毁（含配置重建）也必须结束旧记录。
        private val activityObserver = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(destroyedActivity: Activity) { if (destroyedActivity === activity) destroy() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity === this@Presentation.activity) { activityResumed = true; refresh() }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity === this@Presentation.activity) { activityResumed = false; refresh() }
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity === this@Presentation.activity) { activityResumed = false; refresh() }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        private val allowTemplate = layout !is NativeLayout.Custom && request.topOnTemplateAspectRatio != null
        private val resolvedRequest: ResolvedNativeRequest? get() =
            (activity as? NativeFullScreenActivity)?.nativeRequest ?: Ads.resolveNativeRequest(request)
        val controller = NativeCardController(
            availability = {
                request.failureReason()?.let { NativeAvailability(failure = it) }
                    ?: resolvedRequest?.let { Ads.nativeAvailability(it) }
                    ?: NativeAvailability()
            },
            canDisplay = { activityResumed && connection?.canDisplay() == true },
            newSlot = { resolvedRequest?.let {
                Ads.newNativeSlot(it, (activity as? NativeFullScreenActivity)?.onNativePosition)
            } },
            load = {
                requestedWidth = checkNotNull(connection).contentWidth
                val resolved = checkNotNull(resolvedRequest)
                if (activity is NativeFullScreenActivity) activity.loadNative(it)
                else if (resolved.isBidding) NativeBiddingProvider(allowTemplate).load(activity, resolved, requestedWidth, it)
                else NativeAdCache.load(activity, resolved.candidates().single(), requestedWidth, allowTemplate, it)
            },
            render = { ad, isCurrent ->
                runCatching { render(ad, isCurrent) }.onFailure { error ->
                    Ads.nativeLog(request.position, error = error) { "渲染失败：正在释放本次广告" }
                }.getOrThrow()
            },
            removeView = {
                if (platformView != null) Ads.nativeLog(request.position) { "隐藏或失效释放：移除原平台容器" }
                detachPlatformView(); platformView = null
            },
            clock = SystemClock::elapsedRealtime,
            dispatch = NativeMainThread::run,
            interaction = NativeInteractions::onInteraction,
            onStateChanged = { next -> connection?.callback?.invoke(next) },
            onActualImpression = { (activity as? NativeFullScreenActivity)?.onNativeImpression?.invoke() },
            retentionPolicy = policy,
            canBindAd = { ad -> ad.platform?.let(Ads::isPlatformEnabled) != false },
            privacyAllowed = { Ads.consentSnapshot.canRequestAds },
            platformsEnabled = {
                val candidates = resolvedRequest?.candidates().orEmpty()
                candidates.isEmpty() || candidates.any { it.platform?.let(Ads::isPlatformEnabled) != false }
            },
            policyAttemptFactory = {
                if (activity is NativeFullScreenActivity) {
                    activity.policyAttempt?.let { NativeCardPolicyAdapter(it, owned = false) }
                } else NativeCardPolicyAdapter(AdPolicyAttempt(AdPolicyRequest(request.position, sceneType = request.sceneType)))
            },
            // 页面只领取共享库存；真实 SDK 准备事件由库存会话记录。
            recordLoadEvents = false,
            schedule = { action, delay -> handler.postDelayed(action, delay) },
            unschedule = handler::removeCallbacks,
            onRetentionFallback = { reason ->
                Ads.nativeLog(request.position, warning = true) { "保留降级释放：来源未通过安全暂停或恢复" }
                Ads.nativeLog(request.position, debug = true) { "保留降级 | 原因标识=$reason" }
            },
        )

        fun matches(activity: Activity, owner: LifecycleOwner, request: NativeRequest, layout: NativeLayout, policy: NativeRetentionPolicy): Boolean =
            !destroyed && this.activity === activity && this.owner === owner && this.request == request &&
                this.layout === layout && this.policy == policy

        fun start() {
            if (started || destroyed) return
            started = true
            owner.lifecycle.addObserver(ownerObserver)
            if (destroyed) return
            activity.application.registerActivityLifecycleCallbacks(activityObserver)
            subscription = Ads.observeNativeReadiness { NativeMainThread.run { refresh() } }
            val remove = Ads.addPolicyListener { NativeMainThread.run { refresh() } }
            if (destroyed) remove() else removePolicyListener = remove
            if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED || activity.isDestroyed || activity.isFinishing) destroy()
        }

        fun update(active: Boolean, visible: Boolean) {
            val wasRetained = controller.isRetained
            controller.update(active, visible)
            applyRetentionChange(wasRetained)
        }

        private fun applyRetentionChange(wasRetained: Boolean) {
            // active/visible、owner 或窗口资格导致的成功保留都分离原平台 View。
            // 保留对象与容器引用，恢复时只重新附着，不重新渲染或注册。
            if (controller.isRetained) detachPlatformView()
            if (!wasRetained && controller.isRetained) Ads.nativeLog(request.position) { "按来源契约保留原广告与平台容器，本页库存需求已结束" }
            if (wasRetained && !controller.isRetained && controller.state == NativeState.Loaded) Ads.nativeLog(request.position) { "恢复原广告：沿用同一对象和平台容器，未重新注册" }
        }

        fun refresh() {
            if (destroyed) return
            val view = connection
            if (view != null && requestedWidth > 0 && view.contentWidth > 0 && requestedWidth != view.contentWidth) {
                controller.sizeChanged()
            }
            val wasRetained = controller.isRetained
            controller.refresh()
            applyRetentionChange(wasRetained)
            // 恢复仅重新附着原平台 View，不执行 render 或平台注册。
            if (controller.state == NativeState.Loaded && !controller.isRetained && view != null && view.activeValue && view.visibleValue && activityResumed && view.canDisplay()) {
                platformView?.takeIf { it.parent == null }?.let { view.addView(it) }
            }
        }

        fun detachPlatformView() { platformView?.let { (it.parent as? ViewGroup)?.removeView(it) } }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            val view = connection
            val notify = view?.callback
            // 先删除实际记录并清除旧连接，迟到 observer 不会按 position 误删新记录。
            positions.remove(entry)
            view?.finishConnection()
            subscription?.let { runCatching { it.close() } }
            subscription = null
            removePolicyListener?.invoke()
            removePolicyListener = null
            runCatching { owner.lifecycle.removeObserver(ownerObserver) }
            runCatching { activity.application.unregisterActivityLifecycleCallbacks(activityObserver) }
            controller.destroy()
            Ads.nativeLog(request.position) { "最终释放：展示记录与生命周期观察已结束" }
            runCatching { notify?.invoke(NativeState.Destroyed) }
        }

        private fun render(ad: NativeAdHandle, isCurrent: () -> Boolean) {
            val view = connection ?: return
            if (requestedWidth != view.contentWidth) controller.sizeChanged()
            if (!isCurrent()) return
            val startedAt = SystemClock.elapsedRealtime()
            val binding = if (ad.isTemplate) {
                require(layout !is NativeLayout.Custom) { "unsupported_native_render_mode" }
                require(request.topOnTemplateAspectRatio != null) { "native_template_size_unknown" }
                null
            } else {
                try {
                    when (val selected = layout) {
                        NativeLayout.Default -> createDefaultNativeLayout(activity)
                        is NativeLayout.Custom -> selected.create(activity, ad.assets)
                    }.also { it.validate() }
                } catch (error: Exception) {
                    throw IllegalArgumentException("native_layout_invalid", error)
                }
            }
            // 工厂是业务代码，可能同步导航或销毁；再次核对连接、宽度和交付代次。
            if (requestedWidth != view.contentWidth) controller.sizeChanged()
            if (!isCurrent() || connection !== view) return
            val rendered = ad.render(activity, binding, view.contentWidth)
            if (!isCurrent() || connection !== view) {
                (rendered as? ViewGroup)?.removeAllViews()
                return
            }
            require(rendered.parent == null) { "native_view_already_attached" }
            platformView = rendered
            if (activity is NativeFullScreenActivity) {
                view.addView(rendered, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } else view.addView(rendered)
            Ads.nativeLog(request.position) {
                "渲染完成：平台容器已挂载，等待平台确认曝光 | 渲染=${SystemClock.elapsedRealtime() - startedAt}ms"
            }
            if (!isCurrent()) detachPlatformView()
        }
    }

    private companion object {
        val positions = NativePositionRegistry<Presentation>()
    }
}
