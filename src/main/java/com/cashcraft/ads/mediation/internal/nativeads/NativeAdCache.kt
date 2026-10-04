package com.cashcraft.ads.mediation.internal.nativeads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.ResolvedNativeRequest

/** Unrendered inventory only. Every operation runs on the main thread. */
internal object NativeAdCache {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val inventory by lazy {
        NativeCandidateCache(SystemClock::elapsedRealtime,
            schedule = { action, delay -> handler.postDelayed(action, delay) },
            unschedule = { handler.removeCallbacks(it) })
    }
    val generation: Long get() = inventory.generation

    private val preloads = mutableMapOf<NativeInventoryKey, AdMobNativeInventory>()
    private val topOnInventories = mutableMapOf<NativeInventoryKey, TopOnNativeProvider.Inventory>()
    private val loadEvents = mutableMapOf<NativeInventoryKey, com.cashcraft.ads.mediation.internal.AdLoadSession>()
    private fun inventoryLoaded(key: NativeInventoryKey, events: com.cashcraft.ads.mediation.internal.AdLoadSession, success: Boolean) {
        if (loadEvents[key] !== events) return
        loadEvents.remove(key)
        if (success) events.loaded(null, null)
        else events.failed("failed", null, "native_inventory_load_failed", null)
    }
    private var environmentInstalled = false
    private var inventoryContext: android.content.Context? = null
    private var networkInstalled = false
    private fun installNetwork(context: android.content.Context) {
        if (networkInstalled) return
        val manager = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                handler.post { control.networkRecovered(); inventoryChanged() }
            }
        }
        try {
            manager.registerNetworkCallback(android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
            networkInstalled = true
        } catch (error: Exception) {
            Ads.nativeLog("TopOn", warning = true, error = error) { "网络恢复监听不可用，仍可显式重试" }
        }
    }
    private val waiting = linkedSetOf<Runnable>()
    private fun inventoryChanged() {
        handler.post { waiting.toList().forEach { it.run() } }
    }

    private fun updateEnvironment() {
        control.updateEnvironment(
            com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground,
            AdPlatform.entries.filter { Ads.nativeAvailability(it).ready }.toSet(),
        )
        inventoryChanged()
    }

    private fun installEnvironment() {
        if (environmentInstalled) return
        environmentInstalled = true
        Ads.observeNativeReadiness(::updateEnvironment)
        com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.addListener(
            object : com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.Listener {
                override fun onAppEnteredForeground(activity: Activity) = updateEnvironment()
                override fun onAppEnteredBackground() = updateEnvironment()
            })
    }
    private val control by lazy {
        NativeInventoryControl(SystemClock::elapsedRealtime,
            schedule = { action, delay -> handler.postDelayed(action, delay) },
            unschedule = { handler.removeCallbacks(it) },
            prepare = { key, deadline, complete ->
                check(Ads.nativeAvailability(key.platform).ready) { "native_not_ready" }
                Ads.nativeLog(key.platform.name) { "库存准备：首次需求或消费补货" }
                val events = Ads.beginNativeInventoryLoad(key.platform, key.id)
                loadEvents[key] = events
                if (key.platform == AdPlatform.ADMOB) {
                    val session = preloads.getOrPut(key) {
                        AdMobNativeInventory("cashcraft-native-${++preloadSequence}", key.id)
                    }
                    session.prepare(checkNotNull(deadline)) { success ->
                        inventoryLoaded(key, events, success)
                        complete(success)
                        Ads.nativeLog(key.platform.name, debug = true) { "备用库存 | 可领取=$success（不代表当前展示对象状态）" }
                        inventoryChanged()
                    }
                } else {
                    val session = topOnInventories.getOrPut(key) {
                        TopOnNativeProvider().inventory(checkNotNull(inventoryContext), key)
                    }
                    session.prepare { success ->
                        inventoryLoaded(key, events, success)
                        complete(success)
                        Ads.nativeLog(key.platform.name, debug = true) { "备用库存 | 可领取=$success（不代表当前展示对象状态）" }
                        inventoryChanged()
                    }
                }
            },
            closeSession = { key ->
                loadEvents.remove(key)?.failed("cancelled", null, "native_inventory_closed", null)
                if (key.platform == AdPlatform.ADMOB) preloads.remove(key)?.close()
                else topOnInventories.remove(key)?.close()
                Unit
            },
            reportError = { key, message, error ->
                loadEvents.remove(key)?.failed("failed", null, "native_inventory_load_failed", null)
                Ads.nativeLog(key.platform.name, warning = true, error = error) { message }
            })
    }
    private var preloadSequence = 0L

    // 测试零价同样走生产库存；报价读取失败保持未知，选择器使用实际领取对象。
    fun acquirePreload(request: ResolvedNativeRequest): AutoCloseable {
        require(request.platform == AdPlatform.ADMOB)
        check(Ads.nativeAvailability(request).ready) { "native_not_ready" }
        installEnvironment()
        updateEnvironment()
        return control.acquire(NativeInventoryKey(AdPlatform.ADMOB, request.adUnitId))
    }

    fun acquireTopOnInventory(context: android.content.Context, request: ResolvedNativeRequest,
        widthPx: Int): AutoCloseable {
        require(request.platform == AdPlatform.TOPON)
        check(Ads.nativeAvailability(request).ready) { "native_not_ready" }
        val key = topOnKey(request, widthPx)
        // 同 placement 的 SDK 库存共享；未完成不同尺寸证据前明确拒绝并行不兼容 key。
        check(!control.hasIncompatibleKey(key)) {
            "native_inventory_size_conflict"
        }
        inventoryContext = context.applicationContext
        installNetwork(context)
        installEnvironment()
        updateEnvironment()
        return control.acquire(key)
    }

    fun peekTopOnInventory(request: ResolvedNativeRequest, widthPx: Int): String? {
        val key = topOnKey(request, widthPx)
        return if (control.isReady(key)) topOnInventories[key]?.peekIdentity() else null
    }

    fun takeTopOnInventory(request: ResolvedNativeRequest, widthPx: Int,
        callbacks: NativeCallbacks): NativeAdHandle? {
        if (!Ads.nativeAvailability(request).ready ||
            !com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground) return null
        val key = topOnKey(request, widthPx)
        if (!control.isReady(key)) return null
        val ad = try {
            topOnInventories[key]?.take(request, widthPx, callbacks)
        } catch (error: Exception) {
            control.takeFailed(key)
            throw error
        }
        if (ad == null) {
            control.takeFailed(key)
            return null
        }
        control.consumed(key)
        Ads.nativeLog(request.position, debug = true) { "TopOn 预加载广告已领取，交给本次比价或展示；继续补货" }
        return ad
    }

    fun closeTopOnInventory(request: ResolvedNativeRequest, widthPx: Int) = control.close(topOnKey(request, widthPx))

    private fun topOnKey(request: ResolvedNativeRequest, widthPx: Int) = NativeInventoryKey(
        AdPlatform.TOPON, request.adUnitId,
        widthPx.takeIf { request.topOnTemplateAspectRatio != null }, request.topOnTemplateAspectRatio,
    )

    fun peekPreload(request: ResolvedNativeRequest): String? {
        val key = NativeInventoryKey(AdPlatform.ADMOB, request.adUnitId)
        return if (control.isReady(key)) preloads[key]?.peekIdentity() else null
    }

    fun takePreload(request: ResolvedNativeRequest, callbacks: NativeCallbacks): NativeAdHandle? {
        if (!Ads.nativeAvailability(request).ready ||
            !com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground) return null
        val key = NativeInventoryKey(AdPlatform.ADMOB, request.adUnitId)
        if (!control.isReady(key)) return null
        val ad = preloads[key]?.take(callbacks) ?: return null
        control.consumed(key)
        Ads.nativeLog(request.position, debug = true) { "AdMob 预加载广告已领取，交给本次比价或展示；SDK 继续补货" }
        return ad
    }

    fun closePreload(request: ResolvedNativeRequest) =
        control.close(NativeInventoryKey(requireNotNull(request.platform), request.adUnitId))


    fun preload(context: android.content.Context, request: ResolvedNativeRequest) {
        require(request.topOnTemplateAspectRatio == null) { "native_preload_requires_self_rendered" }
        val demand = if (request.platform == AdPlatform.ADMOB) acquirePreload(request)
            else acquireTopOnInventory(context, request, 0)
        // 预热不持有页面需求；让现有闲置计时器限制其寿命。
        demand.close()
    }

    /** Exclusively takes existing self-rendered inventory; never waits or starts a load. */
    fun takeCached(request: ResolvedNativeRequest, callbacks: NativeCallbacks): NativeAdHandle? {
        if (!Ads.nativeAvailability(request).ready ||
            !com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground) return null
        val ad = inventory.take(request, 0, false)?.also { it.setCallbacks(callbacks) }
            ?: if (request.platform == AdPlatform.ADMOB) takePreload(request, callbacks)
            else takeTopOnInventory(request, 0, callbacks)
        if (ad != null && !runCatching {
                !ad.isTemplate && ad.isValid && ad.expiresAtMillis?.let { SystemClock.elapsedRealtime() < it } != false
            }.getOrDefault(false)) {
            runCatching { ad.destroy() }
            return null
        }
        return ad
    }

    fun load(activity: Activity, request: ResolvedNativeRequest, widthPx: Int,
        allowTemplate: Boolean, callbacks: NativeCallbacks): NativeLoad {
        val key = if (request.platform == AdPlatform.ADMOB)
            NativeInventoryKey(AdPlatform.ADMOB, request.adUnitId) else topOnKey(request, widthPx)
        val hadDemand = control.hasDemand(key)
        // 命中未渲染保留区也必须建立需求，重新启动或保活下一条库存。
        val demand = if (request.platform == AdPlatform.ADMOB) acquirePreload(request)
            else acquireTopOnInventory(activity, request, widthPx)
        // 新页面不重置另一页面仍在等待的失败轮；无活跃需求后的显式获取可重新尝试。
        if (!hadDemand) control.retry(key)
        val cached = inventory.take(request, widthPx, allowTemplate)
        if (cached != null) {
            try {
                cached.setCallbacks(callbacks)
                callbacks.loaded(cached)
            } catch (error: Exception) {
                runCatching { cached.destroy() }
                demand.close()
                throw error
            }
            return NativeLoad { demand.close() }
        }
        var settled = false
        var cancelled = false
        lateinit var receive: Runnable
        val timeout = Runnable {
            if (!settled && !cancelled) {
                settled = true
                waiting.remove(receive)
                demand.close()
                callbacks.failed("native_load_timeout")
            }
        }
        receive = Runnable {
            if (settled || cancelled || !callbacks.isActive) return@Runnable
            try {
                val ad = inventory.take(request, widthPx, allowTemplate)?.also { it.setCallbacks(callbacks) }
                    ?: if (request.platform == AdPlatform.ADMOB) takePreload(request, callbacks)
                    else takeTopOnInventory(request, widthPx, callbacks)
                if (ad != null) {
                    settled = true
                    waiting.remove(receive)
                    handler.removeCallbacks(timeout)
                    if (ad.isTemplate && !allowTemplate) {
                        ad.destroy()
                        demand.close()
                        callbacks.failed("unsupported_native_render_mode")
                    } else {
                        callbacks.loaded(ad)
                    }
                }
            } catch (error: Exception) {
                settled = true
                waiting.remove(receive)
                handler.removeCallbacks(timeout)
                demand.close()
                Ads.nativeLog(request.position, warning = true, error = error) { "库存领取失败" }
                callbacks.failed("native_load_failed")
            }
        }
        waiting.add(receive)
        // 单平台冷库存允许 SDK 完成加载；双平台仍由 NativeAuction 的七秒期限取消等待。
        handler.postDelayed(timeout, 30_000L)
        // 缓存优先在请求入口同步领取，供本轮立即比价；SDK 后续通知仍通过 inventoryChanged 排队交付。
        if (request.preferCachedAds) receive.run() else handler.post(receive)
        return NativeLoad {
            if (!cancelled) {
                cancelled = true
                waiting.remove(receive)
                handler.removeCallbacks(receive)
                handler.removeCallbacks(timeout)
                demand.close()
            }
        }
    }

    fun retain(request: ResolvedNativeRequest, widthPx: Int, ad: NativeAdHandle, requestGeneration: Long) {
        // 撤回许可后迟到的旧请求不能重新填回已经清理的库存。
        if (!Ads.nativeAvailability(request).ready) {
            runCatching { ad.destroy() }
            return
        }
        if (inventory.put(request, widthPx, ad, requestGeneration)) {
            inventoryChanged()
            Ads.nativeLog(request.position, debug = true) { "未渲染对象保留 | ${request.platform} | 本层保留区" }
        }
    }
    fun consentRevoked() {
        inventory.clear()
        if (environmentInstalled) control.updateEnvironment(
            com.cashcraft.ads.mediation.internal.AdLifecycleMonitor.isAppInForeground, false)
    }

    fun clear() {
        inventory.clear()
        if (environmentInstalled) control.clear()
    }
}

/** One candidate per platform/placement, at most four total; no background prefetch. */
internal class NativeCandidateCache(
    private val clock: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
) {
    private data class Key(val platform: AdPlatform, val id: String)
    private data class Entry(val ad: NativeAdHandle, val width: Int, val ratio: Float?, val deadline: Long)
    private val entries = linkedMapOf<Key, Entry>()
    private val expiry = Runnable { prune(); scheduleExpiry() }
    var generation = 0L
        private set


    fun take(request: ResolvedNativeRequest, widthPx: Int, allowTemplate: Boolean): NativeAdHandle? {
        prune()
        val key = key(request)
        val entry = entries[key]
        if (entry == null || (entry.ad.isTemplate &&
                (!allowTemplate || entry.width != widthPx || entry.ratio != request.topOnTemplateAspectRatio))) {
            scheduleExpiry()
            return null
        }
        entries.remove(key) // Exclusive ownership transfers before any host callback.
        scheduleExpiry()
        return entry.ad
    }

    fun put(request: ResolvedNativeRequest, widthPx: Int, ad: NativeAdHandle, requestGeneration: Long = generation): Boolean {
        // 当前许可已恢复也不能接纳上一许可代次的迟到对象。
        if (requestGeneration != generation) {
            runCatching { ad.destroy() }
            return false
        }
        // 同一对象换 key 或重复入池时只能出现一次，原期限不能重置。
        entries.entries.removeAll { it.value.ad === ad }
        prune()
        // 到期对象的销毁可能同步撤回许可；不能把清理前的候选放入新代次。
        if (requestGeneration != generation) {
            runCatching { ad.destroy() }
            return false
        }
        // TopOn 的首次保留期限写回原句柄；离池等待、跨页及再次入池均不能续期。
        val deadline = minOf(ad.expiresAtMillis ?: Long.MAX_VALUE, clock() + 60 * 60 * 1000L)
        if (ad.platform == AdPlatform.TOPON) ad.bindDeadline(deadline)
        if (!valid(ad) || !ad.canCache || clock() >= deadline) {
            runCatching { ad.destroy() }
            scheduleExpiry()
            return false
        }
        ad.setCallbacks(null) // A cached object must not keep the previous page or auction alive.
        val key = key(request)
        val old = entries.remove(key)
        if (old?.ad !== ad) runCatching { old?.ad?.destroy() }
        if (requestGeneration != generation) {
            runCatching { ad.destroy() }
            return false
        }
        // 此处是一小时本层保留上限，不是推测的 TopOn 加载时间或 SDK TTL。
        entries[key] = Entry(ad, widthPx, request.topOnTemplateAspectRatio, deadline)
        while (entries.size > 4) {
            val eldest = entries.keys.first()
            runCatching { entries.remove(eldest)?.ad?.destroy() }
        }
        scheduleExpiry()
        return true
    }

    fun clear() {
        generation++ // 先失效旧请求，再销毁对象；销毁回调可能同步重入。
        val ads = entries.values.map { it.ad }
        entries.clear()
        unschedule(expiry)
        ads.forEach { runCatching { it.destroy() } }
    }

    private fun prune() {
        val expired = mutableListOf<NativeAdHandle>()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (clock() >= entry.deadline || !valid(entry.ad)) {
                iterator.remove()
                expired += entry.ad
            }
        }
        // SDK 销毁可同步重入 clear；完成集合遍历后再释放，避免破坏迭代器。
        expired.forEach { runCatching { it.destroy() } }
    }

    private fun valid(ad: NativeAdHandle) = runCatching {
        ad.isValid && (ad.platform != AdPlatform.ADMOB || ad.expiresAtMillis != null) &&
            (ad.expiresAtMillis?.let { clock() < it } != false)
    }.getOrDefault(false)

    private fun scheduleExpiry() {
        unschedule(expiry)
        entries.values.minOfOrNull { it.deadline }?.let { schedule(expiry, (it - clock()).coerceAtLeast(0)) }
    }

    private fun key(request: ResolvedNativeRequest) = Key(requireNotNull(request.platform), request.adUnitId)
}
