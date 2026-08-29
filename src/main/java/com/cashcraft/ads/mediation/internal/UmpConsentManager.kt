package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.cashcraft.ads.mediation.AdConsentSnapshot
import com.cashcraft.ads.mediation.AdConsentStatus
import com.cashcraft.ads.mediation.UmpConsentConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread UMP boundary shared by every mediation provider. UMP owns persistence. */
internal class UmpConsentManager(
    context: Context,
    private val config: UmpConsentConfig,
    loggingEnabled: Boolean,
    logTag: String,
) {
    private val consentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val logger = AdsModuleLogger(loggingEnabled, logTag)
    private val requestStarted = AtomicBoolean(false)
    private val gateCompleted = AtomicBoolean(false)

    val snapshot: AdConsentSnapshot
        get() {
            if (!config.enabled) return AdConsentSnapshot.DISABLED
            return AdConsentSnapshot(
                status = consentInformation.consentStatus.toModuleStatus(),
                canRequestAds = consentInformation.canRequestAds(),
                privacyOptionsRequired = isPrivacyOptionsRequired,
            )
        }

    val isPrivacyOptionsRequired: Boolean
        get() = config.enabled &&
            consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Refreshes consent once per process launch before any provider initialization or ad request. */
    fun gatherConsent(
        activity: Activity,
        onAdsAllowed: () -> Unit,
        onAdsUnavailable: (String) -> Unit,
    ) {
        if (!config.enabled) {
            logger.consent("disabled", snapshot)
            deliverAdsAllowed(onAdsAllowed)
            return
        }
        if (!requestStarted.compareAndSet(false, true)) return

        val parameters = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(config.tagForUnderAgeOfConsent)
            .build()
        logger.consent("request_started", snapshot)
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                logger.consent("request_succeeded", snapshot)
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    logger.consent("form_completed", snapshot, formError?.message)
                    completeGate(
                        errorMessage = formError?.message,
                        onAdsAllowed = onAdsAllowed,
                        onAdsUnavailable = onAdsUnavailable,
                    )
                }
            },
            { requestError ->
                logger.consent("request_failed", snapshot, requestError.message)
                completeGate(
                    errorMessage = requestError.message,
                    onAdsAllowed = onAdsAllowed,
                    onAdsUnavailable = onAdsUnavailable,
                )
            },
        )
    }

    fun showPrivacyOptions(
        activity: Activity,
        onDismissed: (errorMessage: String?) -> Unit,
    ) {
        if (!config.enabled) {
            onDismissed(null)
            return
        }
        logger.consent("privacy_options_started", snapshot)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            logger.consent("privacy_options_completed", snapshot, formError?.message)
            onDismissed(formError?.message)
        }
    }

    private fun completeGate(
        errorMessage: String?,
        onAdsAllowed: () -> Unit,
        onAdsUnavailable: (String) -> Unit,
    ) {
        if (consentInformation.canRequestAds()) {
            deliverAdsAllowed(onAdsAllowed)
        } else if (gateCompleted.compareAndSet(false, true)) {
            onAdsUnavailable(errorMessage ?: "consent_not_obtained")
        }
    }

    private fun deliverAdsAllowed(onAdsAllowed: () -> Unit) {
        if (gateCompleted.compareAndSet(false, true)) onAdsAllowed()
    }

    private fun Int.toModuleStatus(): AdConsentStatus = when (this) {
        ConsentInformation.ConsentStatus.REQUIRED -> AdConsentStatus.REQUIRED
        ConsentInformation.ConsentStatus.NOT_REQUIRED -> AdConsentStatus.NOT_REQUIRED
        ConsentInformation.ConsentStatus.OBTAINED -> AdConsentStatus.OBTAINED
        else -> AdConsentStatus.UNKNOWN
    }
}
