package com.cashcraft.ads.mediation.internal.admob

import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.AdsModuleLogger
import com.cashcraft.ads.mediation.internal.BannerDisplaySession
import com.cashcraft.ads.mediation.internal.BannerSlot

/** Snapshot at SDK callback ingress, before dispatching to the main thread. */
internal data class BannerResponse(
    val id: String?,
    val source: String? = null,
    val adapterClassName: String? = null,
)

/** One local load's metadata. SDK listeners can retain this after the page has been released. */
internal class AdMobBannerEvents(
    private val slot: BannerSlot,
    private val load: AdLoadSession,
    private val logger: AdsModuleLogger? = null,
) {
    private val displays = LinkedHashMap<String, BannerDisplaySession>()
    private var ended = false
    private var loadFinished = false
    private var trackLoad = true

    @Synchronized
    fun suppressLoadTracking() { trackLoad = false }

    @Synchronized
    fun prepareLoaded(response: BannerResponse) {
        if (!ended) remember(response, if (loadFinished || !trackLoad) null else load.requestId)
    }

    @Synchronized
    fun loaded(response: BannerResponse) {
        if (ended) return
        remember(response, if (loadFinished || !trackLoad) null else load.requestId)
        loadFinished = true
        if (trackLoad) load.loaded(response.source, response.id)
    }

    @Synchronized
    fun failed(code: String?, reason: String?, responseId: String?) {
        if (ended) return
        loadFinished = true
        if (trackLoad) load.failed("failed", code, reason, responseId)
    }

    @Synchronized
    fun refreshed(response: BannerResponse) {
        if (!ended) remember(response, null)?.refreshSucceeded()
    }

    @Synchronized
    fun refreshFailed(code: String?, reason: String?) {
        if (!ended) slot.refreshFailed(code, reason)
    }

    @Synchronized
    fun impression(response: BannerResponse) {
        if (!ended) find(response)?.impression()
    }

    @Synchronized
    fun click(response: BannerResponse) {
        if (!ended) find(response)?.click()
    }

    @Synchronized
    fun paid(response: BannerResponse, micros: Long?, currency: String?, precision: String?, atMillis: Long) {
        val display = displays[response.id]
        if (display == null) {
            logger?.bannerDiagnostic(slot.slotId, "unknown_revenue_identity", response.id, micros?.toString(), currency)
            return
        }
        display.paid(micros, currency, precision, atMillis)
    }

    @Synchronized
    fun end() { ended = true }

    private fun remember(response: BannerResponse, requestId: String?): BannerDisplaySession? {
        val id = response.id?.takeIf(String::isNotBlank) ?: return find(response)
        displays[id]?.let { return it }
        val display = slot.newDisplay(id, requestId, response.source, response.adapterClassName) ?: return null
        displays[id] = display
        // ponytail: retain 32 displays, matching full-screen metadata; raise only with measured late-callback needs.
        // Evicted callbacks are diagnostic;
        // they never create a session from paid/impression or fall back to the latest display.
        if (displays.size > 32) displays.remove(displays.keys.first())
        return display
    }

    private fun find(response: BannerResponse): BannerDisplaySession? = displays[response.id].also {
        if (it == null) logger?.bannerDiagnostic(slot.slotId, "unknown_display_identity", response.id)
    }
}
