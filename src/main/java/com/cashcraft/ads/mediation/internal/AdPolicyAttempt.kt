package com.cashcraft.ads.mediation.internal

import android.util.Log
import com.cashcraft.ads.mediation.AdBlockInfo
import com.cashcraft.ads.mediation.AdBlockReason
import com.cashcraft.ads.mediation.AdPolicyCheckResult
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.Ads
import java.util.UUID

/** One display opportunity owns its reservation and its single terminal block notification. */
internal class AdPolicyAttempt(val request: AdPolicyRequest) {
    /** Stable across provider/format fallback and late callbacks; use in material diagnostics. */
    val id: String = UUID.randomUUID().toString()
    private var completed = false
    private var reserved = false
    var blocked: AdBlockReason? = null
        private set
    var hasImpression: Boolean = false
        private set

    @Synchronized
    fun check(): AdPolicyCheckResult = remember {
        if (reserved) Ads.policyChecker?.reserve(id, request) ?: AdPolicyCheckResult.Passed
        else Ads.policyChecker?.check(request) ?: AdPolicyCheckResult.Passed
    }

    @Synchronized
    fun reserve(): AdPolicyCheckResult = remember {
        (Ads.policyChecker?.reserve(id, request) ?: AdPolicyCheckResult.Passed).also {
            if (it == AdPolicyCheckResult.Passed && !reserved) {
                reserved = true
                Ads.onPolicyUsageChanged()
            }
        }
    }

    private inline fun remember(check: () -> AdPolicyCheckResult): AdPolicyCheckResult {
        blocked?.let { return AdPolicyCheckResult.Blocked(it) }
        if (hasImpression) return AdPolicyCheckResult.Passed
        return check().also {
            if (it is AdPolicyCheckResult.Blocked) {
                blocked = it.reason
                Ads.policyChecker?.let { checker ->
                    Log.w("AdsPolicy", checker.blockedDiagnostic(id, request, it.reason))
                }
            }
        }
    }

    @Synchronized
    fun impression() {
        if (hasImpression) return
        hasImpression = true
        Ads.policyChecker?.impression(id, request.sceneType)
        if (completed && request.fullscreen) Ads.policyChecker?.fullscreenClosed()
        Ads.onPolicyUsageChanged()
    }

    /** A real SDK click remains usage even when the page opportunity has completed. */
    @Synchronized
    fun click() {
        Ads.policyChecker?.click(request.sceneType)
        Ads.onPolicyUsageChanged()
    }

    @Synchronized
    fun complete() {
        if (completed) return
        completed = true
        Ads.policyChecker?.release(id)
        if (hasImpression && request.fullscreen) Ads.policyChecker?.fullscreenClosed()
        blocked?.let { reason -> request.position?.let { Ads.notifyAdBlocked(AdBlockInfo(it, reason)) } }
        if (reserved || hasImpression) Ads.onPolicyUsageChanged()
    }

    fun result(result: AdShowResult): AdShowResult = blocked?.let(AdShowResult::Blocked) ?: result
}
