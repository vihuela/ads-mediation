package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdProviderConfig
import com.cashcraft.ads.mediation.BiddingProviderConfig

internal enum class BannerReadiness {
    NOT_INITIALIZED, NOT_CONFIGURED, INITIALIZING, READY, FAILED, CONSENT_REQUIRED,
}

/** Main-thread observation of the existing initialization, never a second SDK init flow. */
internal class BannerProviderReadiness {
    private val states = mutableMapOf<AdPlatform, BannerReadiness>()
    private val observers = mutableMapOf<AdPlatform, MutableSet<() -> Unit>>()

    fun configure(provider: AdProviderConfig) {
        check(states.isEmpty()) { "Provider configuration is immutable after initialization" }
        AdPlatform.entries.forEach { platform ->
            states[platform] = if (provider is BiddingProviderConfig || provider.platform == platform) {
                BannerReadiness.INITIALIZING
            } else BannerReadiness.NOT_CONFIGURED
        }
        AdPlatform.entries.forEach(::notifyObservers)
    }

    fun read(platform: AdPlatform, canRequestAds: Boolean): BannerReadiness {
        val state = states[platform] ?: return BannerReadiness.NOT_INITIALIZED
        return when {
            state == BannerReadiness.NOT_CONFIGURED || state == BannerReadiness.FAILED -> state
            !canRequestAds -> BannerReadiness.CONSENT_REQUIRED
            else -> state
        }
    }

    fun completed(platform: AdPlatform, success: Boolean) {
        if (states[platform] != BannerReadiness.INITIALIZING) return
        states[platform] = if (success) BannerReadiness.READY else BannerReadiness.FAILED
        notifyObservers(platform)
    }

    /** UMP can become allowed through the existing privacy-options flow after an initial denial. */
    fun started() {
        states.keys.toList().forEach { platform ->
            if (states[platform] != BannerReadiness.NOT_CONFIGURED && states[platform] != BannerReadiness.READY) {
                states[platform] = BannerReadiness.INITIALIZING
                notifyObservers(platform)
            }
        }
    }

    fun failedPending() {
        AdPlatform.entries.forEach { completed(it, false) }
    }

    /** Subscribe before the initial read; completion cannot fall in a read/subscribe gap. */
    fun observe(platform: AdPlatform, onChanged: () -> Unit): () -> Unit {
        // A wrapper makes two registrations of the same callback independently removable.
        val observer: () -> Unit = { onChanged() }
        observers.getOrPut(platform) { linkedSetOf() }.add(observer)
        runCatching(observer)
        return { observers[platform]?.remove(observer); Unit }
    }

    private fun notifyObservers(platform: AdPlatform) {
        observers[platform]?.toList()?.forEach { observer ->
            // A callback can remove another observer during this same notification.
            if (observers[platform]?.contains(observer) == true) runCatching(observer)
        }
    }
}
