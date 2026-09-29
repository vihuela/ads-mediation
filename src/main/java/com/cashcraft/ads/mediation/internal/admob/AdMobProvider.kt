package com.cashcraft.ads.mediation.admob

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
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
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.dismissedResult
import java.util.Locale
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
    private val preloadStartedAt = mutableMapOf<String, Long>()
    private val responseLoadBounds = mutableMapOf<String, Long>()
    private val pendingAds = mutableMapOf<AdMobFormat, RetainedAd<Ad>>()
    private val takenAds = mutableMapOf<Ad, RetainedAd<Ad>>()

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
                    mainHandler.post { onInitialized(false) }
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
                isEnabled = { this.config.autoShowAppOpen },
                isProviderReady = { state == AdMobState.READY },
                providerFailureReason = {
                    state.takeUnless { it == AdMobState.READY }?.showFailureReason()
                },
                isAdAvailable = { isReady(AdMobFormat.APP_OPEN) },
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
        backgroundScope.launch {
            val initialized = runCatching {
                MobileAds.initialize(
                    application,
                    InitializationConfig.Builder(config.ids.applicationId).build(),
                )
            }.isSuccess
            mainHandler.post { finishInitialization(initialized) }
        }
    }

    private val loadFailures = mutableMapOf<String, Long>()
    internal fun loadFailureVersion(format: AdMobFormat): Long =
        if (format == AdMobFormat.BANNER) 0L else loadFailures[format.preloadId()] ?: 0L

    fun isReady(format: AdMobFormat): Boolean {
        if (format == AdMobFormat.BANNER) return false
        if (state != AdMobState.READY) return false
        if (pendingAd(format) != null) return true
        return when (format) {
            AdMobFormat.BANNER -> false
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
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ) = onMain {
        val session = beginBiddingSession(AdMobFormat.APP_OPEN, position, attempt, onSessionCreated)
        onSessionStarted(session)
        showAppOpenOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingInterstitial(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdMobShowResult) -> Unit,
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ) = onMain {
        val session = beginBiddingSession(AdMobFormat.INTERSTITIAL, position, attempt, onSessionCreated)
        onSessionStarted(session)
        showInterstitialOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingRewarded(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdMobRewardResult) -> Unit,
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ) = onMain {
        val session = beginBiddingSession(AdMobFormat.REWARDED, position, attempt, onSessionCreated)
        onSessionStarted(session)
        showRewardedOnMain(activity, position, onResult, session)
    }

    internal fun beginBiddingSession(
        format: AdMobFormat,
        position: String,
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ): AdShowSession = events.begin(format, position, config.ids.adUnitId(format), attempt, onSessionCreated)

    private fun finishInitialization(success: Boolean) {
        state = if (success) AdMobState.READY else AdMobState.FAILED
        if (success) startPreloading()
        initializationListeners.forEach { listener -> runCatching { listener(success) } }
        initializationListeners.clear()
        if (success) autoAppOpenController.onProviderInitialized()
    }

    private fun startPreloading() {
        val preloadCallback = object : PreloadCallback {
            override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                mainHandler.post {
                    // Matching is by response ID, never by callback/poll order.
                    // ponytail: preload-start age discards later fills early; use a public
                    // per-ad load timestamp if the SDK eventually exposes one.
                    responseLoadBounds.entries.removeAll {
                        SystemClock.elapsedRealtime() - it.value >= APP_OPEN_MAX_AGE_MILLIS
                    }
                    val responseId = responseInfo.responseId
                    val loadBound = preloadStartedAt[preloadId]
                    if (!responseId.isNullOrBlank() && loadBound != null) {
                        responseLoadBounds[responseId] = loadBound
                    }
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
                mainHandler.post {
                    loadFailures[preloadId] = (loadFailures[preloadId] ?: 0L) + 1
                    preloadLoadSessions[preloadId]?.failed(
                        result = adError.analyticsLoadResult(),
                        errorCode = adError.code.name,
                        reason = adError.message,
                        responseId = adError.responseInfo?.responseId,
                    )
                }
            }

            override fun onAdsExhausted(preloadId: String) {
                mainHandler.post { beginPreloadCycle(preloadId) }
            }
        }
        preloadDescriptors.clear()
        preloadDescriptors[PRELOAD_APP_OPEN] = PreloadDescriptor(
            format = AdMobFormat.APP_OPEN,
            adUnitId = config.ids.appOpenId,
            bufferSize = config.preload.appOpen,
        )
        preloadDescriptors[PRELOAD_INTERSTITIAL] = PreloadDescriptor(
            format = AdMobFormat.INTERSTITIAL,
            adUnitId = config.ids.interstitialId,
            bufferSize = config.preload.interstitial,
        )
        preloadDescriptors[PRELOAD_REWARDED] = PreloadDescriptor(
            format = AdMobFormat.REWARDED,
            adUnitId = config.ids.rewardedId,
            bufferSize = config.preload.rewarded,
        )
        preloadDescriptors.keys.forEach(::beginPreloadCycle)
        preloadStartedAt[PRELOAD_APP_OPEN] = SystemClock.elapsedRealtime()
        AppOpenAdPreloader.start(
            PRELOAD_APP_OPEN,
            preloadConfiguration(config.ids.appOpenId, config.preload.appOpen),
            preloadCallback,
        )
        preloadStartedAt[PRELOAD_INTERSTITIAL] = SystemClock.elapsedRealtime()
        InterstitialAdPreloader.start(
            PRELOAD_INTERSTITIAL,
            preloadConfiguration(config.ids.interstitialId, config.preload.interstitial),
            preloadCallback,
        )
        preloadStartedAt[PRELOAD_REWARDED] = SystemClock.elapsedRealtime()
        RewardedAdPreloader.start(
            PRELOAD_REWARDED,
            preloadConfiguration(config.ids.rewardedId, config.preload.rewarded),
            preloadCallback,
        )
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

    internal fun bidPrice(format: AdMobFormat): Double? {
        if (format == AdMobFormat.BANNER) return null
        pendingAd(format)?.let { return it.priceUsd }
        if (!isReady(format)) return null
        return AdMobNextGenBidPrice.peek(format, format.preloadId())
    }

    private fun pendingAd(format: AdMobFormat): RetainedAd<Ad>? = synchronized(pendingAds) {
        val pending = pendingAds[format] ?: return@synchronized null
        if (pending.isUsable(SystemClock.elapsedRealtime())) return@synchronized pending
        pendingAds.remove(format)
        onMain { pending.ad.destroy() }
        null
    }

    private fun takeAd(format: AdMobFormat): Ad? {
        // Remove atomically before use: an off-main isReady() may expire a retained entry.
        val pending = synchronized(pendingAds) { pendingAds.remove(format) }
        if (pending != null) {
            if (pending.isUsable(SystemClock.elapsedRealtime())) {
                takenAds[pending.ad] = pending
                return pending.ad
            }
            pending.ad.destroy()
        }
        val ad: Ad = when (format) {
            AdMobFormat.BANNER -> return null
            AdMobFormat.APP_OPEN -> AppOpenAdPreloader.pollAd(PRELOAD_APP_OPEN)
            AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.pollAd(PRELOAD_INTERSTITIAL)
            AdMobFormat.REWARDED -> RewardedAdPreloader.pollAd(PRELOAD_REWARDED)
        } ?: return null
        val bound = responseLoadBounds.remove(ad.getResponseInfo().responseId)
        takenAds[ad] = RetainedAd(
            ad, bound,
            // Next-Gen documents four hours for app-open. It exposes no retained-object TTL
            // for interstitial/rewarded: unknown validity must never become a reusable ad.
            if (format == AdMobFormat.APP_OPEN) APP_OPEN_MAX_AGE_MILLIS else null,
            // The existing queue price cannot be proved to belong to this polled response.
            priceUsd = null,
        )
        return ad
    }

    private fun retainUnshownAd(format: AdMobFormat, ad: Ad) {
        when (ad) {
            is AppOpenAd -> ad.adEventCallback = null
            is InterstitialAd -> ad.adEventCallback = null
            is RewardedAd -> ad.adEventCallback = null
        }
        val retained = takenAds.remove(ad)?.copy(wasRetained = true)
        if (retained?.isUsable(SystemClock.elapsedRealtime()) == true) {
            synchronized(pendingAds) { pendingAds.put(format, retained) }?.ad?.destroy()
        } else {
            ad.destroy()
        }
    }

    private fun AdMobFormat.preloadId(): String = when (this) {
        AdMobFormat.BANNER -> error("Banner does not use a preload buffer")
        AdMobFormat.APP_OPEN -> PRELOAD_APP_OPEN
        AdMobFormat.INTERSTITIAL -> PRELOAD_INTERSTITIAL
        AdMobFormat.REWARDED -> PRELOAD_REWARDED
    }

    private fun AdMobIds.adUnitId(format: AdMobFormat): String = when (format) {
        AdMobFormat.BANNER -> error("Banner requires an explicit ad unit ID")
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
        val ad = takeAd(AdMobFormat.APP_OPEN) as? AppOpenAd
        if (ad == null) {
            failBeforeShow(session, NO_AD_AVAILABLE, onResult)
            return
        }
        val responseInfo = ad.getResponseInfo()
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdImpression() = onMain {
                session.impression(responseInfo.loadedAdSourceResponseInfo?.name, responseInfo.responseId)
            }
            override fun onAdClicked() = onMain { session.emit(AdMobEventName.CLICK) }
            override fun onAdPaid(value: AdValue) = onMain { session.paid(value, responseInfo) }
            override fun onAdDismissedFullScreenContent() = onMain {
                finishDismissed(ad, session, onResult)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) = onMain {
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
        val ad = takeAd(AdMobFormat.INTERSTITIAL) as? InterstitialAd
        if (ad == null) {
            failBeforeShow(session, NO_AD_AVAILABLE, onResult)
            return
        }
        val responseInfo = ad.getResponseInfo()
        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdImpression() = onMain {
                session.impression(responseInfo.loadedAdSourceResponseInfo?.name, responseInfo.responseId)
            }
            override fun onAdClicked() = onMain { session.emit(AdMobEventName.CLICK) }
            override fun onAdPaid(value: AdValue) = onMain { session.paid(value, responseInfo) }
            override fun onAdDismissedFullScreenContent() = onMain {
                finishDismissed(ad, session, onResult)
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) = onMain {
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
        val ad = takeAd(AdMobFormat.REWARDED) as? RewardedAd
        if (ad == null) {
            failBeforeShow(session, NO_AD_AVAILABLE, showResultCallback)
            return
        }
        var rewardEarned = false
        val responseInfo = ad.getResponseInfo()
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdImpression() = onMain {
                session.impression(responseInfo.loadedAdSourceResponseInfo?.name, responseInfo.responseId)
            }
            override fun onAdClicked() = onMain { session.emit(AdMobEventName.CLICK) }
            override fun onAdPaid(value: AdValue) = onMain { session.paid(value, responseInfo) }
            override fun onAdDismissedFullScreenContent() = onMain {
                finishDismissed(ad, session) { result ->
                    onResult(AdMobRewardResult(rewardEarned, result, session.sessionId))
                }
            }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) = onMain {
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
                onMain {
                    rewardEarned = true
                    session.emit(
                        AdMobEventName.REWARD_EARNED,
                        reason = "${rewardItem.type}:${rewardItem.amount}",
                    )
                }
            }
        }
    }

    private fun canShow(
        activity: Activity,
        session: AdShowSession,
        onResult: (AdMobShowResult) -> Unit,
    ): Boolean {
        val reason = FullScreenShowGate.tryAcquire(
            activity = activity,
            providerFailureReason = state.takeUnless { it == AdMobState.READY }?.showFailureReason(),
            attempt = session.attempt,
        )
        if (reason != null) {
            failBeforeShow(session, reason, onResult)
            return false
        }
        return true
    }

    private fun failBeforeShow(
        session: AdShowSession,
        reason: String,
        onResult: (AdMobShowResult) -> Unit,
    ) {
        if (!session.attempt.complete()) return
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
        session.attempt.onAborted = { retainUnshownAd(session.format, ad) }
        val reason = FullScreenShowGate.commit(
            activity,
            state.takeUnless { it == AdMobState.READY }?.showFailureReason(),
            session.attempt,
            finalCheck = {
                val taken = takenAds[ad]
                if (taken?.wasRetained == true && !taken.isUsable(SystemClock.elapsedRealtime())) {
                    NO_AD_AVAILABLE
                } else null
            },
        )
        if (reason != null) {
            session.attempt.invalidate(reason)
            failBeforeShow(session, reason, onResult)
            return
        }
        takenAds.remove(ad)
        runCatching(show).onFailure { error ->
            if (!session.attempt.complete()) return@onFailure
            ad.destroy()
            val failure = error.message ?: "show_exception"
            session.showFailure(failure, cause = error)
            runCatching { onResult(AdShowResult.Failed(failure)) }
        }
    }

    private fun finishDismissed(
        ad: com.google.android.libraries.ads.mobile.sdk.common.Ad,
        session: AdShowSession,
        onResult: (AdMobShowResult) -> Unit,
    ) {
        if (!session.attempt.complete()) return
        val result = session.dismissedResult()
        session.emit(AdMobEventName.DISMISS)
        ad.destroy()
        runCatching { onResult(result) }
    }

    private fun finishFailed(
        ad: com.google.android.libraries.ads.mobile.sdk.common.Ad,
        session: AdShowSession,
        error: FullScreenContentError,
        onResult: (AdMobShowResult) -> Unit,
    ) {
        if (!session.attempt.complete()) return
        val reason = error.message.ifBlank { "show_failed" }
        session.showFailure(reason, error.code.toString())
        ad.destroy()
        runCatching { onResult(AdShowResult.Failed(reason)) }
    }

    private fun AdShowSession.paid(value: AdValue, responseInfo: ResponseInfo) {
        val adSourceInfo = responseInfo.loadedAdSourceResponseInfo
        val adapterClassName = adSourceInfo?.adapterClassName ?: responseInfo.adapterClassName
        val valueMicros = value.valueMicros.takeIf { it >= 0L } ?: return
        val currencyCode = value.currencyCode
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase(Locale.ROOT)
            ?: return
        val adNetwork = adSourceInfo?.name?.trim()?.takeIf(String::isNotEmpty)
        val impressionId = responseInfo.responseId?.trim()?.takeIf(String::isNotEmpty)
        val precisionType = value.precisionType.name.takeIf(String::isNotEmpty)
        emit(
            AdMobEventName.PAID,
            adSource = adNetwork,
            responseId = impressionId,
            value = valueMicros / MICROS_PER_UNIT,
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

    private fun Activity.isGoogleMobileAdsActivity(): Boolean =
        javaClass.name.startsWith("com.google.android.libraries.ads.mobile.sdk.")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private const val PRELOAD_APP_OPEN = "lcb_admob_app_open"
    private const val PRELOAD_INTERSTITIAL = "lcb_admob_interstitial"
    private const val PRELOAD_REWARDED = "lcb_admob_rewarded"
    private const val NO_AD_AVAILABLE = "no_preloaded_ad"
    private const val MICROS_PER_UNIT = 1_000_000.0
    private const val APP_OPEN_MAX_AGE_MILLIS = 4 * 60 * 60 * 1_000L

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
