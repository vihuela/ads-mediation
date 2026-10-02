package com.cashcraft.ads.mediation.internal.nativeads

import com.cashcraft.ads.mediation.NativeState
import com.cashcraft.ads.mediation.NativeRetentionPolicy
import com.cashcraft.ads.mediation.AdsState

internal data class NativeAvailability(val ready: Boolean = false, val failure: String? = null)

internal fun nativeAvailability(
    platformConfigured: Boolean,
    consentPending: Boolean,
    consentAllowed: Boolean,
    providerState: AdsState,
): NativeAvailability = when {
    !platformConfigured -> NativeAvailability(failure = "native_platform_not_configured")
    consentPending -> NativeAvailability()
    !consentAllowed -> NativeAvailability(failure = "consent_not_obtained")
    providerState == AdsState.FAILED -> NativeAvailability(failure = "native_platform_initialization_failed")
    else -> NativeAvailability(ready = providerState == AdsState.READY)
}

/** Main-thread state, with injected SDK/View effects so lifecycle races can be checked on the JVM. */
internal class NativeCardController(
    private val availability: () -> NativeAvailability,
    private val canDisplay: () -> Boolean,
    private val newSlot: () -> NativeSlot?,
    private val load: (NativeCallbacks) -> NativeLoad,
    private val render: (NativeAdHandle, () -> Boolean) -> Unit,
    private val removeView: () -> Unit,
    private val clock: () -> Long,
    private val dispatch: (() -> Unit) -> Unit,
    private val interaction: (String, NativeInteraction) -> Unit,
    private val onStateChanged: (NativeState) -> Unit,
    private val retentionPolicy: NativeRetentionPolicy = NativeRetentionPolicy.DESTROY_ON_HIDE,
    private val onRetentionFallback: (String) -> Unit = {},
    private val recordLoadEvents: Boolean = true,
) {
    var state: NativeState = NativeState.Idle
        private set
    private var active = false
    private var visible = false
    private var generation = 0L
    private var slot: NativeSlot? = null
    private var attempt: NativeAttempt? = null
    private var delivery: NativeDelivery? = null
    private var loading: NativeLoad? = null
    private var ad: NativeAdHandle? = null
    private var closed = false
    private var retained = false
    val isRetained: Boolean get() = retained
    var retentionFallbackReason: String? = null
        private set

    fun update(active: Boolean, visible: Boolean) {
        if (state == NativeState.Destroyed) return
        val wasActive = this.active
        this.active = active
        this.visible = visible
        if (!active) {
            val token = generation
            if (wasActive && !retainBoundAd() && generation == token) endCycle(retentionFallbackReason ?: "native_inactive")
            return
        }
        if (!wasActive && !retained) { closed = false; transition(NativeState.Idle) }
        refresh()
    }

    fun refresh() {
        if (state == NativeState.Destroyed || closed) return
        // 许可撤回仍结束保留对象，不能因页面 inactive 而绕过清理。
        val gate = availability()
        if (retained && gate.failure != null) { fail(gate.failure); return }
        if (!active) return
        if (slot == null) {
            slot = newSlot()
            slot?.position()
            if (!active || state == NativeState.Destroyed) return
        }
        if (gate.failure != null) {
            fail(gate.failure)
            return
        }
        if (!gate.ready || !visible || !canDisplay()) {
            val token = generation
            if ((state == NativeState.Loading || state == NativeState.Loaded) && !retainBoundAd() && generation == token) {
                releaseToIdle(retentionFallbackReason ?: "native_temporarily_unavailable")
            }
            return
        }
        if (retained) {
            val current = ad ?: return
            val token = generation
            // 已绑定对象的恢复资格由来源契约判定，不套用未展示库存的 isValid。
            val resumed = runCatching { current.resumeAfterRetention() }.getOrDefault(false)
            if (generation != token) return
            if (resumed) {
                retained = false
                // 恢复过程也可能同步触发失焦或页面退出，不能让已隐藏媒体继续运行。
                if (!eligible()) {
                    if (!retainBoundAd() && generation == token) releaseToIdle(retentionFallbackReason ?: "native_temporarily_unavailable")
                    return
                }
            } else {
                fallback("native_retention_resume_unsupported")
                if (generation != token) return
                if (!releaseToIdle("native_retention_resume_unsupported")) return
            }
        }
        if (state == NativeState.Idle) start()
    }

    fun retry() {
        if (state !is NativeState.Failed || !eligible() || closed) return
        transition(NativeState.Idle)
        refresh()
    }

    fun sizeChanged() {
        if (ad?.isTemplate != true && state != NativeState.Loading) return
        if (releaseToIdle("native_size_changed")) refresh()
    }

    fun destroy() {
        if (state == NativeState.Destroyed) return
        active = false
        state = NativeState.Destroyed
        release("native_destroyed")
        val oldSlot = slot
        slot = null
        oldSlot?.end("native_destroyed")
        runCatching { onStateChanged(state) }
    }

    private fun retainBoundAd(): Boolean {
        retentionFallbackReason = null // 本次隐藏原因不能继承上一条广告的降级结果。
        if (retentionPolicy != NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE || state != NativeState.Loaded) return false
        val current = ad ?: return false
        if (retained) return true
        val token = generation
        // 只取消本页库存需求；已绑定对象及原展示事件保持独占。
        val operation = loading
        loading = null
        runCatching { operation?.cancel() }
        if (generation != token) return true
        // 消费后的库存有效性可能为 false，是否可安全保留由来源自行判断。
        val paused = runCatching { current.pauseForRetention() }.getOrDefault(false)
        if (generation != token) return true
        if (paused) retained = true
        else fallback("native_retention_pause_unsupported")
        return paused
    }

    private fun fallback(reason: String) {
        retentionFallbackReason = reason
        runCatching { onRetentionFallback(reason) }
    }

    private fun eligible(): Boolean {
        val gate = availability()
        return active && visible && !closed && state != NativeState.Destroyed &&
            gate.ready && gate.failure == null && canDisplay()
    }

    private fun start() {
        val owner = slot ?: return
        val token = ++generation
        val events = owner.attempt(clock, recordLoadEvents)
        attempt = events
        val callbacks = NativeDelivery(events, dispatch, interaction)
        delivery = callbacks
        callbacks.current = { generation == token && state != NativeState.Destroyed && !closed }
        callbacks.interactive = { eligible() && !retained }
        callbacks.onLoaded = { result ->
            if (result === ad) {
                // 保留期间的同对象重复交付不能销毁仍归本位置的广告。
            } else if (ad != null && generation == token) {
                runCatching { result.destroy() }
            } else if (generation != token || !eligible()) {
                runCatching { result.destroy() }
                if (generation == token) releaseToIdle("native_temporarily_unavailable")
            } else if (ad != null || state != NativeState.Loading) {
                // A platform must not replace an owned object by delivering a second success.
                if (result !== ad) runCatching { result.destroy() }
            } else {
                ad = result
                events.loaded(result)
                if (generation == token && eligible()) {
                    if (result.expiresAtMillis?.let { clock() >= it } == true) {
                        if (releaseToIdle("native_ad_expired")) refresh()
                    } else {
                        // 胜出后将事件直连展示交付，取消比价/库存需求不能切断原对象的事件。
                        runCatching { result.setCallbacks(callbacks); render(result) { generation == token && eligible() } }.fold(
                            onSuccess = {
                                if (generation == token) {
                                    if (eligible()) transition(NativeState.Loaded) else refresh()
                                }
                            },
                            onFailure = { error ->
                                if (generation == token) fail(error.message?.takeIf {
                                    it.startsWith("native_") || it == "unsupported_native_render_mode"
                                } ?: "native_render_failed")
                            },
                        )
                    }
                } else if (generation == token) {
                    releaseToIdle("native_temporarily_unavailable")
                }
            }
        }
        callbacks.onFailed = { reason, code -> if (generation == token) fail(reason, code) }
        callbacks.onClosed = {
            if (generation == token) {
                closed = true
                val released = release("native_closed")
                events.close()
                if (generation == released) transition(NativeState.Idle)
            }
        }
        transition(NativeState.Loading)
        if (generation != token) return
        if (!eligible()) { refresh(); return }
        events.start()
        if (generation != token) return
        if (!eligible()) { refresh(); return }
        try {
            val operation = load(callbacks)
            if (generation == token && eligible() && !retained) loading = operation else runCatching { operation.cancel() }
        } catch (error: Exception) {
            if (generation == token) fail("native_load_failed")
        }
    }

    private fun fail(reason: String, code: String? = null) {
        if (state == NativeState.Destroyed || closed) return
        if (state == NativeState.Failed(reason, code)) return
        val failed = NativeState.Failed(reason, code)
        val events = attempt
        val oldSlot = slot
        // Commit before host events: an immediate retry must see Failed.
        state = failed
        val released = release(reason, reportCancellation = false)
        events?.fail(reason, code)
        if (events == null) oldSlot?.end(reason)
        if (generation == released && state == failed) runCatching { onStateChanged(failed) }
    }

    private fun endCycle(reason: String) {
        val oldSlot = slot
        slot = null
        closed = false
        val released = release(reason)
        oldSlot?.end(reason)
        if (generation == released) transition(NativeState.Idle)
    }

    private fun releaseToIdle(reason: String): Boolean {
        val released = release(reason)
        if (generation != released) return false
        transition(NativeState.Idle)
        return generation == released
    }

    private fun release(reason: String, reportCancellation: Boolean = true): Long {
        val released = ++generation
        val oldDelivery = delivery
        val oldLoad = loading
        val oldAd = ad
        val oldAttempt = attempt
        delivery = null
        loading = null
        ad = null
        retained = false
        attempt = null
        oldDelivery?.detach()
        runCatching { oldLoad?.cancel() }
        runCatching(removeView)
        runCatching { oldAd?.destroy() }
        if (reportCancellation) oldAttempt?.cancel(reason)
        return released
    }

    private fun transition(next: NativeState) {
        if (state == NativeState.Destroyed || state == next) return
        state = next
        runCatching { onStateChanged(next) }
    }
}

internal enum class NativeInteraction { CLICK, OPEN, CLOSE }

/** Providers may retain this after cancellation; detach removes every closure reaching the page. */
private class NativeDelivery(
    private val events: NativeAttempt,
    private val dispatch: (() -> Unit) -> Unit,
    private val interaction: (String, NativeInteraction) -> Unit,
) : NativeCallbacks {
    var current: (() -> Boolean)? = null
    var interactive: (() -> Boolean)? = null
    var onLoaded: ((NativeAdHandle) -> Unit)? = null
    var onFailed: ((String, String?) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    fun detach() { current = null; interactive = null; onLoaded = null; onFailed = null; onClosed = null }
    override val isActive: Boolean get() = current?.invoke() == true
    override fun loadStarted(platform: com.cashcraft.ads.mediation.AdPlatform) = dispatch {
        if (current?.invoke() == true) events.loadStarted(platform)
    }
    override fun candidateLoaded(platform: com.cashcraft.ads.mediation.AdPlatform, ad: NativeAdHandle) = dispatch {
        if (current?.invoke() == true) events.candidateLoaded(platform, ad)
    }
    override fun candidateFailed(platform: com.cashcraft.ads.mediation.AdPlatform, reason: String, errorCode: String?) = dispatch {
        if (current?.invoke() == true) events.candidateFailed(platform, reason, errorCode)
    }
    override fun bidResult(decision: com.cashcraft.ads.mediation.internal.BidDecision) = dispatch {
        if (current?.invoke() == true) events.bidResult(decision)
    }
    override fun loaded(ad: NativeAdHandle) = dispatch {
        val receiver = onLoaded
        if (current?.invoke() == true && receiver != null) receiver(ad) else runCatching { ad.destroy() }
    }
    override fun failed(reason: String, errorCode: String?) = dispatch {
        if (current?.invoke() == true) onFailed?.invoke(reason, errorCode)
    }
    override fun impression(adSource: String?, responseId: String?) = dispatch {
        if (current?.invoke() == true) events.impression(adSource, responseId)
    }
    override fun clicked(adSource: String?, responseId: String?) = dispatch {
        if (current?.invoke() == true && interactive?.invoke() == true) {
            interaction(events.id, NativeInteraction.CLICK)
            events.click(adSource, responseId)
        }
    }
    override fun closed() = dispatch { if (current?.invoke() == true) onClosed?.invoke() }
    override fun overlayOpened() = dispatch {
        if (current?.invoke() == true && interactive?.invoke() == true) interaction(events.id, NativeInteraction.OPEN)
    }
    override fun overlayClosed() = dispatch { interaction(events.id, NativeInteraction.CLOSE) }
    override fun paid(revenue: NativeRevenue) = dispatch { events.paid(revenue) }
}
