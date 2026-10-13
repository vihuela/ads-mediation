package com.cashcraft.ads.mediation.admob

import com.cashcraft.ads.mediation.internal.adMobRevenueAmount
import com.cashcraft.ads.mediation.internal.NativeAds
import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdMobRevenuePayload
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.revenueEventId
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.AdShowSession
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.dismissedResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class AdMobState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
}

/**
 * Minimal host facade for GMA Next-Gen app-open, interstitial, and rewarded ads.
 *
 * The SDK preloaders own cache lifetime and replenishment. Every public show attempt emits one
 * `ad_position`, followed by exactly one terminal `ad_impression` or `ad_show_fail` event.
 */
object AdMobAds {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fullScreenShowGate = FullScreenShowGate
    private val mobileAdsInitializationStarted = AtomicBoolean(false)
    private val initializationListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var state: AdMobState = AdMobState.NOT_INITIALIZED
        private set

    private lateinit var config: AdMobConfig
    private lateinit var application: Application
    private lateinit var events: AdEventDispatcher
    private lateinit var autoAppOpenController: AutoAppOpenController<AdShowSession>
    private val preloadDescriptors = mutableMapOf<String, PreloadDescriptor>()
    private val preloadLoadSessions = mutableMapOf<String, AdLoadSession>()

    private var initializationAttempt = 0L
    internal var initializationError: Throwable? = null
        private set
    internal val canRetryInitialization: Boolean
        get() = state == AdMobState.FAILED && initializationError is java.io.IOException

    /** Call once from `Application.onCreate`. Initialization itself runs off the main thread. */
    fun initialize(
        application: Application,
        config: AdMobConfig,
        onInitialized: (Boolean) -> Unit = {},
        initialActivity: Activity? = null,
    ) {
        synchronized(this) {
            when (state) {
                AdMobState.READY -> {
                    mainHandler.post { onInitialized(true) }
                    return
                }
                AdMobState.INITIALIZING -> {
                    initializationListeners += onInitialized
                    return
                }
                AdMobState.FAILED -> {
                    if (!canRetryInitialization) {
                        mainHandler.post { onInitialized(false) }
                        return
                    }
                    require(this.application === application && this.config == config) {
                        "Retry must reuse the installed AdMob configuration"
                    }
                    state = AdMobState.INITIALIZING
                    initializationError = null
                    initializationListeners += onInitialized
                    mobileAdsInitializationStarted.set(false)
                    onMain { beginMobileAdsInitialization() }
                    return
                }
                AdMobState.NOT_INITIALIZED -> Unit
            }
            state = AdMobState.INITIALIZING
            initializationListeners += onInitialized
            this.config = config
            this.application = application
            AdMobNextGenBidPrice.initialize(application)
            events = AdEventDispatcher(
                context = application,
                platform = AdPlatform.ADMOB,
                mediationMode = config.mediationMode,
                listener = config.eventListener,
                loggingEnabled = config.loggingEnabled,
                logTag = config.logTag,
            )
            autoAppOpenController = AutoAppOpenController(
                isEnabled = { this.config.autoShowAppOpen && this.config.ids.appOpenId.isNotBlank() },
                isProviderReady = { state == AdMobState.READY },
                providerFailureReason = {
                    state.takeUnless { it == AdMobState.READY }?.showFailureReason()
                },
                isAdAvailable = { AppOpenAdPreloader.isAdAvailable(PRELOAD_APP_OPEN) },
                shouldIgnoreActivity = { it.isGoogleMobileAdsActivity() },
                beginOpportunity = {
                    events.begin(
                        AdMobFormat.APP_OPEN,
                        this.config.appOpenPosition,
                        this.config.ids.appOpenId,
                    )
                },
                show = { activity, session ->
                    showAppOpenOnMain(
                        activity = activity,
                        position = this.config.appOpenPosition,
                        onResult = {},
                        session = session,
                    )
                },
                fail = { session, reason -> session.showFailure(reason) },
                noAdFailureReason = NO_AD_AVAILABLE,
            )
        }

        // Application.onCreate normally runs on the main thread. Register synchronously there so
        // a fast cold start cannot resume its first Activity before this callback is installed.
        onMain {
            AdLifecycleMonitor.install(application, initialActivity)
            beginMobileAdsInitialization()
        }
    }

    private fun beginMobileAdsInitialization() {
        if (!mobileAdsInitializationStarted.compareAndSet(false, true)) return
        state = AdMobState.INITIALIZING
        val token = ++initializationAttempt
        backgroundScope.launch {
            val result = runCatching {
                MobileAds.initialize(
                    application,
                    InitializationConfig.Builder(config.ids.applicationId)
                        .setNativeValidatorDisabled()
                        .build(),
                )
            }
            mainHandler.post {
                if (token == initializationAttempt && state == AdMobState.INITIALIZING) {
                    initializationError = result.exceptionOrNull()
                    finishInitialization(result.isSuccess)
                }
            }
        }
    }

    fun isReady(format: AdMobFormat): Boolean {
        if (state != AdMobState.READY || config.ids.adUnitId(format).isBlank()) return false
        return when (format) {
            AdMobFormat.NATIVE -> NativeAds.isReady(AdPlatform.ADMOB)
            AdMobFormat.APP_OPEN -> AppOpenAdPreloader.isAdAvailable(PRELOAD_APP_OPEN)
            AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.isAdAvailable(PRELOAD_INTERSTITIAL)
            AdMobFormat.REWARDED -> RewardedAdPreloader.isAdAvailable(PRELOAD_REWARDED)
        }
    }

    fun showAppOpen(
        activity: Activity,
        position: String = "manual",
        onResult: (AdMobShowResult) -> Unit = {},
    ) = onMain {
        if (!::config.isInitialized) {
            onResult(AdShowResult.Failed("sdk_not_initialized"))
        } else {
            showAppOpenOnMain(activity, position, onResult)
        }
    }

    fun showInterstitial(
        activity: Activity,
        position: String,
        onResult: (AdMobShowResult) -> Unit = {},
    ) = onMain {
        if (!::config.isInitialized) {
            onResult(AdShowResult.Failed("sdk_not_initialized"))
        } else {
            showInterstitialOnMain(activity, position, onResult)
        }
    }

    fun showRewarded(
        activity: Activity,
        position: String,
        onResult: (AdMobRewardResult) -> Unit,
    ) = onMain {
        if (!::config.isInitialized) {
            onResult(
                AdMobRewardResult(
                    rewardEarned = false,
                    showResult = AdShowResult.Failed("sdk_not_initialized"),
                    sessionId = null,
                ),
            )
        } else {
            showRewardedOnMain(activity, position, onResult)
        }
    }

    internal fun showBiddingAppOpen(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdMobShowResult) -> Unit,
    ) = onMain {
        val session = beginBiddingSession(AdMobFormat.APP_OPEN, position)
        onSessionStarted(session)
        showAppOpenOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingInterstitial(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdMobShowResult) -> Unit,
    ) = onMain {
        val session = beginBiddingSession(AdMobFormat.INTERSTITIAL, position)
        onSessionStarted(session)
        showInterstitialOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingRewarded(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdMobRewardResult) -> Unit,
    ) = onMain {
        val session = beginBiddingSession(AdMobFormat.REWARDED, position)
        onSessionStarted(session)
        showRewardedOnMain(activity, position, onResult, session)
    }

    internal fun beginBiddingSession(format: AdMobFormat, position: String): AdShowSession =
        events.begin(format, position, config.ids.adUnitId(format))

    private fun finishInitialization(success: Boolean) {
        if (state != AdMobState.INITIALIZING) return
        state = if (success) AdMobState.READY else AdMobState.FAILED
        if (success) startPreloading()
        val listeners = initializationListeners.toList()
        initializationListeners.clear()
        listeners.forEach { listener -> runCatching { listener(success) } }
        if (success) autoAppOpenController.onProviderInitialized()
    }

    private fun startPreloading() {
        val preloadCallback = object : PreloadCallback {
            override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                onMain {
                    preloadLoadSessions[preloadId]?.loaded(
                        adSource = responseInfo.loadedAdSourceResponseInfo?.name,
                        responseId = responseInfo.responseId,
                    )
                    if (preloadId == PRELOAD_APP_OPEN) {
                        autoAppOpenController.onAdAvailable()
                    }
                }
            }

            override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                onMain {
                    preloadLoadSessions[preloadId]?.failed(
                        result = adError.analyticsLoadResult(),
                        errorCode = adError.code.name,
                        reason = adError.message,
                        responseId = adError.responseInfo?.responseId,
                    )
                }
            }

            override fun onAdsExhausted(preloadId: String) {
                onMain { beginPreloadCycle(preloadId) }
            }
        }
        preloadDescriptors.clear()
        fun start(format: AdMobFormat, bufferSize: Int, begin: (String, PreloadConfiguration, PreloadCallback) -> Unit) {
            val id = config.ids.adUnitId(format)
            if (id.isBlank()) return
            val preloadId = format.preloadId()
            preloadDescriptors[preloadId] = PreloadDescriptor(format, id, bufferSize)
            beginPreloadCycle(preloadId)
            begin(preloadId, preloadConfiguration(id, bufferSize), preloadCallback)
        }
        start(AdMobFormat.APP_OPEN, config.preload.appOpen) { id, request, callback ->
            AppOpenAdPreloader.start(id, request, callback)
        }
        start(AdMobFormat.INTERSTITIAL, config.preload.interstitial) { id, request, callback ->
            InterstitialAdPreloader.start(id, request, callback)
        }
        start(AdMobFormat.REWARDED, config.preload.rewarded) { id, request, callback ->
            RewardedAdPreloader.start(id, request, callback)
        }
    }

    private fun beginPreloadCycle(preloadId: String) {
        val descriptor = preloadDescriptors[preloadId] ?: return
        preloadLoadSessions[preloadId] = events.beginLoad(
            format = descriptor.format,
            adUnitId = descriptor.adUnitId,
            bufferSize = descriptor.bufferSize,
        )
    }

    private fun LoadAdError.analyticsLoadResult(): String = when (code) {
        LoadAdError.ErrorCode.NO_FILL -> "no_fill"
        LoadAdError.ErrorCode.TIMEOUT -> "timeout"
        LoadAdError.ErrorCode.CANCELLED -> "cancelled"
        else -> "error"
    }

    private fun preloadConfiguration(adUnitId: String, bufferSize: Int) = PreloadConfiguration(
        AdRequest.Builder(adUnitId).build(),
        bufferSize,
    )

    internal fun bidAdSource(format: AdMobFormat): String? = runCatching {
        if (!isReady(format)) return@runCatching null
        if (format == AdMobFormat.NATIVE) return@runCatching NativeAds.bidAdSource(AdPlatform.ADMOB)
        val info = when (format) {
            AdMobFormat.APP_OPEN -> AppOpenAdPreloader.peekAdResponseInfo(PRELOAD_APP_OPEN)
            AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.peekAdResponseInfo(PRELOAD_INTERSTITIAL)
            AdMobFormat.REWARDED -> RewardedAdPreloader.peekAdResponseInfo(PRELOAD_REWARDED)
            AdMobFormat.NATIVE -> null
        }
        info?.loadedAdSourceResponseInfo?.name
    }.getOrNull()

    internal fun bidPrice(format: AdMobFormat): Double? {
        if (!isReady(format)) return null
        if (format == AdMobFormat.NATIVE) return NativeAds.bidPrice(AdPlatform.ADMOB)
        return AdMobNextGenBidPrice.peek(format, format.preloadId())
    }

    private fun AdMobFormat.preloadId(): String = when (this) {
        AdMobFormat.NATIVE -> error("Native ads use NativeAdLoader")
        AdMobFormat.APP_OPEN -> PRELOAD_APP_OPEN
        AdMobFormat.INTERSTITIAL -> PRELOAD_INTERSTITIAL
        AdMobFormat.REWARDED -> PRELOAD_REWARDED
    }

    private fun AdMobIds.adUnitId(format: AdMobFormat): String = when (format) {
        AdMobFormat.NATIVE -> nativeId.orEmpty()
        AdMobFormat.APP_OPEN -> appOpenId
        AdMobFormat.INTERSTITIAL -> interstitialId
        AdMobFormat.REWARDED -> rewardedId
    }

    private fun showAppOpenOnMain(
        activity: Activity,
        position: String,
        onResult: (AdMobShowResult) -> Unit,
        session: AdShowSession = events.begin(AdMobFormat.APP_OPEN, position, config.ids.appOpenId),
    ) {
        if (!canShow(activity, session, onResult = onResult)) return
        val ad = AppOpenAdPreloader.pollAd(PRELOAD_APP_OPEN)
        if (ad == null) {
            failBeforeShow(session, NO_AD_AVAILABLE, onResult)
            return
        }
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdImpression() = session.impression(ad.adSource(), ad.getResponseInfo().responseId)
            override fun onAdClicked() = session.emit(AdMobEventName.CLICK)
            override fun onAdPaid(value: AdValue) = session.paid(value, ad.getResponseInfo())
            override fun onAdDismissedFullScreenContent() {
                finishDismissed(ad, session, onResult)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                finishFailed(ad, session, fullScreenContentError, onResult)
            }
        }
        showSafely(ad, activity, session, onResult) { ad.show(activity) }
    }

    private fun showInterstitialOnMain(
        activity: Activity,
        position: String,
        onResult: (AdMobShowResult) -> Unit,
        session: AdShowSession = events.begin(
            AdMobFormat.INTERSTITIAL,
            position,
            config.ids.interstitialId,
        ),
    ) {
        if (!canShow(activity, session, onResult)) return
        val ad = InterstitialAdPreloader.pollAd(PRELOAD_INTERSTITIAL)
        if (ad == null) {
            failBeforeShow(session, NO_AD_AVAILABLE, onResult)
            return
        }
        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdImpression() = session.impression(ad.adSource(), ad.getResponseInfo().responseId)
            override fun onAdClicked() = session.emit(AdMobEventName.CLICK)
            override fun onAdPaid(value: AdValue) = session.paid(value, ad.getResponseInfo())
            override fun onAdDismissedFullScreenContent() {
                finishDismissed(ad, session, onResult)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                finishFailed(ad, session, fullScreenContentError, onResult)
            }
        }
        showSafely(ad, activity, session, onResult) { ad.show(activity) }
    }

    private fun showRewardedOnMain(
        activity: Activity,
        position: String,
        onResult: (AdMobRewardResult) -> Unit,
        session: AdShowSession = events.begin(AdMobFormat.REWARDED, position, config.ids.rewardedId),
    ) {
        val showResultCallback: (AdMobShowResult) -> Unit = { result ->
            onResult(
                AdMobRewardResult(
                    rewardEarned = false,
                    showResult = result,
                    sessionId = session.sessionId,
                ),
            )
        }
        if (!canShow(activity, session, showResultCallback)) return
        val ad = RewardedAdPreloader.pollAd(PRELOAD_REWARDED)
        if (ad == null) {
            failBeforeShow(session, NO_AD_AVAILABLE, showResultCallback)
            return
        }
        var rewardEarned = false
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdImpression() = session.impression(ad.adSource(), ad.getResponseInfo().responseId)
            override fun onAdClicked() = session.emit(AdMobEventName.CLICK)
            override fun onAdPaid(value: AdValue) = session.paid(value, ad.getResponseInfo())
            override fun onAdDismissedFullScreenContent() {
                finishDismissed(ad, session) { result ->
                    onResult(AdMobRewardResult(rewardEarned, result, session.sessionId))
                }
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                finishFailed(ad, session, fullScreenContentError) { result ->
                    onResult(
                        AdMobRewardResult(
                            rewardEarned = false,
                            showResult = result,
                            sessionId = session.sessionId,
                        ),
                    )
                }
            }
        }
        showSafely(ad, activity, session, showResultCallback) {
            ad.show(activity) { rewardItem ->
                rewardEarned = true
                session.emit(
                    AdMobEventName.REWARD_EARNED,
                    reason = "${rewardItem.type}:${rewardItem.amount}",
                )
            }
        }
    }

    private fun canShow(
        activity: Activity,
        session: AdShowSession,
        onResult: (AdMobShowResult) -> Unit,
    ): Boolean {
        val reason = fullScreenShowGate.tryAcquire(
            attempt = session,
            activity = activity,
            providerFailureReason = state.takeUnless { it == AdMobState.READY }?.showFailureReason()
                ?: "ad_not_configured".takeIf { session.adUnitId.isBlank() },
        )
        if (reason != null) {
            session.showFailure(reason)
            runCatching { onResult(AdShowResult.Failed(reason)) }
            return false
        }
        return true
    }

    private fun failBeforeShow(
        session: AdShowSession,
        reason: String,
        onResult: (AdMobShowResult) -> Unit,
    ) {
        fullScreenShowGate.release(session)
        session.showFailure(reason)
        runCatching { onResult(AdShowResult.Failed(reason)) }
    }

    private fun showSafely(
        ad: com.google.android.libraries.ads.mobile.sdk.common.Ad,
        activity: Activity,
        session: AdShowSession,
        onResult: (AdMobShowResult) -> Unit,
        show: () -> Unit,
    ) {
        runCatching(show).onFailure { error ->
            ad.destroy()
            failBeforeShow(session, error.message ?: "show_exception", onResult)
        }
    }

    private fun finishDismissed(
        ad: com.google.android.libraries.ads.mobile.sdk.common.Ad,
        session: AdShowSession,
        onResult: (AdMobShowResult) -> Unit,
    ) {
        val result = session.dismissedResult()
        session.emit(AdMobEventName.DISMISS)
        ad.destroy()
        fullScreenShowGate.release(session)
        runCatching { onResult(result) }
    }

    private fun finishFailed(
        ad: com.google.android.libraries.ads.mobile.sdk.common.Ad,
        session: AdShowSession,
        error: FullScreenContentError,
        onResult: (AdMobShowResult) -> Unit,
    ) {
        val reason = error.message.ifBlank { "show_failed" }
        session.showFailure(reason, error.code.toString())
        ad.destroy()
        fullScreenShowGate.release(session)
        runCatching { onResult(AdShowResult.Failed(reason)) }
    }

    internal fun nativePaid(session: AdShowSession, value: AdValue, responseInfo: ResponseInfo) =
        session.paid(value, responseInfo)

    private fun AdShowSession.paid(value: AdValue, responseInfo: ResponseInfo) {
        val adSourceInfo = responseInfo.loadedAdSourceResponseInfo
        val adapterClassName = adSourceInfo?.adapterClassName ?: responseInfo.adapterClassName
        val amount = adMobRevenueAmount(value) ?: return
        val valueMicros = amount.valueMicros
        val currencyCode = amount.currencyCode
        val adNetwork = adSourceInfo?.name?.trim()?.takeIf(String::isNotEmpty)
        val impressionId = responseInfo.responseId?.trim()?.takeIf(String::isNotEmpty)
        val precisionType = value.precisionType.name.takeIf(String::isNotEmpty)
        emit(
            AdMobEventName.PAID,
            adSource = adNetwork,
            responseId = impressionId,
            value = amount.value,
            valueMicros = valueMicros,
            currency = currencyCode,
            mediationAdapterClassName = adapterClassName,
            precisionType = precisionType,
        )
        runCatching {
            config.revenueListener.onRevenuePaid(
                AdMobRevenuePayload(
                    eventId = revenueEventId(AdPlatform.ADMOB, impressionId, sessionId),
                    occurredAtMillis = System.currentTimeMillis(),
                    mediationMode = mediationMode,
                    format = format,
                    sessionId = sessionId,
                    position = position,
                    placementId = adUnitId,
                    valueMicros = valueMicros,
                    currencyCode = currencyCode,
                    adNetwork = adNetwork,
                    impressionId = impressionId,
                    mediationAdapterClassName = adapterClassName,
                    precisionType = precisionType,
                ),
            )
        }
    }

    private fun com.google.android.libraries.ads.mobile.sdk.common.Ad.adSource(): String? =
        getResponseInfo().loadedAdSourceResponseInfo?.name

    private fun Activity.isGoogleMobileAdsActivity(): Boolean =
        javaClass.name.startsWith("com.google.android.libraries.ads.mobile.sdk.")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private const val PRELOAD_APP_OPEN = "lcb_admob_app_open"
    private const val PRELOAD_INTERSTITIAL = "lcb_admob_interstitial"
    private const val PRELOAD_REWARDED = "lcb_admob_rewarded"
    private const val NO_AD_AVAILABLE = "no_preloaded_ad"

    private data class PreloadDescriptor(
        val format: AdMobFormat,
        val adUnitId: String,
        val bufferSize: Int,
    )
}

/** Keeps failed show opportunities distinguishable from requests made while SDK startup is pending. */
internal fun AdMobState.showFailureReason(): String = when (this) {
    AdMobState.NOT_INITIALIZED -> "sdk_not_initialized"
    AdMobState.INITIALIZING -> "sdk_initializing"
    AdMobState.FAILED -> "sdk_initialization_failed"
    AdMobState.READY -> "sdk_not_ready"
}
