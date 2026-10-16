package com.cashcraft.ads.mediation.internal.topon

import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.internal.AdShowSession
import java.util.UUID

/** Main-thread ownership and payment identities; closing a show does not close its revenue. */
internal class TopOnRevenueTracker {
    data class Identity(val showId: String?, val token: String?, val format: AdFormat, val placement: String)
    data class Payment(val eventId: String, val session: AdShowSession?)
    private data class Show(val token: String, val session: AdShowSession, var showId: String? = null)

    private val shows = linkedMapOf<String, Show>()
    private val paid = linkedMapOf<String, Set<String>>()
    private val conflicts = linkedSetOf<String>()

    fun register(session: AdShowSession): String {
        val token = UUID.randomUUID().toString()
        shows[token] = Show(token, session)
        while (shows.size > MAX_SHOWS) shows.remove(shows.keys.first())
        return token
    }

    fun session(identity: Identity): AdShowSession? = resolve(identity)?.session

    /** Inspect ownership without registering a showId or merging payment identities. */
    fun peekSession(identity: Identity): AdShowSession? = find(identity)?.session

    fun confirm(identity: Identity, session: AdShowSession): Boolean =
        resolve(identity)?.session === session

    private fun resolve(identity: Identity): Show? = find(identity)?.also { show ->
        identity.showId?.let { show.showId = it }
        // A previously delivered orphan and its subsequently confirmed token are one payment.
        linkPaidAliases(keys(identity, show))
    }

    private fun find(identity: Identity): Show? {
        if (isConflict(identity)) return null
        val byId = identity.showId?.let { id -> shows.values.firstOrNull {
            it.showId == id && it.session.format == identity.format && it.session.adUnitId == identity.placement
        } }
        val byToken = identity.token?.let(shows::get)
        // A supplied but stale/foreign token cannot be overridden by a coincidentally matching ID.
        if (identity.token != null && byToken == null) {
            if (byId != null) rememberConflict(identity)
            return null
        }
        if (byId != null && byToken != null && byId !== byToken) return rememberConflict(identity)
        val show = byToken ?: byId ?: return null
        if (show.session.format != identity.format || show.session.adUnitId != identity.placement) {
            return rememberConflict(identity)
        }
        if (identity.showId != null && show.showId != null && show.showId != identity.showId) {
            return rememberConflict(identity)
        }
        return show
    }

    /** Read-only: duplicate notifications must not take space away from pending new payments. */
    fun hasPayment(identity: Identity): Boolean {
        val keys = keys(identity, null)
        return paid.values.any { aliases -> aliases.any(keys::contains) }
    }

    fun claim(identity: Identity): Payment? {
        val show = resolve(identity)
        val keys = keys(identity, show)
        if (linkPaidAliases(keys)) return null
        val eventId = when {
            keys.any { it.startsWith("conflict:") } -> "topon:unattributed:${UUID.randomUUID()}"
            identity.showId != null -> "topon:${scope(identity)}:id:${identity.showId}"
            identity.token != null -> "topon:${scope(identity)}:show:${identity.token}"
            else -> "topon:unattributed:${UUID.randomUUID()}"
        }
        if (keys.isNotEmpty()) paid[eventId] = keys
        while (paid.size > MAX_PAYMENTS) paid.remove(paid.keys.first())
        return Payment(eventId, show?.session)
    }

    private fun keys(identity: Identity, show: Show?): Set<String> {
        val scope = scope(identity)
        // Conflicting identities must not consume either innocent show's payment identity.
        if (isConflict(identity)) return setOf(conflictKey(identity))
        return buildSet {
            (show?.showId ?: identity.showId)?.let { add("id:$scope:$it") }
            (show?.token ?: identity.token)?.let { add("token:$scope:$it") }
        }
    }

    private fun scope(identity: Identity): String =
        "${identity.format.analyticsValue}:${identity.placement.length}:${identity.placement}"

    private fun conflictKey(identity: Identity): String =
        "conflict:${scope(identity)}:${identity.showId?.length}:${identity.showId}:${identity.token?.length}:${identity.token}"

    private fun isConflict(identity: Identity): Boolean {
        val key = conflictKey(identity)
        return key in conflicts || paid.values.any { key in it }
    }

    private fun rememberConflict(identity: Identity): Show? {
        conflicts += conflictKey(identity)
        while (conflicts.size > MAX_PAYMENTS) conflicts.remove(conflicts.first())
        return null
    }

    private fun linkPaidAliases(keys: Set<String>): Boolean {
        val existing = paid.entries.firstOrNull { (_, aliases) -> aliases.any(keys::contains) } ?: return false
        existing.setValue(existing.value + keys)
        return true
    }

    companion object {
        // shortcut: bounded process-local history; older identities become unattributed, not current.
        const val MAX_SHOWS = 32
        const val MAX_PAYMENTS = 128
    }
}
