package com.cashcraft.ads.mediation.internal

/** One owned ad per provider, with request tokens so late callbacks cannot replace a newer load. */
internal class NativeAdCache<T>(
    private val nowMillis: () -> Long,
    private val destroy: (T) -> Unit,
    private val isValid: (T) -> Boolean = { true },
) {
    @Volatile private var ad: T? = null
    @Volatile private var loadedAt = 0L
    private var generation = 0L
    var loadingToken: Long? = null
        private set

    /** Non-consuming snapshot; never calls provider APIs or destroys Android objects off-main. */
    val hasFreshValue: Boolean
        get() = ad != null && nowMillis() - loadedAt < MAX_AGE_MILLIS

    fun peek(): T? {
        ad?.let {
            if (nowMillis() - loadedAt >= MAX_AGE_MILLIS || !isValid(it)) {
                ad = null
                destroy(it)
            }
        }
        return ad
    }

    fun take(): T? = peek().also { ad = null }

    fun beginLoad(): Long? {
        if (loadingToken != null || peek() != null) return null
        return (++generation).also { loadingToken = it }
    }

    fun loaded(token: Long, value: T): Boolean {
        if (loadingToken != token) {
            destroy(value)
            return false
        }
        loadingToken = null
        loadedAt = nowMillis()
        ad = value
        return true
    }

    fun failed(token: Long): Boolean {
        if (loadingToken != token) return false
        loadingToken = null
        return true
    }

    companion object {
        const val MAX_AGE_MILLIS = 60 * 60 * 1_000L
    }
}
