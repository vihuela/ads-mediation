package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.admob.AdMobConfig
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdBiddingCoordinator
import com.cashcraft.ads.mediation.internal.AdBidEventData
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AdShowSession
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.BidDecision
import com.cashcraft.ads.mediation.internal.DisplayOpportunityController
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class AdsState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
}

/** SDK-neutral facade for app-open, interstitial, and rewarded ads. */
object Ads {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initializationListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val providerInitializationStarted = AtomicBoolean(false)

    @Volatile
    private var activePlatform: AdPlatform? = null

    @Volatile
    private var initializationStage = InitializationStage.NOT_STARTED

    private lateinit var application: Application
    private lateinit var config: AdsConfig
    private lateinit var facadeEvents: AdEventDispatcher
    private lateinit var umpConsentManager: UmpConsentManager
    private lateinit var autoBiddingAppOpenController: AutoAppOpenController<Unit>
    private var admobInitializationResult: Boolean? = null
    private var topOnInitializationResult: Boolean? = null

    private val lifecycleListener = object : AdLifecycleMonitor.Listener {
        override fun onActivityResumed(activity: Activity) {
            gatherConsentIfNeeded(activity)
        }
    }

    val platform: AdPlatform?
        get() = activePlatform

    val state: AdsState
        get() = when (initializationStage) {
            InitializationStage.NOT_STARTED -> AdsState.NOT_INITIALIZED
            InitializationStage.WAITING_FOR_UMP,
            InitializationStage.PROVIDER_INITIALIZING,
            -> AdsState.INITIALIZING
            InitializationStage.FAILED -> AdsState.FAILED
            InitializationStage.COMPLETE -> when (config.provider) {
                is AdMobProviderConfig -> AdMobAds.state.toCommonState()
                is TopOnProviderConfig -> TopOnAds.state.toCommonState()
                is BiddingProviderConfig -> if (
                    AdMobAds.state == AdMobState.READY || TopOnAds.state == TopOnState.READY
                ) {
                    AdsState.READY
                } else {
                    AdsState.FAILED
                }
            }
        }

    fun initialize(
        application: Application,
        config: AdsConfig,
        onInitialized: (Boolean) -> Unit = {},
    ) {
        var isFirstInitialization = false
        synchronized(this) {
            val existing = activePlatform
            require(existing == null || existing == config.provider.platform) {
                "Ads is already initialized with $existing"
            }
            if (::config.isInitialized) {
                when (state) {
                    AdsState.READY -> mainHandler.post { onInitialized(true) }
                    AdsState.FAILED -> mainHandler.post { onInitialized(false) }
                    AdsState.NOT_INITIALIZED,
                    AdsState.INITIALIZING,
                    -> initializationListeners += onInitialized
                }
                return
            }
            activePlatform = config.provider.platform.takeUnless { config.provider is BiddingProviderConfig }
            this.application = application
            this.config = config
            umpConsentManager = UmpConsentManager(
                context = application,
                config = config.umpConsent,
                loggingEnabled = config.loggingEnabled,
                logTag = config.logTag,
            )
            facadeEvents = AdEventDispatcher(
                context = application,
                platform = config.provider.platform,
                mediationMode = config.provider.mediationMode,
                listener = config.eventListener,
                loggingEnabled = config.loggingEnabled,
                logTag = config.logTag,
            )
            autoBiddingAppOpenController = AutoAppOpenController(
                isEnabled = {
                    this.config.autoShowAppOpen && this.config.provider is BiddingProviderConfig
                },
                isProviderReady = { initializationStage == InitializationStage.COMPLETE },
                providerFailureReason = { null },
                isAdAvailable = { isReady(AdFormat.APP_OPEN) },
                shouldIgnoreActivity = { activity ->
                    activity.javaClass.name.startsWith("com.google.android.libraries.ads.mobile.sdk.") ||
                        activity.javaClass.name.startsWith("com.thinkup.")
                },
                beginOpportunity = { Unit },
                show = { activity, _ ->
                    showAppOpen(activity, this.config.appOpenPosition)
                },
                fail = { _, reason -> failAutoBiddingAppOpenOpportunity(reason) },
                noAdFailureReason = NO_BID_CANDIDATE,
            )
            initializationListeners += onInitialized
            initializationStage = InitializationStage.WAITING_FOR_UMP
            isFirstInitialization = true
        }

        if (!isFirstInitialization) return
        onMain {
            AdLifecycleMonitor.addListener(lifecycleListener)
            AdLifecycleMonitor.install(application)
            if (config.umpConsent.enabled) {
                AdLifecycleMonitor.currentActivity?.let(::gatherConsentIfNeeded)
            } else {
                startProviderInitialization()
            }
        }
    }

    private fun gatherConsentIfNeeded(activity: Activity) {
        if (!::umpConsentManager.isInitialized || initializationStage != InitializationStage.WAITING_FOR_UMP) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        umpConsentManager.gatherConsent(
            activity = activity,
            onAdsAllowed = ::startProviderInitialization,
            onAdsUnavailable = { finishInitialization(false) },
        )
    }

    private fun startProviderInitialization() {
        if (!umpConsentManager.snapshot.canRequestAds) return
        if (!providerInitializationStarted.compareAndSet(false, true)) return
        initializationStage = InitializationStage.PROVIDER_INITIALIZING
        val initialActivity = AdLifecycleMonitor.currentActivity
            ?.takeUnless { it.isFinishing || it.isDestroyed }
        when (val provider = config.provider) {
            is AdMobProviderConfig -> AdMobAds.initialize(
                application = application,
                config = AdMobConfig(
                    ids = provider.ids,
                    preload = provider.preload,
                    eventListener = config.eventListener,
                    revenueListener = config.revenueListener,
                    loggingEnabled = config.loggingEnabled,
                    logTag = config.logTag,
                    autoShowAppOpen = config.autoShowAppOpen,
                    appOpenPosition = config.appOpenPosition,
                    mediationMode = AdMediationMode.ADMOB,
                ),
                onInitialized = ::finishInitialization,
                initialActivity = initialActivity,
            )

            is TopOnProviderConfig -> TopOnAds.initialize(
                application = application,
                commonConfig = config,
                mediationMode = AdMediationMode.TOPON,
                onInitialized = ::finishInitialization,
                initialActivity = initialActivity,
            )

            is BiddingProviderConfig -> {
                AdMobAds.initialize(
                    application = application,
                    config = AdMobConfig(
                        ids = provider.admob.ids,
                        preload = provider.admob.preload,
                        eventListener = config.eventListener,
                        revenueListener = config.revenueListener,
                        loggingEnabled = config.loggingEnabled,
                        logTag = config.logTag,
                        autoShowAppOpen = false,
                        appOpenPosition = config.appOpenPosition,
                        mediationMode = AdMediationMode.BIDDING,
                    ),
                    onInitialized = { success -> finishBiddingProviderInitialization(true, success) },
                    initialActivity = initialActivity,
                )
                TopOnAds.initialize(
                    application = application,
                    commonConfig = config.copy(
                        provider = provider.topon,
                        autoShowAppOpen = false,
                    ),
                    mediationMode = AdMediationMode.BIDDING,
                    onInitialized = { success -> finishBiddingProviderInitialization(false, success) },
                    initialActivity = initialActivity,
                )
            }
        }
    }

    private fun finishBiddingProviderInitialization(isAdMob: Boolean, success: Boolean) {
        if (isAdMob) admobInitializationResult = success else topOnInitializationResult = success
        val admobResult = admobInitializationResult ?: return
        val topOnResult = topOnInitializationResult ?: return
        finishInitialization(admobResult || topOnResult)
    }

    private fun finishInitialization(success: Boolean) {
        initializationStage = if (success) InitializationStage.COMPLETE else InitializationStage.FAILED
        initializationListeners.forEach { listener -> runCatching { listener(success) } }
        initializationListeners.clear()
        if (success && config.provider is BiddingProviderConfig) {
            autoBiddingAppOpenController.onProviderInitialized()
        }
    }

    fun isReady(format: AdFormat): Boolean = ::config.isInitialized && consentSnapshot.canRequestAds &&
        when (config.provider) {
            is AdMobProviderConfig -> AdMobAds.isReady(format)
            is TopOnProviderConfig -> TopOnAds.isReady(format)
            is BiddingProviderConfig -> AdMobAds.isReady(format) || TopOnAds.isReady(format)
        }

    fun showAppOpen(
        activity: Activity,
        position: String = "manual",
        onResult: (AdShowResult) -> Unit = {},
    ) = showAppOpenInternal(activity, null, position, onResult)

    /** Uses a host-owned container for providers, such as TopOn, that render splash ads into a view. */
    fun showAppOpen(
        activity: Activity,
        hostContainer: ViewGroup,
        position: String = "manual",
        onResult: (AdShowResult) -> Unit = {},
    ) = showAppOpenInternal(activity, hostContainer, position, onResult)

    private fun showAppOpenInternal(
        activity: Activity,
        hostContainer: ViewGroup?,
        position: String,
        onResult: (AdShowResult) -> Unit,
    ) = onMain {
        val failure = commonShowFailure()
        if (failure != null) {
            failShow(AdFormat.APP_OPEN, position, failure, onResult)
            return@onMain
        }
        when (config.provider) {
            is AdMobProviderConfig -> AdMobAds.showAppOpen(activity, position, onResult)
            is TopOnProviderConfig -> TopOnAds.showAppOpen(
                activity = activity,
                position = position,
                onResult = onResult,
                hostContainer = hostContainer,
            )
            is BiddingProviderConfig -> bidAndShow(
                format = AdFormat.APP_OPEN,
                activity = activity,
                position = position,
                onResult = { onResult(it.showResult) },
                appOpenHostContainer = hostContainer,
            )
        }
    }

    fun showInterstitial(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit = {},
    ) = onMain {
        val failure = commonShowFailure()
        if (failure != null) {
            failShow(AdFormat.INTERSTITIAL, position, failure, onResult)
            return@onMain
        }
        when (config.provider) {
            is AdMobProviderConfig -> AdMobAds.showInterstitial(activity, position, onResult)
            is TopOnProviderConfig -> TopOnAds.showInterstitial(activity, position, onResult)
            is BiddingProviderConfig -> bidAndShow(
                format = AdFormat.INTERSTITIAL,
                activity = activity,
                position = position,
                onResult = { onResult(it.showResult) },
            )
        }
    }

    fun showRewarded(
        activity: Activity,
        position: String,
        onResult: (AdRewardResult) -> Unit,
    ) = onMain {
        val failure = commonShowFailure()
        if (failure != null) {
            failRewardedShow(position, failure, onResult)
            return@onMain
        }
        when (config.provider) {
            is AdMobProviderConfig -> AdMobAds.showRewarded(activity, position, onResult)
            is TopOnProviderConfig -> TopOnAds.showRewarded(activity, position, onResult)
            is BiddingProviderConfig -> bidAndShow(AdFormat.REWARDED, activity, position, onResult)
        }
    }

    /** Waits within this scene's fixed deadline; cancellation leaves SDK loading intact. */
    fun showAppOpenWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.APP_OPEN, activity, position, timeoutMillis, isSceneValid,
        onResult = { onResult(it.showResult) },
    )

    fun showAppOpenWhenReady(
        activity: Activity,
        hostContainer: ViewGroup,
        position: String = "manual",
        timeoutMillis: Long,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.APP_OPEN, activity, position, timeoutMillis, isSceneValid, hostContainer,
        onResult = { onResult(it.showResult) },
    )

    fun showInterstitialWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.INTERSTITIAL, activity, position, timeoutMillis, isSceneValid,
        onResult = { onResult(it.showResult) },
    )

    fun showRewardedWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdRewardResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.REWARDED, activity, position, timeoutMillis, isSceneValid, onResult = onResult,
    )

    private fun createOpportunity(
        format: AdFormat,
        activity: Activity,
        position: String,
        timeoutMillis: Long,
        isSceneValid: () -> Boolean,
        hostContainer: ViewGroup? = null,
        onResult: (AdRewardResult) -> Unit,
    ): AdDisplayOpportunity {
        // Capture before posting: a congested main queue must not extend the caller's deadline.
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var controller: DisplayOpportunityController
        val handle = AdDisplayOpportunity { onMain { controller.cancel() } }
        val boundActivity = activity
        val listener = object : AdLifecycleMonitor.Listener {
            override fun onActivityPaused(activity: Activity) {
                if (activity === boundActivity) controller.cancel("activity_not_resumed")
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === boundActivity) controller.cancel("activity_not_available")
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity !== boundActivity) controller.cancel("activity_not_resumed")
            }
            override fun onAppEnteredBackground() {
                controller.cancel("app_not_in_foreground")
            }
        }
        controller = DisplayOpportunityController(
            startedAtMillis = startedAt,
            timeoutMillis = timeoutMillis,
            nowMillis = SystemClock::elapsedRealtime,
            schedule = { check, delay -> mainHandler.postDelayed(check, delay) },
            unschedule = mainHandler::removeCallbacks,
            precondition = ::opportunityPrecondition,
            sceneValid = isSceneValid,
            hostFailure = { AdLifecycleMonitor.activityWaitFailureReason(activity) },
            isReady = { isReady(format) },
            ensureLoaded = {
                if (config.provider !is AdMobProviderConfig) TopOnAds.ensureLoaded(format)
            },
            show = { attempt, callback ->
                val decision = if (config.provider is BiddingProviderConfig) {
                    AdBiddingCoordinator.selectAvailable(format)
                } else null
                val winner = decision?.selection?.winner ?: when (config.provider) {
                    is AdMobProviderConfig -> AdPlatform.ADMOB
                    is TopOnProviderConfig -> AdPlatform.TOPON
                    is BiddingProviderConfig -> null
                }
                if (winner == null) {
                    controller.cancel(NO_BID_CANDIDATE)
                } else {
                    showSelected(
                        winner, format, activity, position, hostContainer, attempt,
                        onSessionCreated = controller::sessionStarted,
                        onSessionStarted = { session ->
                            decision?.let { session.bidResult(it.toEventData(format)) }
                        },
                        onResult = callback,
                    )
                }
            },
            onCleanup = {
                AdLifecycleMonitor.removeListener(listener)
                handle.detach()
            },
            onResult = onResult,
        )
        onMain {
            AdLifecycleMonitor.addListener(listener)
            controller.start()
        }
        return handle
    }

    private fun opportunityPrecondition(): String? = displayOpportunityFailureReason(
        commonFailure = commonShowFailure(),
        state = state,
        hasReadyBiddingProvider = providerInitializationStarted.get() &&
            ::config.isInitialized && config.provider is BiddingProviderConfig &&
            (AdMobAds.state == AdMobState.READY || TopOnAds.state == TopOnState.READY),
    )

    /** Both waiting and immediate bidding pass the same owner all the way to SDK show(). */
    private fun showSelected(
        winner: AdPlatform,
        format: AdFormat,
        activity: Activity,
        position: String,
        hostContainer: ViewGroup?,
        attempt: FullScreenShowAttempt,
        onSessionCreated: (AdShowSession) -> Unit = {},
        onSessionStarted: (AdShowSession) -> Unit,
        onResult: (AdRewardResult) -> Unit,
    ) {
        var sessionId: String? = null
        val created: (AdShowSession) -> Unit = {
            sessionId = it.sessionId
            onSessionCreated(it)
        }
        val callback: (AdShowResult) -> Unit = { onResult(AdRewardResult(false, it, sessionId)) }
        when (winner) {
            AdPlatform.ADMOB -> when (format) {
                AdFormat.APP_OPEN -> AdMobAds.showBiddingAppOpen(
                    activity, position, onSessionStarted, callback, attempt, created,
                )
                AdFormat.INTERSTITIAL -> AdMobAds.showBiddingInterstitial(
                    activity, position, onSessionStarted, callback, attempt, created,
                )
                AdFormat.REWARDED -> AdMobAds.showBiddingRewarded(
                    activity, position, onSessionStarted, onResult, attempt, created,
                )
            }
            AdPlatform.TOPON -> when (format) {
                AdFormat.APP_OPEN -> TopOnAds.showBiddingAppOpen(
                    activity, position, onSessionStarted, callback, hostContainer, attempt, created,
                )
                AdFormat.INTERSTITIAL -> TopOnAds.showBiddingInterstitial(
                    activity, position, onSessionStarted, callback, attempt, created,
                )
                AdFormat.REWARDED -> TopOnAds.showBiddingRewarded(
                    activity, position, onSessionStarted, onResult, attempt, created,
                )
            }
        }
    }

    private fun commonShowFailure(): String? = when {
        !::config.isInitialized -> "sdk_not_initialized"
        !consentSnapshot.canRequestAds -> CONSENT_NOT_OBTAINED
        !providerInitializationStarted.get() -> "sdk_initializing"
        initializationStage == InitializationStage.FAILED -> "sdk_initialization_failed"
        config.provider is BiddingProviderConfig && initializationStage != InitializationStage.COMPLETE ->
            "sdk_initializing"
        else -> null
    }

    private fun bidAndShow(
        format: AdFormat,
        activity: Activity,
        position: String,
        onResult: (AdRewardResult) -> Unit,
        appOpenHostContainer: ViewGroup? = null,
    ) {
        val attempt = FullScreenShowAttempt().apply { guard = ::commonShowFailure }
        FullScreenShowGate.reserve(attempt)?.let { reason ->
            val sessionId = emitFacadeShowFailure(format, position, reason)
            onResult(AdRewardResult(false, AdShowResult.Failed(reason), sessionId))
            return
        }
        val decision = AdBiddingCoordinator.select(format)
        val selection = decision.selection
        if (selection == null) {
            attempt.complete()
            val session = facadeEvents.begin(format, position, config.provider.adUnitId(format))
            session.bidResult(decision.toEventData(format))
            session.showFailure(NO_BID_CANDIDATE)
            onResult(AdRewardResult(false, AdShowResult.Failed(NO_BID_CANDIDATE), session.sessionId))
            return
        }
        showSelected(
            selection.winner, format, activity, position, appOpenHostContainer, attempt,
            onSessionStarted = { it.bidResult(decision.toEventData(format)) },
            onResult = onResult,
        )
    }

    private fun BidDecision.toEventData(format: AdFormat): AdBidEventData {
        val provider = config.provider as BiddingProviderConfig
        return AdBidEventData(
            winnerPlatform = selection?.winner,
            admobAvailable = admobAvailable,
            topOnAvailable = topOnAvailable,
            admobValue = admobPriceUsd,
            topOnValue = topOnPriceUsd,
            winningValue = selection?.priceUsd,
            admobAdUnitId = provider.admob.adUnitId(format),
            topOnAdUnitId = provider.topon.adUnitId(format),
        )
    }

    private fun failShow(
        format: AdFormat,
        position: String,
        reason: String,
        onResult: (AdShowResult) -> Unit,
    ) {
        emitFacadeShowFailure(format, position, reason)
        onResult(AdShowResult.Failed(reason))
    }

    private fun failRewardedShow(
        position: String,
        reason: String,
        onResult: (AdRewardResult) -> Unit,
    ) {
        val sessionId = emitFacadeShowFailure(AdFormat.REWARDED, position, reason)
        onResult(
            AdRewardResult(
                rewardEarned = false,
                showResult = AdShowResult.Failed(reason),
                sessionId = sessionId,
            ),
        )
    }

    private fun emitFacadeShowFailure(
        format: AdFormat,
        position: String,
        reason: String,
    ): String? {
        if (!::facadeEvents.isInitialized || !::config.isInitialized) return null
        val session = facadeEvents.begin(format, position, config.provider.adUnitId(format))
        session.showFailure(reason)
        return session.sessionId
    }

    /** Current global UMP state, independent of the selected mediation provider. */
    val consentSnapshot: AdConsentSnapshot
        get() = if (::umpConsentManager.isInitialized) umpConsentManager.snapshot else AdConsentSnapshot.UNKNOWN

    val isPrivacyOptionsRequired: Boolean
        get() = ::umpConsentManager.isInitialized && umpConsentManager.isPrivacyOptionsRequired

    fun showPrivacyOptions(
        activity: Activity,
        onDismissed: (errorMessage: String?) -> Unit = {},
    ) = onMain {
        if (!::umpConsentManager.isInitialized || activity.isFinishing || activity.isDestroyed) {
            onDismissed("activity_not_available")
            return@onMain
        }
        umpConsentManager.showPrivacyOptions(activity) { errorMessage ->
            if (umpConsentManager.snapshot.canRequestAds) startProviderInitialization()
            onDismissed(errorMessage)
        }
    }

    private fun failAutoBiddingAppOpenOpportunity(reason: String) {
        val decision = AdBiddingCoordinator.select(AdFormat.APP_OPEN)
        val session = decision.selection?.let { selection ->
            when (selection.winner) {
                AdPlatform.ADMOB -> AdMobAds.beginBiddingSession(
                    AdFormat.APP_OPEN,
                    config.appOpenPosition,
                )
                AdPlatform.TOPON -> TopOnAds.beginBiddingSession(
                    AdFormat.APP_OPEN,
                    config.appOpenPosition,
                )
            }
        } ?: facadeEvents.begin(
            AdFormat.APP_OPEN,
            config.appOpenPosition,
            config.provider.adUnitId(AdFormat.APP_OPEN),
        )
        session.bidResult(decision.toEventData(AdFormat.APP_OPEN))
        session.showFailure(
            if (decision.selection == null) NO_BID_CANDIDATE else reason,
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private const val CONSENT_NOT_OBTAINED = "consent_not_obtained"
    private const val NO_BID_CANDIDATE = "no_preloaded_ad"
}

/** A waiting opportunity can use one ready bidder before aggregate initialization completes. */
internal fun displayOpportunityFailureReason(
    commonFailure: String?,
    state: AdsState,
    hasReadyBiddingProvider: Boolean,
): String? {
    if (state == AdsState.INITIALIZING && hasReadyBiddingProvider &&
        (commonFailure == null || commonFailure == "sdk_initializing")
    ) return null
    return commonFailure ?: when (state) {
        AdsState.NOT_INITIALIZED -> "sdk_not_initialized"
        AdsState.INITIALIZING -> "sdk_initializing"
        AdsState.FAILED -> "sdk_initialization_failed"
        AdsState.READY -> null
    }
}

private enum class InitializationStage {
    NOT_STARTED,
    WAITING_FOR_UMP,
    PROVIDER_INITIALIZING,
    COMPLETE,
    FAILED,
}

private fun AdMobState.toCommonState(): AdsState = when (this) {
    AdMobState.NOT_INITIALIZED -> AdsState.NOT_INITIALIZED
    AdMobState.INITIALIZING -> AdsState.INITIALIZING
    AdMobState.READY -> AdsState.READY
    AdMobState.FAILED -> AdsState.FAILED
}

private fun TopOnState.toCommonState(): AdsState = when (this) {
    TopOnState.NOT_INITIALIZED -> AdsState.NOT_INITIALIZED
    TopOnState.INITIALIZING -> AdsState.INITIALIZING
    TopOnState.READY -> AdsState.READY
    TopOnState.FAILED -> AdsState.FAILED
}
