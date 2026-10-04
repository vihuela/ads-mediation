package com.cashcraft.ads.mediation.internal.topon

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
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
import com.cashcraft.ads.mediation.isFormatEnabled
import com.cashcraft.ads.mediation.revenueEventId
import com.cashcraft.ads.mediation.internal.FullScreenLoadSignals
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLoadSession
import com.cashcraft.ads.mediation.internal.AdShowSession
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.dismissedResult
import com.thinkup.core.api.AdError
import com.thinkup.core.api.TUAdConst
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal enum class TopOnState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
}

/** TopOn overseas provider. TopOn owns mediation; this layer owns lifecycle and analytics. */
internal object TopOnAds {
    private val mainHandler = Handler(Looper.getMainLooper())
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

    private val loadFailures = mutableMapOf<AdFormat, Long>()
    internal fun loadFailureVersion(format: AdFormat): Long = loadFailures[format] ?: 0L
    private fun recordLoadFailure(format: AdFormat) {
        if (!::config.isInitialized || !config.isFormatEnabled(format)) return
        loadFailures[format] = loadFailureVersion(format) + 1
        FullScreenLoadSignals.changed()
    }

    private var appOpenLoading = false
    private var interstitialLoading = false
    private var rewardedLoading = false
    private var appOpenLoadSession: AdLoadSession? = null
    private var interstitialLoadSession: AdLoadSession? = null
    private var rewardedLoadSession: AdLoadSession? = null

    @Volatile
    private var activeAppOpen: ActiveShow? = null
    @Volatile
    private var activeInterstitial: ActiveShow? = null
    @Volatile
    private var activeRewarded: ActiveRewardedShow? = null
    private val revenueSessionsByImpressionId = LinkedHashMap<String, AdShowSession>()
    private val showSessionsByImpressionId = LinkedHashMap<String, String>()
    @SuppressLint("StaticFieldLeak")
    private var splashContainer: FrameLayout? = null
    private lateinit var autoAppOpenController: AutoAppOpenController<AdShowSession>

    private val lifecycleListener = object : AdLifecycleMonitor.Listener {
        override fun onActivityDestroyed(activity: Activity) {
            if (splashContainer?.context === activity) {
                if (activeAppOpen != null) {
                    finishAppOpenFailed("activity_destroyed", "activity_destroyed")
                } else {
                    removeSplashContainer()
                }
            }
        }
    }

    // TUSDK 6.6.52 exposes only a message on failure; no documented failed-init retry contract.
    // Keep failures terminal until a vendor-supported retry API and error classification exist.
    internal var initializationFailureReason: String? = null
        private set

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
            autoAppOpenController = AutoAppOpenController(
                isEnabled = {
                    this.commonConfig.autoShowAppOpen && this.config.isFormatEnabled(AdFormat.APP_OPEN)
                },
                isProviderReady = { state == TopOnState.READY },
                providerFailureReason = {
                    state.takeUnless { it == TopOnState.READY }?.showFailureReason()
                },
                isAdAvailable = { ::appOpenAd.isInitialized && appOpenAd.isAdReady },
                beginOpportunity = {
                    events.begin(
                        AdFormat.APP_OPEN,
                        this.commonConfig.appOpenPosition,
                        this.config.ids.appOpenPlacementId,
                    )
                },
                show = { activity, session ->
                    showAppOpenOnMain(
                        activity,
                        this.commonConfig.appOpenPosition,
                        {},
                        session,
                    )
                },
                fail = { session, reason -> session.showFailure(reason) },
                noAdFailureReason = NO_AD_AVAILABLE,
            )
            AdLifecycleMonitor.addListener(lifecycleListener)
        }

        onMain {
            AdLifecycleMonitor.install(application, initialActivity)
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
                        override fun onFail(message: String) = onMain {
                            if (state == TopOnState.INITIALIZING) {
                                initializationFailureReason = message
                                finishInitialization(false)
                            }
                        }
                    },
                )
            }.onFailure {
                initializationFailureReason = it.message ?: it.javaClass.simpleName
                finishInitialization(false)
            }
        }
    }

    fun isReady(format: AdFormat): Boolean {
        if (state != TopOnState.READY || !::config.isInitialized || !config.isFormatEnabled(format)) {
            return false
        }
        return when (format) {
            AdFormat.BANNER -> false
            AdFormat.APP_OPEN -> ::appOpenAd.isInitialized && appOpenAd.isAdReady
            AdFormat.INTERSTITIAL -> ::interstitialAd.isInitialized && interstitialAd.isAdReady
            AdFormat.REWARDED -> ::rewardedAd.isInitialized && rewardedAd.isAdReady
            AdFormat.NATIVE -> false
        }
    }

    fun ensureLoaded(format: AdFormat) {
        require(format != AdFormat.BANNER) { "Banner does not use full-screen preloading" }
        if (!::config.isInitialized || !config.isFormatEnabled(format)) return
        onMain {
            when (format) {
                AdFormat.BANNER -> Unit
                AdFormat.APP_OPEN -> loadAppOpen()
                AdFormat.INTERSTITIAL -> loadInterstitial()
                AdFormat.REWARDED -> loadRewarded()
                AdFormat.NATIVE -> Unit
            }
        }
    }

    fun bidPrice(format: AdFormat): Double? {
        if (!isReady(format)) return null
        // Price the same highest-priority cache entry that TopOn is expected to consume on show().
        // checkValidAdCaches().firstOrNull() is only the first item in the cache snapshot and is
        // not documented as the next ad selected by TopOn when multiple ads are cached.
        val info = when (format) {
            AdFormat.BANNER -> return null
            AdFormat.APP_OPEN -> appOpenAd.checkAdStatus().getTUTopAdInfo()
            AdFormat.INTERSTITIAL -> interstitialAd.checkAdStatus().getTUTopAdInfo()
            AdFormat.REWARDED -> rewardedAd.checkAdStatus().getTUTopAdInfo()
            AdFormat.NATIVE -> null
        } ?: return null
        return info.getPublisherRevenue(TUAdConst.CURRENCY.USD)
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: info.getEcpm(TUAdConst.CURRENCY.USD)
                .div(1_000.0)
                .takeIf { it.isFinite() && it >= 0.0 }
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
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ) = onMain {
        if (!::config.isInitialized) {
            attempt.complete()
            onResult(AdShowResult.Failed("sdk_not_initialized"))
            return@onMain
        }
        val session = beginBiddingSession(AdFormat.APP_OPEN, position, attempt, onSessionCreated)
        onSessionStarted(session)
        showAppOpenOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingInterstitial(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdShowResult) -> Unit,
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ) = onMain {
        if (!::config.isInitialized) {
            attempt.complete()
            onResult(AdShowResult.Failed("sdk_not_initialized"))
            return@onMain
        }
        val session = beginBiddingSession(AdFormat.INTERSTITIAL, position, attempt, onSessionCreated)
        onSessionStarted(session)
        showInterstitialOnMain(activity, position, onResult, session)
    }

    internal fun showBiddingRewarded(
        activity: Activity,
        position: String,
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdRewardResult) -> Unit,
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ) = onMain {
        if (!::config.isInitialized) {
            attempt.complete()
            onResult(AdRewardResult(false, AdShowResult.Failed("sdk_not_initialized")))
            return@onMain
        }
        val session = beginBiddingSession(AdFormat.REWARDED, position, attempt, onSessionCreated)
        onSessionStarted(session)
        showRewardedOnMain(activity, position, onResult, session)
    }

    internal fun beginBiddingSession(
        format: AdFormat,
        position: String,
        attempt: FullScreenShowAttempt = FullScreenShowAttempt(),
        onSessionCreated: (AdShowSession) -> Unit = {},
    ): AdShowSession = events.begin(
        format = format,
        position = position,
        adUnitId = config.adUnitId(format),
        attempt = attempt,
        onCreated = onSessionCreated,
    )

    private fun finishInitialization(success: Boolean) {
        if (state != TopOnState.INITIALIZING) return
        state = if (success) TopOnState.READY else TopOnState.FAILED
        if (success) {
            createAds()
            loadAll()
        }
        val listeners = initializationListeners.toList()
        initializationListeners.clear()
        listeners.forEach { listener -> runCatching { listener(success) } }
        if (success && config.isFormatEnabled(AdFormat.APP_OPEN)) {
            autoAppOpenController.onProviderInitialized()
        }
    }

    private fun createAds() {
        if (config.isFormatEnabled(AdFormat.INTERSTITIAL)) {
            interstitialAd = TUInterstitial(application, config.ids.interstitialPlacementId).apply {
                setAdListener(interstitialListener)
                setAdRevenueListener { info -> onMain { revenuePaid(activeInterstitial?.session, info) } }
            }
        }
        if (config.isFormatEnabled(AdFormat.REWARDED)) {
            rewardedAd = TURewardVideoAd(application, config.ids.rewardedPlacementId).apply {
                setAdListener(rewardedListener)
                setAdRevenueListener { info -> onMain { revenuePaid(activeRewarded?.session, info) } }
            }
        }
        if (config.isFormatEnabled(AdFormat.APP_OPEN)) {
            appOpenAd = TUSplashAd(
                application,
                config.ids.appOpenPlacementId,
                splashListener,
                SPLASH_LOAD_TIMEOUT_MILLIS,
            ).apply {
                setAdRevenueListener { info -> onMain { revenuePaid(activeAppOpen?.session, info) } }
            }
        }
    }

    private fun loadAll() {
        loadAppOpen()
        // Stagger formats so the latency-sensitive splash request is not competing with two other
        // waterfalls at process start.
        if (config.isFormatEnabled(AdFormat.INTERSTITIAL)) {
            mainHandler.postDelayed({ loadInterstitial() }, INTERSTITIAL_PRELOAD_DELAY_MILLIS)
        }
        if (config.isFormatEnabled(AdFormat.REWARDED)) {
            mainHandler.postDelayed({ loadRewarded() }, REWARDED_PRELOAD_DELAY_MILLIS)
        }
    }

    private fun loadAppOpen(afterShow: Boolean = false) {
        if (!::config.isInitialized || !config.isFormatEnabled(AdFormat.APP_OPEN) ||
            !::appOpenAd.isInitialized
        ) return
        if (state != TopOnState.READY || appOpenLoading || (!afterShow && appOpenAd.isAdReady)) return
        appOpenLoading = true
        appOpenLoadSession = events.beginLoad(
            AdFormat.APP_OPEN,
            config.ids.appOpenPlacementId,
            TOPON_BUFFER_SIZE,
        )
        runCatching(appOpenAd::loadAd).onFailure { error ->
            appOpenLoading = false
            recordLoadFailure(AdFormat.APP_OPEN)
            appOpenLoadSession?.failed("error", "exception", error.message, null)
        }
    }

    private fun loadInterstitial(afterShow: Boolean = false) {
        if (!::config.isInitialized || !config.isFormatEnabled(AdFormat.INTERSTITIAL) ||
            !::interstitialAd.isInitialized
        ) return
        if (state != TopOnState.READY || interstitialLoading || (!afterShow && interstitialAd.isAdReady)) return
        interstitialLoading = true
        interstitialLoadSession = events.beginLoad(
            AdFormat.INTERSTITIAL,
            config.ids.interstitialPlacementId,
            TOPON_BUFFER_SIZE,
        )
        runCatching(interstitialAd::load).onFailure { error ->
            interstitialLoading = false
            recordLoadFailure(AdFormat.INTERSTITIAL)
            interstitialLoadSession?.failed("error", "exception", error.message, null)
        }
    }

    private fun loadRewarded(afterShow: Boolean = false) {
        if (!::config.isInitialized || !config.isFormatEnabled(AdFormat.REWARDED) ||
            !::rewardedAd.isInitialized
        ) return
        if (state != TopOnState.READY || rewardedLoading || (!afterShow && rewardedAd.isAdReady)) return
        rewardedLoading = true
        rewardedLoadSession = events.beginLoad(
            AdFormat.REWARDED,
            config.ids.rewardedPlacementId,
            TOPON_BUFFER_SIZE,
        )
        runCatching(rewardedAd::load).onFailure { error ->
            rewardedLoading = false
            recordLoadFailure(AdFormat.REWARDED)
            rewardedLoadSession?.failed("error", "exception", error.message, null)
        }
    }

    private val interstitialListener = object : TUInterstitialListener {
        override fun onInterstitialAdLoaded() = onMain {
            interstitialLoading = false
            interstitialLoadSession?.loadedFrom(interstitialAd.checkValidAdCaches())
            FullScreenLoadSignals.changed()
        }

        override fun onInterstitialAdLoadFail(error: AdError) = onMain {
            interstitialLoading = false
            recordLoadFailure(AdFormat.INTERSTITIAL)
            interstitialLoadSession?.failedFrom(error)
        }

        override fun onInterstitialAdShow(info: TUAdInfo) {
            val captured = activeInterstitial
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeInterstitial?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.session?.let { session ->
                    rememberShowSession(info.showId, session)
                    rememberRevenueSession(info.showId, session)
                    session.impression(info.networkName, info.showId)
                    if (!captured.refillStarted) {
                        captured.refillStarted = true
                        loadInterstitial(afterShow = true)
                    }
                }
            }
        }

        override fun onInterstitialAdClicked(info: TUAdInfo) {
            val captured = activeInterstitial
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeInterstitial?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.session?.emit(AdEventName.CLICK)
            }
        }

        override fun onInterstitialAdClose(info: TUAdInfo) {
            val captured = activeInterstitial
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeInterstitial?.session,
                        info.showId,
                    )
                ) return@onMain
                finishInterstitialDismissed()
            }
        }

        override fun onInterstitialAdVideoError(error: AdError) {
            val captured = activeInterstitial
            onMain {
                if (captured == null || activeInterstitial?.session !== captured.session) {
                    return@onMain
                }
                finishInterstitialFailed(error)
            }
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
            recordLoadFailure(AdFormat.REWARDED)
            rewardedLoadSession?.failedFrom(error)
        }

        override fun onRewardedVideoAdPlayStart(info: TUAdInfo) {
            val captured = activeRewarded
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeRewarded?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.session?.let { session ->
                    rememberShowSession(info.showId, session)
                    rememberRevenueSession(info.showId, session)
                    session.impression(info.networkName, info.showId)
                    if (!captured.refillStarted) {
                        captured.refillStarted = true
                        loadRewarded(afterShow = true)
                    }
                }
            }
        }

        override fun onRewardedVideoAdPlayClicked(info: TUAdInfo) {
            val captured = activeRewarded
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeRewarded?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.session?.emit(AdEventName.CLICK)
            }
        }

        override fun onReward(info: TUAdInfo) {
            val captured = activeRewarded
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeRewarded?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.let { active ->
                    active.rewardEarned = true
                    active.session.emit(
                        AdEventName.REWARD_EARNED,
                        reason = "${info.scenarioRewardName}:${info.scenarioRewardNumber}",
                    )
                }
            }
        }

        override fun onRewardedVideoAdClosed(info: TUAdInfo) {
            val captured = activeRewarded
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeRewarded?.session,
                        info.showId,
                    )
                ) return@onMain
                finishRewardedDismissed()
            }
        }

        override fun onRewardedVideoAdPlayFailed(error: AdError, info: TUAdInfo?) {
            val captured = activeRewarded
            onMain {
                if (!canAcceptShowCallback(captured?.session, activeRewarded?.session, info?.showId)) {
                    return@onMain
                }
                finishRewardedFailed(error)
            }
        }

        override fun onRewardedVideoAdPlayEnd(info: TUAdInfo) = Unit
    }

    private val splashListener = object : TUSplashAdEZListener() {
        override fun onAdLoaded() = onMain {
            appOpenLoading = false
            appOpenLoadSession?.loadedFrom(appOpenAd.checkValidAdCaches())
            FullScreenLoadSignals.changed()
            autoAppOpenController.onAdAvailable()
        }

        override fun onNoAdError(error: AdError) = onMain {
            appOpenLoading = false
            recordLoadFailure(AdFormat.APP_OPEN)
            appOpenLoadSession?.failedFrom(error)
        }

        override fun onAdShow(info: TUAdInfo) {
            val captured = activeAppOpen
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeAppOpen?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.session?.let { session ->
                    rememberShowSession(info.showId, session)
                    rememberRevenueSession(info.showId, session)
                    session.impression(info.networkName, info.showId)
                    if (!captured.refillStarted) {
                        captured.refillStarted = true
                        loadAppOpen(afterShow = true)
                    }
                }
            }
        }

        override fun onAdClick(info: TUAdInfo) {
            val captured = activeAppOpen
            onMain {
                if (!canAcceptShowCallback(
                        captured?.session,
                        activeAppOpen?.session,
                        info.showId,
                    )
                ) return@onMain
                captured?.session?.emit(AdEventName.CLICK)
            }
        }

        override fun onAdDismiss(info: TUAdInfo, extra: TUSplashAdExtraInfo) {
            val captured = activeAppOpen
            onMain {
                if (!canAcceptShowCallback(captured?.session, activeAppOpen?.session, info.showId)) {
                    return@onMain
                }
                finishAppOpenDismissed()
            }
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
            if (!session.attempt.isWaitingOpportunity) loadAppOpen()
            return
        }
        val hostResult = runCatching {
            activity.findViewById<ViewGroup?>(android.R.id.content)
                ?: (activity.window?.decorView as? ViewGroup)
        }
        val host = hostResult.getOrElse { error ->
            failBeforeShow(
                session = session,
                reason = APP_OPEN_CONTAINER_RESOLUTION_FAILED,
                onResult = onResult,
                errorCode = error.javaClass.simpleName,
                cause = error,
            )
            return
        }
        if (host == null) {
            failBeforeShow(
                session = session,
                reason = APP_OPEN_CONTAINER_UNAVAILABLE,
                onResult = onResult,
                errorCode = "container_not_found",
            )
            return
        }
        if (!host.isAttachedToWindow || host.rootView !== activity.window.decorView) {
            failBeforeShow(
                session = session,
                reason = APP_OPEN_CONTAINER_UNAVAILABLE,
                onResult = onResult,
                errorCode = "container_not_attached",
            )
            return
        }
        if (!host.isShown) {
            failBeforeShow(
                session = session,
                reason = APP_OPEN_CONTAINER_UNAVAILABLE,
                onResult = onResult,
                errorCode = "container_not_visible",
            )
            return
        }
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // Register cleanup before addView: host attachment listeners may cancel/reenter.
        splashContainer = container
        activeAppOpen = ActiveShow(session, onResult)
        session.attempt.onAborted = { clearPreparedAppOpen(session, container) }
        val attachError = runCatching { host.addView(container) }.exceptionOrNull()
        if (attachError != null) {
            clearPreparedAppOpen(session, container)
            failBeforeShow(
                session = session,
                reason = APP_OPEN_CONTAINER_ATTACH_FAILED,
                onResult = onResult,
                errorCode = attachError.javaClass.simpleName,
                cause = attachError,
            )
            return
        }
        if (!commitForShow(
                activity = activity,
                session = session,
                onResult = onResult,
                finalCheck = {
                    when {
                        !appOpenAd.isAdReady -> NO_AD_AVAILABLE
                        !host.isAttachedToWindow || !host.isShown || !container.isAttachedToWindow ||
                            host.rootView !== activity.window.decorView ->
                            APP_OPEN_CONTAINER_UNAVAILABLE
                        else -> null
                    }
                },
            )
        ) {
            clearPreparedAppOpen(session, container)
            return
        }
        runCatching { appOpenAd.show(activity, container) }.onFailure { error ->
            if (activeAppOpen?.session === session) {
                finishAppOpenFailed("show_exception", error.javaClass.simpleName, error)
            }
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
            if (!session.attempt.isWaitingOpportunity) loadInterstitial()
            return
        }
        if (!commitForShow(
                activity = activity,
                session = session,
                onResult = onResult,
                finalCheck = { if (!interstitialAd.isAdReady) NO_AD_AVAILABLE else null },
            )
        ) {
            if (!session.attempt.isWaitingOpportunity && !interstitialAd.isAdReady) loadInterstitial()
            return
        }
        activeInterstitial = ActiveShow(session, onResult)
        runCatching { interstitialAd.show(activity) }.onFailure { error ->
            if (activeInterstitial?.session === session) {
                finishInterstitialFailed(error.message ?: "show_exception", "exception")
            }
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
            if (!session.attempt.isWaitingOpportunity) loadRewarded()
            return
        }
        if (!commitForShow(
                activity = activity,
                session = session,
                onResult = showResultCallback,
                finalCheck = { if (!rewardedAd.isAdReady) NO_AD_AVAILABLE else null },
            )
        ) {
            if (!session.attempt.isWaitingOpportunity && !rewardedAd.isAdReady) loadRewarded()
            return
        }
        activeRewarded = ActiveRewardedShow(session, onResult)
        runCatching { rewardedAd.show(activity) }.onFailure { error ->
            if (activeRewarded?.session === session) {
                finishRewardedFailed(error.message ?: "show_exception", "exception")
            }
        }
    }

    private fun canShow(
        activity: Activity,
        session: AdShowSession,
        onResult: (AdShowResult) -> Unit,
    ): Boolean {
        if (::config.isInitialized && !config.isFormatEnabled(session.format)) {
            failBeforeShow(session, "ad_format_disabled", onResult)
            return false
        }
        val reason = FullScreenShowGate.tryAcquire(
            activity = activity,
            providerFailureReason = state.takeUnless { it == TopOnState.READY }?.showFailureReason(),
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
        onResult: (AdShowResult) -> Unit,
        errorCode: String? = null,
        cause: Throwable? = null,
    ) {
        if (!session.attempt.complete()) return
        session.showFailure(reason, errorCode, cause)
        runCatching { onResult(AdShowResult.Failed(reason)) }
    }

    private fun commitForShow(
        activity: Activity,
        session: AdShowSession,
        onResult: (AdShowResult) -> Unit,
        finalCheck: () -> String? = { null },
    ): Boolean {
        val reason = FullScreenShowGate.commit(
            activity = activity,
            providerFailureReason = state.takeUnless { it == TopOnState.READY }?.showFailureReason(),
            attempt = session.attempt,
            finalCheck = finalCheck,
        )
        if (reason != null) {
            failBeforeShow(session, reason, onResult)
            return false
        }
        return true
    }

    private fun finishInterstitialDismissed() {
        val active = activeInterstitial ?: return
        if (!active.session.attempt.complete()) return
        activeInterstitial = null
        finishDismissed(active)
        loadInterstitial()
    }

    private fun finishInterstitialFailed(error: AdError) =
        finishInterstitialFailed(error.desc.ifBlank { "show_failed" }, error.code)

    private fun finishInterstitialFailed(reason: String, errorCode: String?) {
        val active = activeInterstitial ?: return
        if (!active.session.attempt.complete()) return
        activeInterstitial = null
        finishFailed(active, reason, errorCode)
        loadInterstitial()
    }

    private fun finishRewardedDismissed() {
        val active = activeRewarded ?: return
        if (!active.session.attempt.complete()) return
        activeRewarded = null
        val result = active.session.dismissedResult()
        active.session.emit(AdEventName.DISMISS)
        runCatching {
            active.onResult(AdRewardResult(active.rewardEarned, result, active.session.sessionId))
        }
        loadRewarded()
    }

    private fun finishRewardedFailed(error: AdError) =
        finishRewardedFailed(error.desc.ifBlank { "show_failed" }, error.code)

    private fun finishRewardedFailed(reason: String, errorCode: String?) {
        val active = activeRewarded ?: return
        if (!active.session.attempt.complete()) return
        activeRewarded = null
        active.session.showFailure(reason, errorCode)
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
        if (!active.session.attempt.complete()) return
        activeAppOpen = null
        removeSplashContainer()
        finishDismissed(active)
        loadAppOpen()
    }

    private fun finishAppOpenFailed(reason: String, errorCode: String?, cause: Throwable? = null) {
        val active = activeAppOpen ?: return
        if (!active.session.attempt.complete()) return
        activeAppOpen = null
        removeSplashContainer()
        finishFailed(active, reason, errorCode, cause)
        loadAppOpen()
    }

    private fun finishDismissed(active: ActiveShow) {
        val result = active.session.dismissedResult()
        active.session.emit(AdEventName.DISMISS)
        runCatching { active.onResult(result) }
    }

    private fun finishFailed(
        active: ActiveShow,
        reason: String,
        errorCode: String?,
        cause: Throwable? = null,
    ) {
        active.session.showFailure(reason, errorCode, cause)
        runCatching { active.onResult(AdShowResult.Failed(reason)) }
    }

    private fun removeSplashContainer() {
        removeSplashContainer(splashContainer)
    }

    private fun removeSplashContainer(container: FrameLayout?) {
        container ?: return
        (container.parent as? ViewGroup)?.removeView(container)
        if (splashContainer === container) splashContainer = null
    }

    private fun clearPreparedAppOpen(session: AdShowSession, container: FrameLayout) {
        if (activeAppOpen?.session === session) activeAppOpen = null
        removeSplashContainer(container)
    }

    private fun canAcceptShowCallback(
        capturedSession: AdShowSession?,
        currentSession: AdShowSession?,
        showId: String?,
    ): Boolean = acceptsTopOnCallback(
        capturedSessionId = capturedSession?.sessionId,
        currentSessionId = currentSession?.sessionId,
        recordedSessionId = showId?.trim()?.let(showSessionsByImpressionId::get),
        committed = capturedSession?.attempt?.isCommitted == true,
    )

    private fun rememberShowSession(impressionId: String?, session: AdShowSession) {
        val normalizedId = impressionId?.trim()?.takeIf(String::isNotEmpty) ?: return
        // ponytail: remember the last 32 IDs without retaining page callbacks. Reject known stale
        // IDs; callbacks with no SDK identity still rely on the SDK's per-show ordering contract.
        showSessionsByImpressionId[normalizedId] = session.sessionId
        while (showSessionsByImpressionId.size > MAX_PENDING_REVENUE_SESSIONS) {
            val oldestId = showSessionsByImpressionId.entries.firstOrNull()?.key ?: break
            showSessionsByImpressionId.remove(oldestId)
        }
    }

    private fun revenuePaid(session: AdShowSession?, info: TUAdInfo) {
        val impressionId = info.showId?.trim()?.takeIf(String::isNotEmpty)
        val revenue = info.getPublisherRevenue(TUAdConst.CURRENCY.USD)
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return
        val valueMicros = revenue.toMicrosOrNull() ?: return
        val revenueSession = impressionId
            ?.let(revenueSessionsByImpressionId::remove)
            ?: session
            ?: return
        val adNetwork = info.networkName?.trim()?.takeIf(String::isNotEmpty)
        val precisionType = info.ecpmPrecision?.trim()?.takeIf(String::isNotEmpty)
        revenueSession.paid(
            info = info,
            revenue = revenue,
            valueMicros = valueMicros,
            currencyCode = USD_CURRENCY_CODE,
        )
        // Preserve the complete TUAdInfo object because Tenjin's TopOn endpoint reflects over it.
        runCatching {
            commonConfig.revenueListener.onRevenuePaid(
                TopOnRevenuePayload(
                    eventId = revenueEventId(AdPlatform.TOPON, impressionId, revenueSession.sessionId),
                    occurredAtMillis = System.currentTimeMillis(),
                    mediationMode = revenueSession.mediationMode,
                    format = revenueSession.format,
                    sessionId = revenueSession.sessionId,
                    position = revenueSession.position,
                    placementId = revenueSession.adUnitId,
                    valueMicros = valueMicros,
                    currencyCode = USD_CURRENCY_CODE,
                    adNetwork = adNetwork,
                    impressionId = impressionId,
                    precisionType = precisionType,
                    adInfo = info,
                ),
            )
        }
    }

    private fun AdShowSession.paid(
        info: TUAdInfo,
        revenue: Double,
        valueMicros: Long,
        currencyCode: String,
    ) {
        emit(
            AdEventName.PAID,
            adSource = info.networkName,
            responseId = info.showId,
            value = revenue,
            valueMicros = valueMicros,
            currency = currencyCode,
            precisionType = info.ecpmPrecision,
        )
    }

    private fun rememberRevenueSession(impressionId: String?, session: AdShowSession) {
        val normalizedId = impressionId?.trim()?.takeIf(String::isNotEmpty) ?: return
        revenueSessionsByImpressionId[normalizedId] = session
        while (revenueSessionsByImpressionId.size > MAX_PENDING_REVENUE_SESSIONS) {
            val oldestId = revenueSessionsByImpressionId.entries.firstOrNull()?.key ?: break
            revenueSessionsByImpressionId.remove(oldestId)
        }
    }

    private fun Double.toMicrosOrNull(): Long? = runCatching {
        BigDecimal.valueOf(this)
            .multiply(MICROS_PER_UNIT)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

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


    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class ActiveShow(
        val session: AdShowSession,
        val onResult: (AdShowResult) -> Unit,
        var refillStarted: Boolean = false,
    )

    private data class ActiveRewardedShow(
        val session: AdShowSession,
        val onResult: (AdRewardResult) -> Unit,
        var rewardEarned: Boolean = false,
        var refillStarted: Boolean = false,
    )

    private val MICROS_PER_UNIT = BigDecimal("1000000")
    private const val USD_CURRENCY_CODE = "USD"
    private const val MAX_PENDING_REVENUE_SESSIONS = 32
    private const val TOPON_BUFFER_SIZE = 1
    private const val NO_AD_AVAILABLE = "no_preloaded_ad"
    private const val APP_OPEN_CONTAINER_UNAVAILABLE = "app_open_container_unavailable"
    private const val APP_OPEN_CONTAINER_RESOLUTION_FAILED = "app_open_container_resolution_failed"
    private const val APP_OPEN_CONTAINER_ATTACH_FAILED = "app_open_container_attach_failed"
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

/** Unknown IDs preserve legacy callbacks; a known old ID must never finish a newer owner. */
internal fun acceptsTopOnCallback(
    capturedSessionId: String?,
    currentSessionId: String?,
    recordedSessionId: String?,
    committed: Boolean,
): Boolean = committed && capturedSessionId != null && capturedSessionId == currentSessionId &&
    (recordedSessionId == null || recordedSessionId == capturedSessionId)
