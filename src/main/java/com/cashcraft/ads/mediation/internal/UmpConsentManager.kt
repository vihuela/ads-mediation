package com.cashcraft.ads.mediation.internal

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.android.ump.FormError
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
    // 请求发出后即按上次会话的授权结果放行；本次请求的结果不再用于否决已启动的提供方。
    private var earlyAdsAllowed = false
    // 表单显示期间开屏不计时；UMP 不回调"已显示"，表单加载耗时一并排除。
    var formShowing = false
        private set

    private var attempt = 0L
    var failure: ConsentInitializationFailure? = null
        private set

    /** Releases only the failed request gate. UMP persistence and user consent are untouched. */
    fun prepareRetry(): Boolean {
        if (failure?.retryable != true) return false
        attempt++
        failure = null
        earlyAdsAllowed = false
        formShowing = false
        requestStarted.set(false)
        gateCompleted.set(false)
        return true
    }

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

    /** Refreshes consent before provider initialization; only a failed transient gate may be retried. */
    fun gatherConsent(
        activity: Activity,
        onConsentUpdated: () -> Unit,
        onAdsAllowed: () -> Unit,
        onAdsUnavailable: (String) -> Unit,
    ) {
        if (!config.enabled) {
            logger.consent("disabled", snapshot)
            deliverAdsAllowed(onAdsAllowed)
            return
        }
        if (!requestStarted.compareAndSet(false, true)) return

        val token = ++attempt
        val parameters = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(config.tagForUnderAgeOfConsent)
            .build()
        logger.consent("request_started", snapshot)
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                if (token != attempt || gateCompleted.get()) return@requestConsentInfoUpdate
                logger.consent("request_succeeded", snapshot)
                onConsentUpdated()
                formShowing = consentInformation.consentStatus == ConsentInformation.ConsentStatus.REQUIRED
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    formShowing = false
                    if (token != attempt || gateCompleted.get()) return@loadAndShowConsentFormIfRequired
                    logger.consent("form_completed", snapshot, formError?.message)
                    onConsentUpdated()
                    completeGate(
                        error = formError,
                        stage = "ump_form",
                        onAdsAllowed = onAdsAllowed,
                        onAdsUnavailable = onAdsUnavailable,
                    )
                }
            },
            { requestError ->
                if (token != attempt || gateCompleted.get()) return@requestConsentInfoUpdate
                logger.consent("request_failed", snapshot, requestError.message)
                onConsentUpdated()
                completeGate(
                    error = requestError,
                    stage = "ump_request",
                    onAdsAllowed = onAdsAllowed,
                    onAdsUnavailable = onAdsUnavailable,
                )
            },
        )
        // UMP 官方做法：requestConsentInfoUpdate 调用后 canRequestAds() 即反映上次会话的授权，
        // 无需等待网络回调；表单与状态刷新仍照常进行，下游启动提供方是幂等的。
        if (consentInformation.canRequestAds()) {
            earlyAdsAllowed = true
            logger.consent("early_can_request_ads", snapshot)
            onAdsAllowed()
        }
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
        error: FormError?,
        stage: String,
        onAdsAllowed: () -> Unit,
        onAdsUnavailable: (String) -> Unit,
    ) {
        if (consentInformation.canRequestAds()) {
            deliverAdsAllowed(onAdsAllowed)
        } else if (gateCompleted.compareAndSet(false, true)) {
            // 授权变化已通知加载方；已启动的提供方不改判初始化失败。
            if (earlyAdsAllowed) return
            failure = ConsentInitializationFailure(stage, error?.errorCode, error?.message ?: "consent_not_obtained")
            onAdsUnavailable(failure!!.message)
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

/** ErrorCode values are defined by UMP 4.0.0; unknown/configuration/user outcomes are terminal. */
internal data class ConsentInitializationFailure(val stage: String, val code: Int?, val message: String) {
    val retryable: Boolean
        get() = code == FormError.ErrorCode.INTERNET_ERROR || code == FormError.ErrorCode.INTERNAL_ERROR ||
            code == FormError.ErrorCode.TIME_OUT
}
