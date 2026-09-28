package com.cashcraft.ads.mediation.admob

/** An unshown object keeps its original age bound; cancellation never makes it younger. */
internal data class RetainedAd<T>(
    val ad: T,
    val loadStartedAtMillis: Long?,
    val maxAgeMillis: Long?,
    val priceUsd: Double? = null,
    val wasRetained: Boolean = false,
) {
    fun isUsable(nowMillis: Long): Boolean {
        val start = loadStartedAtMillis ?: return false
        val lifetime = maxAgeMillis ?: return false
        return lifetime > 0 && nowMillis >= start && nowMillis - start < lifetime
    }
}
