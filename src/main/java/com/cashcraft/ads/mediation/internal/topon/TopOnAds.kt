package com.cashcraft.ads.mediation.internal.topon

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cashcraft.ads.mediation.AdEventName
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdMediationMode
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdRewardResult
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.AdsConfig
import com.cashcraft.ads.mediation.TopOnProviderConfig
import com.cashcraft.ads.mediation.TopOnRevenuePayload
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.AdShowSession
import com.thinkup.core.api.AdError
import com.thinkup.core.api.TUAdInfo
import com.thinkup.core.api.TUNetworkConfig
import com.thinkup.core.api.TUSDK
import com.thinkup.core.api.TUSDKInitListener
import com.thinkup.interstitial.api.TUInterstitial
import com.thinkup.interstitial.api.TUInterstitialListener
import com.thinkup.rewardvideo.api.TURewardVideoAd
import com.thinkup.rewardvideo.api.TURewardVideoListener
import com.thinkup.splashad.api.TUSplashAd
import com.thinkup.splashad.api.TUSplashAdEZListener
import com.thinkup.splashad.api.TUSplashAdExtraInfo
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TopOnState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
}

/** TopOn overseas provider. TopOn owns mediation; this layer owns lifecycle and analytics. */
internal object TopOnAds {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fullScreenShowing = AtomicBoolean(false)
    private val initializationListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var state: TopOnState = TopOnState.NOT_INITIALIZED
        private set

    private lateinit var application: Application
    private lateinit var commonConfig: AdsConfig
    private lateinit var config: TopOnProviderConfig
    private lateinit var events: AdEventDispatcher
    // All three SDK objects are constructed with the Application context and intentionally live
    // for the process lifetime, matching TopOn's one-instance-per-placement recommendation.
    @SuppressLint("StaticFieldLeak")
    private lateinit var appOpenAd: TUSplashAd

    @SuppressLint("StaticFieldLeak")
    private lateinit var interstitialAd: TUInterstitial

    @SuppressLint("StaticFieldLeak")
    private lateinit var rewardedAd: TURewardVideoAd

    private var appOpenLoading = false
    private var interstitialLoading = false
    private var rewardedLoading = false
    private var appOpenLoadSession: AdLoadSession? = null
    private var interstitialLoadSession: AdLoadSession? = null
    private var rewardedLoadSession: AdLoadSession? = null

    private var activeAppOpen: ActiveShow? = null
    private var activeInterstitial: ActiveShow? = null
    private var activeRewarded: ActiveRewardedShow? = null
    @SuppressLint("StaticFieldLeak")
    private var splashContainer: FrameLayout? = null

    private var currentActivity = WeakReference<Activity>(null)
    private var startedActivityCount = 0
    private var appInForeground = false
    private var foregroundStartedAtMillis = 0L
    private var autoAppOpenAttempted = false
    private var autoAppOpenCheckScheduled = false
    private var pendingAutoAppOpenSession: AdShowSession? = null

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

        override fun onActivityStarted(activity: Activity) {
            startedActivityCount++
            if (startedActivityCount == 1 && !appInForeground) onAppEnteredForeground(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0 && appInForeground) onAppEnteredBackground()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity.get() === activity) currentActivity.clear()
            if (splashContainer?.context === activity) {
                if (activeAppOpen != null) {
                    finishAppOpenFailed("activity_destroyed", "activity_destroyed")
                } else {
                    removeSplashContainer()
                }
            }
        }
    }

    // The listener overload is retained by TopOn 6.6 and is required here to avoid loading ads
    // before asynchronous SDK initialization has actually completed.
    @Suppress("DEPRECATION")
    fun initialize(
        application: Application,
        commonConfig: AdsConfig,
        mediationMode: AdMediationMode = AdMediationMode.TOPON,
        onInitialized: (Boolean) -> Unit,
        initialActivity: Activity? = null,
    ) {
        val requestedConfig = commonConfig.provider as TopOnProviderConfig
        synchronized(this) {
            when (state) {
                TopOnState.READY -> {
                    mainHandler.post { onInitialized(true) }
                    return
                }

                TopOnState.INITIALIZING -> {
                    initializationListeners += onInitialized
                    return
                }

                TopOnState.FAILED -> {
                    mainHandler.post { onInitialized(false) }
                    return
                }

                TopOnState.NOT_INITIALIZED -> Unit
            }
            state = TopOnState.INITIALIZING
            initializationListeners += onInitialized
            this.application = application
            this.commonConfig = commonConfig
            this.config = requestedConfig
            events = AdEventDispatcher(
                context = application,
                platform = AdPlatform.TOPON,
                mediationMode = mediationMode,
                listener = commonConfig.eventListener,
                loggingEnabled = commonConfig.loggingEnabled,
                logTag = commonConfig.logTag,
            )
        }

        onMain {
            application.registerActivityLifecycleCallbacks(lifecycleObserver)
            seedLifecycle(initialActivity)
            TUSDK.setNetworkLogDebug(commonConfig.loggingEnabled)
            runCatching {
                val networkConfig = TUNetworkConfig.Builder()
                    .withInitConfigList(emptyList())
                    .build()
                TUSDK.init(
                    application,
                    config.ids.applicationId,
                    config.ids.applicationKey,
                    networkConfig,
                    object : TUSDKInitListener {
                        override fun onSuccess() = onMain { finishInitialization(true) }
                        override fun onFail(message: String) = onMain { finishInitialization(false) }
                    },
                )
            }.onFailure { finishInitialization(false) }
        }
    }

    fun isReady(format: AdFormat): Boolean {
        if (state != TopOnState.READY) return false
        return when (format) {
            AdFormat.APP_OPEN -> ::appOpenAd.isInitialized && appOpenAd.isAdReady
            AdFormat.INTERSTITIAL -> ::interstitialAd.isInitialized && interstitialAd.isAdReady
            AdFormat.REWARDED -> ::rewardedAd.isInitialized && rewardedAd.isAdReady
        }
    }

    fun ensureLoaded(format: AdFormat) = onMain {
        when (format) {
            AdFormat.APP_OPEN -> loadAppOpen()
            AdFormat.INTERSTITIAL -> loadInterstitial()
            AdFormat.REWARDED -> loadRewarded()
        }
    }

    fun bidPrice(format: AdFormat): Double? {
        if (!isReady(format)) return null
        val info = when (format) {
            AdFormat.APP_OPEN -> appOpenAd.checkValidAdCaches().firstOrNull()
            AdFormat.INTERSTITIAL -> interstitialAd.checkValidAdCaches().firstOrNull()
            AdFormat.REWARDED -> rewardedAd.checkValidAdCaches().firstOrNull()
        } ?: return null
        return info.publisherRevenue
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: info.ecpm?.div(1_000.0)?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun showAppOpen(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit,
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
        onResult: (AdShowResult) -> Unit,
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
        onResult: (AdRewardResult) -> Unit,
    ) = onMain {
        if (!::config.isInitialized) {
            onResult(
                AdRewardResult(
                    rewardEarned = false,
                    showResult = AdShowResult.Failed("sdk_not_initialized"),
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
        onResult: (AdShowResult) -> Unit,
    ) = onMain {
        val session = beginBiddingSession(AdFormat.APP_OPEN, position)
        onSessionStarted(session)
        showAppOpenOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingInterstitial(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdShowResult) -> Unit,
    ) = onMain {
        val session = beginBiddingSession(AdFormat.INTERSTITIAL, position)
        onSessionStarted(session)
        showInterstitialOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingRewarded(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdRewardResult) -> Unit,
    ) = onMain {
        val session = beginBiddingSession(AdFormat.REWARDED, position)
        onSessionStarted(session)
        showRewardedOnMain(activity, position, onResult, session)
    }

    internal fun beginBiddingSession(format: AdFormat, position: String): AdShowSession =
        events.begin(format, position, config.adUnitId(format))

    private fun finishInitialization(success: Boolean) {
        if (state != TopOnState.INITIALIZING) return
        state = if (success) TopOnState.READY else TopOnState.FAILED
        if (success) {
            createAds()
            loadAll()
        }
        initializationListeners.forEach { listener -> runCatching { listener(success) } }
        initializationListeners.clear()
        if (success && appInForeground) {
            foregroundStartedAtMillis = SystemClock.elapsedRealtime()
            autoAppOpenAttempted = false
            beginAutoAppOpenOpportunity()
            scheduleAutoShowAppOpenCheck(delayMillis = 0L)
        }
    }

    private fun createAds() {
        interstitialAd = TUInterstitial(application, config.ids.interstitialPlacementId).apply {
            setAdListener(interstitialListener)
            setAdRevenueListener { info -> onMain { revenuePaid(activeInterstitial?.session, info) } }
        }
        rewardedAd = TURewardVideoAd(application, config.ids.rewardedPlacementId).apply {
            setAdListener(rewardedListener)
            setAdRevenueListener { info -> onMain { revenuePaid(activeRewarded?.session, info) } }
        }
        appOpenAd = TUSplashAd(
            application,
            config.ids.appOpenPlacementId,
            splashListener,
            SPLASH_LOAD_TIMEOUT_MILLIS,
        ).apply {
            setAdRevenueListener { info -> onMain { revenuePaid(activeAppOpen?.session, info) } }
        }
    }

    private fun loadAll() {
        loadAppOpen()
        // Stagger formats so the latency-sensitive splash request is not competing with two other
        // waterfalls at process start.
        mainHandler.postDelayed(::loadInterstitial, INTERSTITIAL_PRELOAD_DELAY_MILLIS)
        mainHandler.postDelayed(::loadRewarded, REWARDED_PRELOAD_DELAY_MILLIS)
    }

    private fun loadAppOpen() {
        if (state != TopOnState.READY || appOpenLoading || appOpenAd.isAdReady) return
        appOpenLoading = true
        appOpenLoadSession = events.beginLoad(
            AdFormat.APP_OPEN,
            config.ids.appOpenPlacementId,
            TOPON_BUFFER_SIZE,
        )
        runCatching(appOpenAd::loadAd).onFailure { error ->
            appOpenLoading = false
            appOpenLoadSession?.failed("error", "exception", error.message, null)
        }
    }

    private fun loadInterstitial() {
        if (state != TopOnState.READY || interstitialLoading || interstitialAd.isAdReady) return
        interstitialLoading = true
        interstitialLoadSession = events.beginLoad(
            AdFormat.INTERSTITIAL,
            config.ids.interstitialPlacementId,
            TOPON_BUFFER_SIZE,
        )
        runCatching(interstitialAd::load).onFailure { error ->
            interstitialLoading = false
            interstitialLoadSession?.failed("error", "exception", error.message, null)
        }
    }

    private fun loadRewarded() {
        if (state != TopOnState.READY || rewardedLoading || rewardedAd.isAdReady) return
        rewardedLoading = true
        rewardedLoadSession = events.beginLoad(
            AdFormat.REWARDED,
            config.ids.rewardedPlacementId,
            TOPON_BUFFER_SIZE,
        )
        runCatching(rewardedAd::load).onFailure { error ->
            rewardedLoading = false
            rewardedLoadSession?.failed("error", "exception", error.message, null)
        }
    }

    private val interstitialListener = object : TUInterstitialListener {
        override fun onInterstitialAdLoaded() = onMain {
            interstitialLoading = false
            interstitialLoadSession?.loadedFrom(interstitialAd.checkValidAdCaches())
        }

        override fun onInterstitialAdLoadFail(error: AdError) = onMain {
            interstitialLoading = false
            interstitialLoadSession?.failedFrom(error)
        }

        override fun onInterstitialAdShow(info: TUAdInfo) = onMain {
            activeInterstitial?.session?.impression(info.networkName, info.showId)
        }

        override fun onInterstitialAdClicked(info: TUAdInfo) = onMain {
            activeInterstitial?.session?.emit(AdEventName.CLICK)
        }

        override fun onInterstitialAdClose(info: TUAdInfo) = onMain {
            finishInterstitialDismissed()
        }

        override fun onInterstitialAdVideoError(error: AdError) = onMain {
            finishInterstitialFailed(error)
        }

        override fun onInterstitialAdVideoStart(info: TUAdInfo) = Unit
        override fun onInterstitialAdVideoEnd(info: TUAdInfo) = Unit
    }

    private val rewardedListener = object : TURewardVideoListener {
        override fun onRewardedVideoAdLoaded() = onMain {
            rewardedLoading = false
            rewardedLoadSession?.loadedFrom(rewardedAd.checkValidAdCaches())
        }

        override fun onRewardedVideoAdFailed(error: AdError) = onMain {
            rewardedLoading = false
            rewardedLoadSession?.failedFrom(error)
        }

        override fun onRewardedVideoAdPlayStart(info: TUAdInfo) = onMain {
            activeRewarded?.session?.impression(info.networkName, info.showId)
        }

        override fun onRewardedVideoAdPlayClicked(info: TUAdInfo) = onMain {
            activeRewarded?.session?.emit(AdEventName.CLICK)
        }

        override fun onReward(info: TUAdInfo) = onMain {
            activeRewarded?.let { active ->
                active.rewardEarned = true
                active.session.emit(
                    AdEventName.REWARD_EARNED,
                    reason = "${info.scenarioRewardName}:${info.scenarioRewardNumber}",
                )
            }
        }

        override fun onRewardedVideoAdClosed(info: TUAdInfo) = onMain {
            finishRewardedDismissed()
        }

        override fun onRewardedVideoAdPlayFailed(error: AdError, info: TUAdInfo?) = onMain {
            finishRewardedFailed(error)
        }

        override fun onRewardedVideoAdPlayEnd(info: TUAdInfo) = Unit
    }

    private val splashListener = object : TUSplashAdEZListener() {
        override fun onAdLoaded() = onMain {
            appOpenLoading = false
            appOpenLoadSession?.loadedFrom(appOpenAd.checkValidAdCaches())
            scheduleAutoShowAppOpenCheck(delayMillis = 0L)
        }

        override fun onNoAdError(error: AdError) = onMain {
            appOpenLoading = false
            appOpenLoadSession?.failedFrom(error)
        }

        override fun onAdShow(info: TUAdInfo) = onMain {
            activeAppOpen?.session?.impression(info.networkName, info.showId)
        }

        override fun onAdClick(info: TUAdInfo) = onMain {
            activeAppOpen?.session?.emit(AdEventName.CLICK)
        }

        override fun onAdDismiss(info: TUAdInfo, extra: TUSplashAdExtraInfo) = onMain {
            finishAppOpenDismissed()
        }
    }

    private fun showAppOpenOnMain(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit,
        session: AdShowSession = events.begin(
            AdFormat.APP_OPEN,
            position,
            config.ids.appOpenPlacementId,
        ),
    ) {
        if (!canShow(activity, session, onResult = onResult)) return
        if (!appOpenAd.isAdReady) {
            failBeforeShow(session, NO_AD_AVAILABLE, onResult)
            loadAppOpen()
            return
        }
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.addView(container)
        splashContainer = container
        activeAppOpen = ActiveShow(session, onResult)
        runCatching { appOpenAd.show(activity, container) }.onFailure { error ->
            finishAppOpenFailed(error.message ?: "show_exception", "exception")
        }
        mainHandler.postDelayed(
            {
                if (activeAppOpen?.session === session && !session.hasTerminalEvent) {
                    finishAppOpenFailed("show_callback_timeout", "timeout")
                }
            },
            SPLASH_SHOW_CALLBACK_TIMEOUT_MILLIS,
        )
    }

    private fun showInterstitialOnMain(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit,
        session: AdShowSession = events.begin(
            AdFormat.INTERSTITIAL,
            position,
            config.ids.interstitialPlacementId,
        ),
    ) {
        if (!canShow(activity, session, onResult)) return
        if (!interstitialAd.isAdReady) {
            failBeforeShow(session, NO_AD_AVAILABLE, onResult)
            loadInterstitial()
            return
        }
        activeInterstitial = ActiveShow(session, onResult)
        runCatching { interstitialAd.show(activity) }.onFailure { error ->
            finishInterstitialFailed(error.message ?: "show_exception", "exception")
        }
    }

    private fun showRewardedOnMain(
        activity: Activity,
        position: String,
        onResult: (AdRewardResult) -> Unit,
        session: AdShowSession = events.begin(
            AdFormat.REWARDED,
            position,
            config.ids.rewardedPlacementId,
        ),
    ) {
        val showResultCallback: (AdShowResult) -> Unit = { result ->
            onResult(AdRewardResult(false, result, session.sessionId))
        }
        if (!canShow(activity, session, showResultCallback)) return
        if (!rewardedAd.isAdReady) {
            failBeforeShow(session, NO_AD_AVAILABLE, showResultCallback)
            loadRewarded()
            return
        }
        activeRewarded = ActiveRewardedShow(session, onResult)
        runCatching { rewardedAd.show(activity) }.onFailure { error ->
            finishRewardedFailed(error.message ?: "show_exception", "exception")
        }
    }

    private fun canShow(
        activity: Activity,
        session: AdShowSession,
        onResult: (AdShowResult) -> Unit,
    ): Boolean {
        val reason = when {
            state != TopOnState.READY -> state.showFailureReason()
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
        onResult: (AdShowResult) -> Unit,
    ) {
        fullScreenShowing.set(false)
        session.showFailure(reason)
        runCatching { onResult(AdShowResult.Failed(reason)) }
    }

    private fun finishInterstitialDismissed() {
        val active = activeInterstitial ?: return
        activeInterstitial = null
        finishDismissed(active)
        loadInterstitial()
    }

    private fun finishInterstitialFailed(error: AdError) =
        finishInterstitialFailed(error.desc.ifBlank { "show_failed" }, error.code)

    private fun finishInterstitialFailed(reason: String, errorCode: String?) {
        val active = activeInterstitial ?: return
        activeInterstitial = null
        finishFailed(active, reason, errorCode)
        loadInterstitial()
    }

    private fun finishRewardedDismissed() {
        val active = activeRewarded ?: return
        activeRewarded = null
        val result = dismissedResult(active.session)
        active.session.emit(AdEventName.DISMISS)
        fullScreenShowing.set(false)
        runCatching {
            active.onResult(AdRewardResult(active.rewardEarned, result, active.session.sessionId))
        }
        loadRewarded()
    }

    private fun finishRewardedFailed(error: AdError) =
        finishRewardedFailed(error.desc.ifBlank { "show_failed" }, error.code)

    private fun finishRewardedFailed(reason: String, errorCode: String?) {
        val active = activeRewarded ?: return
        activeRewarded = null
        active.session.showFailure(reason, errorCode)
        fullScreenShowing.set(false)
        runCatching {
            active.onResult(
                AdRewardResult(
                    rewardEarned = false,
                    showResult = AdShowResult.Failed(reason),
                    sessionId = active.session.sessionId,
                ),
            )
        }
        loadRewarded()
    }

    private fun finishAppOpenDismissed() {
        val active = activeAppOpen ?: return
        activeAppOpen = null
        removeSplashContainer()
        finishDismissed(active)
        loadAppOpen()
    }

    private fun finishAppOpenFailed(reason: String, errorCode: String?) {
        val active = activeAppOpen ?: return
        activeAppOpen = null
        removeSplashContainer()
        finishFailed(active, reason, errorCode)
        loadAppOpen()
    }

    private fun finishDismissed(active: ActiveShow) {
        val result = dismissedResult(active.session)
        active.session.emit(AdEventName.DISMISS)
        fullScreenShowing.set(false)
        runCatching { active.onResult(result) }
    }

    private fun dismissedResult(session: AdShowSession): AdShowResult =
        if (session.hasTerminalEvent) {
            AdShowResult.Dismissed
        } else {
            session.showFailure("dismissed_before_impression")
            AdShowResult.Failed("dismissed_before_impression")
        }

    private fun finishFailed(active: ActiveShow, reason: String, errorCode: String?) {
        active.session.showFailure(reason, errorCode)
        fullScreenShowing.set(false)
        runCatching { active.onResult(AdShowResult.Failed(reason)) }
    }

    private fun removeSplashContainer() {
        splashContainer?.let { container ->
            (container.parent as? ViewGroup)?.removeView(container)
        }
        splashContainer = null
    }

    private fun revenuePaid(session: AdShowSession?, info: TUAdInfo) {
        val revenue = info.publisherRevenue
        val valueMicros = revenue?.toMicros()
        // Preserve the complete TUAdInfo object because Tenjin's TopOn endpoint reflects over it.
        runCatching {
            commonConfig.revenueListener.onRevenuePaid(
                TopOnRevenuePayload(
                    adInfo = info,
                    valueMicros = valueMicros,
                    currencyCode = info.currency,
                ),
            )
        }
        if (revenue != null && valueMicros != null) session?.paid(info, revenue, valueMicros)
    }

    private fun AdShowSession.paid(info: TUAdInfo, revenue: Double, valueMicros: Long) {
        emit(
            AdEventName.PAID,
            adSource = info.networkName,
            responseId = info.showId,
            value = revenue,
            valueMicros = valueMicros,
            currency = info.currency,
            precisionType = info.ecpmPrecision,
        )
    }

    private fun Double.toMicros(): Long = BigDecimal.valueOf(this)
        .multiply(MICROS_PER_UNIT)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()

    private fun AdLoadSession.loadedFrom(caches: List<TUAdInfo>?) {
        val info = caches?.firstOrNull()
        loaded(info?.networkName, info?.requestId)
    }

    private fun AdLoadSession.failedFrom(error: AdError) {
        failed(
            result = error.analyticsLoadResult(),
            errorCode = error.code,
            reason = error.desc,
            responseId = null,
        )
    }

    private fun AdError.analyticsLoadResult(): String = when {
        code.contains("no_fill", ignoreCase = true) || desc.contains("no fill", ignoreCase = true) -> "no_fill"
        code.contains("timeout", ignoreCase = true) || desc.contains("timeout", ignoreCase = true) -> "timeout"
        else -> "error"
    }

    private fun tryAutoShowAppOpen() {
        if (!commonConfig.autoShowAppOpen) return
        if (state != TopOnState.READY || !appInForeground || autoAppOpenAttempted) return
        if (SystemClock.elapsedRealtime() - foregroundStartedAtMillis > APP_OPEN_WINDOW_MILLIS) return
        val session = pendingAutoAppOpenSession ?: return
        val activity = currentActivity.get() ?: return
        if (!activity.hasWindowFocus() || !appOpenAd.isAdReady) return
        autoAppOpenAttempted = true
        pendingAutoAppOpenSession = null
        showAppOpenOnMain(activity, commonConfig.appOpenPosition, {}, session)
    }

    private fun onAppEnteredForeground(activity: Activity) {
        appInForeground = true
        foregroundStartedAtMillis = SystemClock.elapsedRealtime()
        autoAppOpenAttempted = false
        if (state == TopOnState.READY && !activity.isTopOnActivity()) {
            beginAutoAppOpenOpportunity()
            scheduleAutoShowAppOpenCheck()
        }
    }

    private fun onAppEnteredBackground() {
        appInForeground = false
        pendingAutoAppOpenSession?.showFailure("app_backgrounded_before_show")
        pendingAutoAppOpenSession = null
    }

    private fun schedulePausedActivityBackgroundCheck() {
        mainHandler.postDelayed(
            {
                if (appInForeground && currentActivity.get() == null && !fullScreenShowing.get()) {
                    onAppEnteredBackground()
                }
            },
            APP_BACKGROUND_CHECK_DELAY_MILLIS,
        )
    }

    private fun beginAutoAppOpenOpportunity() {
        if (!commonConfig.autoShowAppOpen || state != TopOnState.READY) return
        if (autoAppOpenAttempted || pendingAutoAppOpenSession != null) return
        pendingAutoAppOpenSession = events.begin(
            AdFormat.APP_OPEN,
            commonConfig.appOpenPosition,
            config.ids.appOpenPlacementId,
        )
    }

    private fun scheduleAutoShowAppOpenCheck(delayMillis: Long = APP_OPEN_CHECK_INTERVAL_MILLIS) {
        if (state != TopOnState.READY || autoAppOpenCheckScheduled) return
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
            state != TopOnState.READY -> state.showFailureReason()
            currentActivity.get() == null -> "activity_not_available"
            !appOpenAd.isAdReady -> NO_AD_AVAILABLE
            else -> "app_open_window_expired"
        }
        session.showFailure(reason)
    }

    private fun Activity.isTopOnActivity(): Boolean =
        javaClass.name.startsWith("com.thinkup.")

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

    private data class ActiveShow(
        val session: AdShowSession,
        val onResult: (AdShowResult) -> Unit,
    )

    private data class ActiveRewardedShow(
        val session: AdShowSession,
        val onResult: (AdRewardResult) -> Unit,
        var rewardEarned: Boolean = false,
    )

    private val MICROS_PER_UNIT = BigDecimal("1000000")
    private const val TOPON_BUFFER_SIZE = 1
    private const val NO_AD_AVAILABLE = "no_preloaded_ad"
    private const val APP_BACKGROUND_CHECK_DELAY_MILLIS = 100L
    private const val APP_OPEN_WINDOW_MILLIS = 7_000L
    private const val APP_OPEN_CHECK_INTERVAL_MILLIS = 100L
    private const val INTERSTITIAL_PRELOAD_DELAY_MILLIS = 250L
    private const val REWARDED_PRELOAD_DELAY_MILLIS = 500L
    private const val SPLASH_LOAD_TIMEOUT_MILLIS = 7_000
    private const val SPLASH_SHOW_CALLBACK_TIMEOUT_MILLIS = 2_000L
}

internal fun TopOnState.showFailureReason(): String = when (this) {
    TopOnState.NOT_INITIALIZED -> "sdk_not_initialized"
    TopOnState.INITIALIZING -> "sdk_initializing"
    TopOnState.FAILED -> "sdk_initialization_failed"
    TopOnState.READY -> "sdk_not_ready"
}
