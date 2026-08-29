package com.cashcraft.ads.mediation

/** Stable module-owned consent values; callers never depend on UMP SDK types. */
enum class AdConsentStatus(val analyticsValue: String) {
    UNKNOWN("unknown"),
    REQUIRED("required"),
    NOT_REQUIRED("not_required"),
    OBTAINED("obtained"),
    DISABLED("disabled"),
}

/** Current provider consent state for request gating and host UI decisions. */
data class AdConsentSnapshot(
    val status: AdConsentStatus,
    val canRequestAds: Boolean,
    val privacyOptionsRequired: Boolean,
) {
    companion object {
        val UNKNOWN = AdConsentSnapshot(
            status = AdConsentStatus.UNKNOWN,
            canRequestAds = false,
            privacyOptionsRequired = false,
        )

        val DISABLED = AdConsentSnapshot(
            status = AdConsentStatus.DISABLED,
            canRequestAds = true,
            privacyOptionsRequired = false,
        )
    }
}
