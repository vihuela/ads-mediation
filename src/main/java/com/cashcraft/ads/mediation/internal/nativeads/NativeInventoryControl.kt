package com.cashcraft.ads.mediation.internal.nativeads

import com.cashcraft.ads.mediation.AdPlatform

/** 本层兼容身份不代表 TopOn 同 placement 的 SDK 库存按尺寸隔离。 */
internal data class NativeInventoryKey(
    val platform: AdPlatform,
    val id: String,
    val widthPx: Int? = null,
    val templateRatio: Float? = null,
)

/** 主线程共享库存控制；SDK 适配不持有页面或展示对象，报价与领取分开处理。 */
internal class NativeInventoryControl(
    private val clock: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val prepare: (NativeInventoryKey, Long?, (Boolean) -> Unit) -> NativeLoad?,
    private val closeSession: (NativeInventoryKey) -> Unit,
    private val reportError: (NativeInventoryKey, String, Throwable) -> Unit,
    private val loadDelayMillis: (NativeInventoryKey) -> Long = { 0L },
) {
    private class Entry(val key: NativeInventoryKey) {
        var demand = 0
        var ready = false
        var started = false
        var pending = false
        var exhausted = false
        var retries = 0
        var token = 0L
        var deadline: Long? = null
        var operation: NativeLoad? = null
        var retry: Runnable? = null
        var expiry: Runnable? = null
    }

    private val entries = linkedMapOf<NativeInventoryKey, Entry>()
    private var permitted = AdPlatform.entries.toSet()

    fun acquire(key: NativeInventoryKey): AutoCloseable {
        // TopOn 同广告位的模板尺寸不能并存；仅替换已无页面需求的旧规格。
        if (key.platform == AdPlatform.TOPON) entries.values
            .filter { it.demand == 0 && it.key.platform == key.platform && it.key.id == key.id && it.key != key }
            .forEach { close(it.key) }
        val entry = entries.getOrPut(key) { Entry(key) }
        entry.demand++
        ensure(entry)
        var released = false
        return AutoCloseable {
            if (!released) {
                released = true
                if (entries[key] === entry) entry.demand--
            }
        }
    }

    fun isReady(key: NativeInventoryKey): Boolean = entries[key]?.let {
        it.key.platform in permitted && it.ready && (it.deadline?.let { end -> clock() < end } != false)
    } == true

    fun hasDemand(key: NativeInventoryKey): Boolean = (entries[key]?.demand ?: 0) > 0

    fun deadline(key: NativeInventoryKey): Long? = entries[key]?.deadline

    fun hasIncompatibleKey(key: NativeInventoryKey): Boolean =
        entries.values.any { it.demand > 0 && it.key.platform == key.platform && it.key.id == key.id && it.key != key }

    fun consumed(key: NativeInventoryKey) {
        val entry = entries[key] ?: return
        if (!isReady(key)) return
        entry.ready = false
        if (key.platform == AdPlatform.TOPON) {
            entry.retries = 0
            entry.exhausted = false
            ensure(entry)
        }
        // AdMob 的持续回调和补货由 SDK 承担，不重复启动或叠加重试。
    }

    fun takeFailed(key: NativeInventoryKey) {
        val entry = entries[key] ?: return
        if (key.platform != AdPlatform.TOPON || !isReady(key)) return
        entry.ready = false
        scheduleRetry(entry)
    }

    fun retry(key: NativeInventoryKey) {
        val entry = entries[key] ?: return
        if (!entry.exhausted || entry.key.platform !in permitted) return
        entry.exhausted = false
        entry.retries = 0
        ensure(entry)
    }

    fun networkRecovered() {
        entries.values.filter { it.key.platform == AdPlatform.TOPON && it.exhausted }
            .forEach { retry(it.key) }
    }

    fun updateEnvironment(permitted: Boolean) =
        updateEnvironment(if (permitted) AdPlatform.entries.toSet() else emptySet())

    fun updateEnvironment(permitted: Set<AdPlatform>) {
        if (this.permitted == permitted) return
        this.permitted = permitted.toSet()
        entries.values.toList().forEach { entry ->
            if (entry.key.platform !in this.permitted) {
                stop(entry)
            } else {
                ensure(entry)
            }
        }
    }

    fun clear() {
        entries.keys.toList().forEach(::close)
    }

    fun close(key: NativeInventoryKey) {
        val entry = entries.remove(key) ?: return
        stop(entry)
    }

    private fun current(entry: Entry, token: Long) =
        entries[entry.key] === entry && entry.token == token && entry.key.platform in permitted

    private fun ensure(entry: Entry) {
        if (entries[entry.key] !== entry || entry.key.platform !in permitted || entry.ready ||
            entry.pending || entry.retry != null || entry.exhausted) return
        val isAdMob = entry.key.platform == AdPlatform.ADMOB
        if (isAdMob && entry.started) return
        val delay = loadDelayMillis(entry.key)
        if (delay > 0L) {
            val token = entry.token
            val retry = Runnable {
                if (current(entry, token)) {
                    entry.retry = null
                    ensure(entry)
                }
            }
            entry.retry = retry
            schedule(retry, delay)
            return
        }
        entry.started = true
        entry.pending = true
        val token = ++entry.token
        if (isAdMob) {
            // SDK 无逐对象加载时刻时按会话起点保守限时；自动补货不能续期。
            entry.deadline = clock() + SESSION_MILLIS
            val expiry = Runnable {
                if (current(entry, token)) {
                    stop(entry)
                    ensure(entry)
                }
            }
            entry.expiry = expiry
            schedule(expiry, SESSION_MILLIS)
        }
        val operation = try {
            prepare(entry.key, entry.deadline) { success -> completed(entry, token, success) }
        } catch (error: Exception) {
            report(entry, "库存准备异常", error)
            completed(entry, token, false)
            null
        }
        if (operation == null && isAdMob && current(entry, token)) {
            // 未启动会话不能等待 SDK 自行重试；清理会话时保留本轮启动失败预算。
            val retries = entry.retries
            stop(entry)
            if (current(entry, token + 1)) {
                entry.retries = retries
                scheduleRetry(entry)
            }
        } else if (current(entry, token) && (entry.pending || isAdMob)) {
            entry.operation = operation
        } else {
            cancel(entry, operation)
        }
    }

    private fun completed(entry: Entry, token: Long, success: Boolean) {
        if (!current(entry, token)) return
        if (entry.deadline?.let { clock() >= it } == true) return
        if (entry.key.platform == AdPlatform.TOPON && !entry.pending) return
        entry.pending = false
        entry.ready = success
        if (entry.key.platform == AdPlatform.ADMOB) return
        val operation = entry.operation
        entry.operation = null
        // SDK 就绪仍可能领取失败；只有 consumed 才重置失败预算。
        if (!success) scheduleRetry(entry)
        cancel(entry, operation)
    }

    private fun scheduleRetry(entry: Entry) {
        if (entry.retries < 3) {
            val token = entry.token
            val delay = 2_000L shl entry.retries++
            val retry = Runnable {
                if (current(entry, token)) {
                    entry.retry = null
                    ensure(entry)
                }
            }
            entry.retry = retry
            schedule(retry, delay)
        } else {
            entry.exhausted = true
        }
    }

    private fun stop(entry: Entry) {
        val started = entry.started
        val operation = entry.operation
        entry.token++
        entry.operation = null
        entry.retry?.let(unschedule)
        entry.expiry?.let(unschedule)
        entry.retry = null
        entry.expiry = null
        entry.ready = false
        entry.started = false
        entry.pending = false
        entry.exhausted = false
        entry.retries = 0
        entry.deadline = null
        // 状态先失效；取消/关闭可同步触发旧回调或再次关闭。
        cancel(entry, operation)
        if (started) runCatching { closeSession(entry.key) }.onFailure {
            report(entry, "关闭库存会话失败", it)
        }
    }

    private fun cancel(entry: Entry, operation: NativeLoad?) {
        runCatching { operation?.cancel() }.onFailure {
            report(entry, "取消库存准备失败", it)
        }
    }

    private fun report(entry: Entry, message: String, error: Throwable) {
        runCatching { reportError(entry.key, message, error) }
    }

    private companion object {
        const val SESSION_MILLIS = 3_600_000L
    }
}
