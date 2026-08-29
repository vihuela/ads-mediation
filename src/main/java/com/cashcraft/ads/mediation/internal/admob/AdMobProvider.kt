package com.cashcraft.ads.mediation.admob

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.AdShowSession
import java.lang.ref.WeakReference
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
    private val fullScreenShowing = AtomicBoolean(false)
    private val mobileAdsInitializationStarted = AtomicBoolean(false)
    private val initializationListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var state: AdMobState = AdMobState.NOT_INITIALIZED
        private set

    private lateinit var config: AdMobConfig
    private lateinit var application: Application
    private lateinit var events: AdEventDispatcher
    private var currentActivity = WeakReference<Activity>(null)
    private var startedActivityCount = 0
    private var appInForeground = false
    private var foregroundStartedAtMillis = 0L
    private var autoAppOpenAttempted = false
    private var autoAppOpenCheckScheduled = false
    private var pendingAutoAppOpenSession: AdShowSession? = null
    private val preloadDescriptors = mutableMapOf<String, PreloadDescriptor>()
    private val preloadLoadSessions = mutableMapOf<String, AdLoadSession>()
    private val lifecycleObserver = object : Application.ActivityLifecycleCallbacks {

        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
            if (!appInForeground) onAppEnteredForeground(activity)
            scheduleAutoShowAppOpenCheck()
        }

        override fun onActivityPaused(activity: Activity) {
            if (currentActivity.get() === activity) currentActivity.clear()
            schedulePausedActivityBackgroundCheck()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
            startedActivityCount++
            if (startedActivityCount == 1 && !appInForeground) onAppEnteredForeground(activity)
        }
        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0 && appInForeground) onAppEnteredBackground()
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity.get() === activity) currentActivity.clear()
        }
    }

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
        }

        // Application.onCreate normally runs on the main thread. Register synchronously there so
        // a fast cold start cannot resume its first Activity before this callback is installed.
        onMain {
            application.registerActivityLifecycleCallbacks(lifecycleObserver)
            seedLifecycle(initialActivity)
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

    fun isReady(format: AdMobFormat): Boolean {
        if (state != AdMobState.READY) return false
        return when (format) {
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
        state = if (success) AdMobState.READY else AdMobState.FAILED
        if (success) startPreloading()
        initializationListeners.forEach { listener -> runCatching { listener(success) } }
        initializationListeners.clear()
        if (success && appInForeground) {
            foregroundStartedAtMillis = SystemClock.elapsedRealtime()
            autoAppOpenAttempted = false
            beginAutoAppOpenOpportunity()
            scheduleAutoShowAppOpenCheck(delayMillis = 0L)
        }
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
                        scheduleAutoShowAppOpenCheck(delayMillis = 0L)
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
        AppOpenAdPreloader.start(
            PRELOAD_APP_OPEN,
            preloadConfiguration(config.ids.appOpenId, config.preload.appOpen),
            preloadCallback,
        )
        InterstitialAdPreloader.start(
            PRELOAD_INTERSTITIAL,
            preloadConfiguration(config.ids.interstitialId, config.preload.interstitial),
            preloadCallback,
        )
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
        if (!isReady(format)) return null
        return AdMobNextGenBidPrice.peek(format, format.preloadId())
    }

    private fun AdMobFormat.preloadId(): String = when (this) {
        AdMobFormat.APP_OPEN -> PRELOAD_APP_OPEN
        AdMobFormat.INTERSTITIAL -> PRELOAD_INTERSTITIAL
        AdMobFormat.REWARDED -> PRELOAD_REWARDED
    }

    private fun AdMobIds.adUnitId(format: AdMobFormat): String = when (format) {
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
        val reason = when {
            state != AdMobState.READY -> state.showFailureReason()
            activity.isFinishing || activity.isDestroyed -> "activity_not_available"
            !fullScreenShowing.compareAndSet(false, true) -> "another_full_screen_ad_showing"
            else -> null
        }
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
        fullScreenShowing.set(false)
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
        val result = if (session.hasTerminalEvent) {
            AdShowResult.Dismissed
        } else {
            session.showFailure("dismissed_before_impression")
            AdShowResult.Failed("dismissed_before_impression")
        }
        session.emit(AdMobEventName.DISMISS)
        ad.destroy()
        fullScreenShowing.set(false)
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
        fullScreenShowing.set(false)
        runCatching { onResult(AdShowResult.Failed(reason)) }
    }

    private fun AdShowSession.paid(value: AdValue, responseInfo: ResponseInfo) {
        val adSourceInfo = responseInfo.loadedAdSourceResponseInfo
        val adapterClassName = adSourceInfo?.adapterClassName ?: responseInfo.adapterClassName
        emit(
            AdMobEventName.PAID,
            adSource = adSourceInfo?.name,
            responseId = responseInfo.responseId,
            value = value.valueMicros / MICROS_PER_UNIT,
            valueMicros = value.valueMicros,
            currency = value.currencyCode,
            mediationAdapterClassName = adapterClassName,
            precisionType = value.precisionType.name,
        )
        runCatching {
            config.revenueListener.onRevenuePaid(
                AdMobRevenuePayload(
                    valueMicros = value.valueMicros,
                    currencyCode = value.currencyCode,
                    adUnitId = adUnitId,
                    responseId = responseInfo.responseId,
                    mediationAdapterClassName = adapterClassName,
                    precisionType = value.precisionType.name,
                ),
            )
        }
    }

    private fun com.google.android.libraries.ads.mobile.sdk.common.Ad.adSource(): String? =
        getResponseInfo().loadedAdSourceResponseInfo?.name

    private fun tryAutoShowAppOpen() {
        if (!::config.isInitialized || !config.autoShowAppOpen) return
        if (state != AdMobState.READY || !appInForeground || autoAppOpenAttempted) return
        if (SystemClock.elapsedRealtime() - foregroundStartedAtMillis > APP_OPEN_WINDOW_MILLIS) return
        val session = pendingAutoAppOpenSession ?: return
        val activity = currentActivity.get() ?: return
        if (!activity.hasWindowFocus()) return
        if (!AppOpenAdPreloader.isAdAvailable(PRELOAD_APP_OPEN)) return
        autoAppOpenAttempted = true
        pendingAutoAppOpenSession = null
        showAppOpenOnMain(
            activity = activity,
            position = config.appOpenPosition,
            onResult = {},
            session = session,
        )
    }

    private fun onAppEnteredForeground(activity: Activity) {
        appInForeground = true
        foregroundStartedAtMillis = SystemClock.elapsedRealtime()
        autoAppOpenAttempted = false
        if (state == AdMobState.READY && !activity.isGoogleMobileAdsActivity()) {
            beginAutoAppOpenOpportunity()
            scheduleAutoShowAppOpenCheck()
        }
    }

    private fun onAppEnteredBackground() {
        appInForeground = false
        pendingAutoAppOpenSession?.showFailure("app_backgrounded_before_show")
        pendingAutoAppOpenSession = null
    }

    /**
     * Android may omit `onStop` when Home is opened and the app is restored very quickly. In that
     * case the normal started-activity counter never reaches zero. A short paused-state check keeps
     * that foreground opportunity observable while excluding pauses caused by our own full-screen
     * ads and normal same-process activity hand-offs that resume promptly.
     */
    private fun schedulePausedActivityBackgroundCheck() {
        mainHandler.postDelayed(
            {
                if (
                    appInForeground &&
                    currentActivity.get() == null &&
                    !fullScreenShowing.get()
                ) {
                    onAppEnteredBackground()
                }
            },
            APP_BACKGROUND_CHECK_DELAY_MILLIS,
        )
    }

    private fun beginAutoAppOpenOpportunity() {
        if (!::config.isInitialized || !config.autoShowAppOpen) return
        if (state != AdMobState.READY) return
        if (autoAppOpenAttempted || pendingAutoAppOpenSession != null) return
        pendingAutoAppOpenSession = events.begin(
            AdMobFormat.APP_OPEN,
            config.appOpenPosition,
            config.ids.appOpenId,
        )
    }

    /**
     * `onActivityResumed` runs before the window is guaranteed to be foreground according to GMA.
     * Poll briefly inside the app-open eligibility window so show is attempted only after focus and
     * preload availability are both true.
     */
    private fun scheduleAutoShowAppOpenCheck(delayMillis: Long = APP_OPEN_CHECK_INTERVAL_MILLIS) {
        if (state != AdMobState.READY) return
        if (autoAppOpenCheckScheduled) return
        autoAppOpenCheckScheduled = true
        mainHandler.postDelayed(
            {
                autoAppOpenCheckScheduled = false
                if (!appInForeground || autoAppOpenAttempted) return@postDelayed
                val elapsed = SystemClock.elapsedRealtime() - foregroundStartedAtMillis
                if (elapsed > APP_OPEN_WINDOW_MILLIS) {
                    finishPendingAutoAppOpenAtTimeout()
                    return@postDelayed
                }
                tryAutoShowAppOpen()
                if (!autoAppOpenAttempted) scheduleAutoShowAppOpenCheck()
            },
            delayMillis,
        )
    }

    private fun finishPendingAutoAppOpenAtTimeout() {
        val session = pendingAutoAppOpenSession ?: return
        pendingAutoAppOpenSession = null
        autoAppOpenAttempted = true
        val reason = when {
            state != AdMobState.READY -> state.showFailureReason()
            currentActivity.get() == null -> "activity_not_available"
            !AppOpenAdPreloader.isAdAvailable(PRELOAD_APP_OPEN) -> NO_AD_AVAILABLE
            else -> "app_open_window_expired"
        }
        session.showFailure(reason)
    }

    private fun Activity.isGoogleMobileAdsActivity(): Boolean =
        javaClass.name.startsWith("com.google.android.libraries.ads.mobile.sdk.")

    /** Seeds lifecycle state when UMP completed after the first Activity was already resumed. */
    private fun seedLifecycle(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        currentActivity = WeakReference(activity)
        startedActivityCount = startedActivityCount.coerceAtLeast(1)
        appInForeground = true
        foregroundStartedAtMillis = SystemClock.elapsedRealtime()
        autoAppOpenAttempted = false
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private const val PRELOAD_APP_OPEN = "lcb_admob_app_open"
    private const val PRELOAD_INTERSTITIAL = "lcb_admob_interstitial"
    private const val PRELOAD_REWARDED = "lcb_admob_rewarded"
    private const val NO_AD_AVAILABLE = "no_preloaded_ad"
    private const val APP_BACKGROUND_CHECK_DELAY_MILLIS = 100L
    private const val APP_OPEN_WINDOW_MILLIS = 7_000L
    private const val APP_OPEN_CHECK_INTERVAL_MILLIS = 100L
    private const val MICROS_PER_UNIT = 1_000_000.0

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
