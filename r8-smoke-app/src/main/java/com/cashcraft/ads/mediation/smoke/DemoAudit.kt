package com.cashcraft.ads.mediation.smoke

import android.util.Log
import com.cashcraft.ads.mediation.*
import org.json.JSONArray
import org.json.JSONObject

/** Records exactly what the public listener receives, including candidate prices omitted by analyticsParameters. */
internal object DemoAudit : AdEventListener {
    private val sessions = linkedMapOf<String, MutableList<AdEventName>>()
    private var lastBid = "No auction yet"
    fun onRevenue(payload: AdRevenuePayload) {
        Log.i("AdsRevenueAudit", JSONObject()
            .put("event_id", payload.eventId)
            .put("session_id", payload.sessionId)
            .put("ad_platform", payload.platform.analyticsValue)
            .put("ad_type", payload.format.analyticsValue)
            .put("value_micros", payload.valueMicros)
            .put("currency", payload.currencyCode)
            .toString())
    }
    @Synchronized override fun onEvent(event: AdEvent) {
        val json = JSONObject(event.analyticsParameters()).put("event", event.name.analyticsName)
        if (event.name == AdEventName.BID_RESULT) {
            json.put("candidates", JSONArray(event.bidCandidates.map {
                JSONObject().put("platform", it.platform.analyticsValue).put("format", it.format.analyticsValue)
                    .put("available", it.available).put("price_usd", it.priceUsd ?: JSONObject.NULL)
            }))
            lastBid = "Winner: ${event.winnerPlatform?.analyticsValue ?: "none"}/${event.winnerFormat?.analyticsValue ?: "none"}\n" +
                event.bidCandidates.joinToString("\n") {
                    "${it.platform.analyticsValue}/${it.format.analyticsValue}: ${if (it.available) "ready" else "empty"}, USD ${it.priceUsd ?: "null"}"
                }
        }
        if (event.name in setOf(AdEventName.POSITION, AdEventName.IMPRESSION, AdEventName.SHOW_FAIL)) {
            sessions.getOrPut(event.sessionId) { mutableListOf() }.add(event.name)
        }
        Log.i("AdsDemoAudit", json.toString())
    }
    @Synchronized fun summary(): String {
        val counts = sessions.values.flatten().groupingBy { it }.eachCount()
        val pending = sessions.values.count { AdEventName.POSITION in it && AdEventName.IMPRESSION !in it && AdEventName.SHOW_FAIL !in it }
        val invalid = sessions.values.count { events ->
            events.count { it == AdEventName.POSITION } != 1 ||
                events.count { it == AdEventName.IMPRESSION || it == AdEventName.SHOW_FAIL } > 1
        }
        return "position ${counts[AdEventName.POSITION] ?: 0} = impression ${counts[AdEventName.IMPRESSION] ?: 0} + fail ${counts[AdEventName.SHOW_FAIL] ?: 0} + pending $pending\n" +
            "Invalid sessions: $invalid\n$lastBid"
    }
}
