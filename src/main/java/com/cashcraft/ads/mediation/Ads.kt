package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.admob.AdMobConfig
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdBiddingCoordinator
import com.cashcraft.ads.mediation.internal.AdBidEventData
import com.cashcraft.ads.mediation.internal.AdEventDispatcher
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.AdShowSession
import com.cashcraft.ads.mediation.internal.AutoAppOpenController
import com.cashcraft.ads.mediation.internal.BannerProviderReadiness
import com.cashcraft.ads.mediation.internal.BannerReadiness
import com.cashcraft.ads.mediation.internal.BidDecision
import com.cashcraft.ads.mediation.internal.DisplayOpportunityController
import com.cashcraft.ads.mediation.internal.FullScreenShowAttempt
import com.cashcraft.ads.mediation.internal.FullScreenShowGate
import com.cashcraft.ads.mediation.internal.UmpConsentManager
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import com.cashcraft.ads.mediation.internal.nativeads.NativeAvailability
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
import com.cashcraft.ads.mediation.internal.nativeads.NativeSlot
import com.cashcraft.ads.mediation.internal.nativeads.nativeAvailability
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
    private var initializationCallback: ((Boolean) -> Unit)? = null
    private val nativeReadinessListeners = CopyOnWriteArrayList<() -> Unit>()
    private val pendingNativePreloads = linkedMapOf<AdPlatform, ResolvedNativeRequest>()
    private val providerInitializationStarted = AtomicBoolean(false)
    private val bannerProviders = BannerProviderReadiness()

    /** True from SDK handoff until the full-screen ad finishes; waiting alone is not showing. */
    val isFullScreenAdShowing: Boolean
        get() = FullScreenShowGate.isAnyAdShowing

    /** Configured mode, or null before initialization. An individual ad's platform is in its event. */
    @Volatile
    var mediationMode: AdMediationMode? = null
        private set

    @Volatile
    private var initializationStage = InitializationStage.NOT_STARTED

    private lateinit var application: Application
    private lateinit var config: AdsConfig
    private var nativeLogger: com.cashcraft.ads.mediation.internal.AdsModuleLogger? = null
    private lateinit var facadeEvents: AdEventDispatcher
    private lateinit var umpConsentManager: UmpConsentManager
    private lateinit var autoBiddingAppOpenController: AutoAppOpenController<Unit>
    private var admobInitializationResult: Boolean? = null
    private var topOnInitializationResult: Boolean? = null

    private val lifecycleListener = object : AdLifecycleMonitor.Listener {
        override fun onActivityResumed(activity: Activity) {
            gatherConsentIfNeeded(activity)
            startPendingNativePreloads()
        }
    }

    /** Fixed provider only; null before initialization and in bidding mode. Not the last ad's winner. */
    val platform: AdPlatform?
        get() = when (mediationMode) {
            AdMediationMode.ADMOB -> AdPlatform.ADMOB
            AdMediationMode.TOPON -> AdPlatform.TOPON
            AdMediationMode.BIDDING, null -> null
        }

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

    /**
     * Fixes the configuration for this process. Repeated calls with the same Application and an
     * equal AdsConfig only observe the original initialization result; they do not reconfigure it.
     * Reuse the config's listener instances as well as its values.
     * A supplied callback replaces the single pending initialization observer; omitting it does not.
     * @throws IllegalArgumentException if a subsequent call changes the Application or AdsConfig.
     */
    fun initialize(
        application: Application,
        config: AdsConfig,
        onInitialized: ((Boolean) -> Unit)? = null,
    ) {
        var isFirstInitialization = false
        synchronized(this) {
            if (::config.isInitialized) {
                require(this.application === application) {
                    "Ads is already initialized with a different Application"
                }
                require(this.config == config) {
                    "Ads is already initialized with a different AdsConfig; reuse the original configuration"
                }
                onInitialized?.let { callback -> onMain { observeInitialization(callback) } }
                return
            }
            this.application = application
            this.config = config
            mediationMode = config.provider.mediationMode
            nativeLogger = com.cashcraft.ads.mediation.internal.AdsModuleLogger(config.loggingEnabled, config.logTag)
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
                    this.config.autoShowAppOpen && this.config.provider is BiddingProviderConfig &&
                        this.config.provider.isFormatEnabled(AdFormat.APP_OPEN)
                },
                isProviderReady = { initializationStage == InitializationStage.COMPLETE },
                providerFailureReason = { null },
                isAdAvailable = { isReady(AdFormat.APP_OPEN) },
                beginOpportunity = { Unit },
                show = { activity, _ ->
                    showAppOpen(activity, this.config.appOpenPosition)
                },
                fail = { _, reason -> failAutoBiddingAppOpenOpportunity(reason) },
                noAdFailureReason = NO_BID_CANDIDATE,
            )
            initializationStage = InitializationStage.WAITING_FOR_UMP
            onInitialized?.let { callback -> onMain { observeInitialization(callback) } }
            isFirstInitialization = true
        }

        if (!isFirstInitialization) return
        onMain {
            bannerProviders.configure(config.provider)
            AdLifecycleMonitor.addListener(lifecycleListener)
            AdLifecycleMonitor.install(application)
            if (config.umpConsent.enabled) {
                AdLifecycleMonitor.currentActivity?.let(::gatherConsentIfNeeded)
            } else {
                startProviderInitialization()
            }
        }
    }

    /**
     * Observes platform initialization once, on the main thread; true means at least one platform
     * is usable, not that an ad is loaded. Replaces any previous pending observer.
     * A terminal result is delivered immediately. Call the returned function on timeout or page
     * destruction to release this observer without cancelling SDK initialization. An old observer's
     * cancellation cannot clear a newer observer. Cancellation may be called from any thread.
     */
    fun observeInitialization(onInitialized: (Boolean) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Initialization observation requires the main thread" }
        // Each registration has its own identity even when the caller reuses a callback instance.
        val callback: (Boolean) -> Unit = { onInitialized(it) }
        initializationCallback = callback
        when (state) {
            AdsState.READY -> notifyInitialization(true)
            AdsState.FAILED -> notifyInitialization(false)
            AdsState.NOT_INITIALIZED, AdsState.INITIALIZING -> Unit
        }
        return {
            onMain {
                if (initializationCallback === callback) initializationCallback = null
            }
        }
    }

    private fun notifyInitialization(success: Boolean) {
        val callback = initializationCallback
        initializationCallback = null
        callback?.let { runCatching { it(success) } }
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
        bannerProviders.started()
        notifyNativeReadiness()
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
                onInitialized = { success ->
                    finishSingleProviderInitialization(AdPlatform.ADMOB, success)
                },
                initialActivity = initialActivity,
            )

            is TopOnProviderConfig -> TopOnAds.initialize(
                application = application,
                commonConfig = config,
                mediationMode = AdMediationMode.TOPON,
                onInitialized = { success ->
                    finishSingleProviderInitialization(AdPlatform.TOPON, success)
                },
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

    private fun finishSingleProviderInitialization(platform: AdPlatform, success: Boolean) {
        bannerProviders.completed(platform, success)
        finishInitialization(success)
    }

    private fun finishBiddingProviderInitialization(isAdMob: Boolean, success: Boolean) {
        if (isAdMob) admobInitializationResult = success else topOnInitializationResult = success
        bannerProviders.completed(if (isAdMob) AdPlatform.ADMOB else AdPlatform.TOPON, success)
        notifyNativeReadiness()
        val admobResult = admobInitializationResult ?: return
        val topOnResult = topOnInitializationResult ?: return
        finishInitialization(admobResult || topOnResult)
    }

    private fun finishInitialization(success: Boolean) {
        initializationStage = if (success) InitializationStage.COMPLETE else InitializationStage.FAILED
        if (!success) bannerProviders.failedPending()
        notifyNativeReadiness()
        notifyInitialization(success)
        if (success && config.provider is BiddingProviderConfig) {
            autoBiddingAppOpenController.onProviderInitialized()
        }
    }

    fun isReady(format: AdFormat): Boolean = format != AdFormat.BANNER && format != AdFormat.NATIVE &&
        ::config.isInitialized && config.provider.isFormatEnabled(format) && consentSnapshot.canRequestAds &&
        when (config.provider) {
            is AdMobProviderConfig -> AdMobAds.isReady(format)
            is TopOnProviderConfig -> TopOnAds.isReady(format)
            is BiddingProviderConfig -> AdMobAds.isReady(format) || TopOnAds.isReady(format)
        }

    /**
     * 预热初始化配置中的 Native 自渲染库存，不创建页面或曝光。
     * 可在 initialize 后立即调用；逐平台等待许可、前台和就绪，不占用初始化观察者。
     * 重复调用合并已有库存；无页面需求时沿用五分钟闲置及后台清理规则。
     */
    fun preloadNative() = onMain {
        if (!::config.isInitialized) return@onMain
        val request = config.provider.resolveNativeRequest(NativeRequest("preload_native"))
        if (request.failureReason() != null) return@onMain
        request.candidates().forEach { pendingNativePreloads[requireNotNull(it.platform)] = it }
        startPendingNativePreloads()
    }

    private fun startPendingNativePreloads() {
        if (!AdLifecycleMonitor.isAppInForeground) return
        pendingNativePreloads.toMap().forEach { (platform, request) ->
            if (nativeAvailability(platform).ready) {
                pendingNativePreloads.remove(platform)
                runCatching { NativeAdCache.preload(application, request) }.onFailure { error ->
                    nativeLog("预加载", warning = true, error = error) { "原生库存预热失败，页面仍可重试" }
                }
            }
        }
    }

    /**
     * Resolves the default AdMob Banner configured by initialize; SDK readiness is not required.
     * Bidding configurations use their AdMob Banner, without running a Banner auction.
     * @throws IllegalStateException if initialize has not been called or no AdMob bannerId is configured.
     */
    fun bannerRequest(
        position: String,
        size: BannerSize = BannerSize.AnchoredAdaptive,
    ): BannerRequest {
        require(position.isNotBlank()) { "position must not be blank" }
        check(::config.isInitialized) { "Call Ads.initialize before using a configured Banner" }
        val ids = when (val provider = config.provider) {
            is AdMobProviderConfig -> provider.ids
            is BiddingProviderConfig -> provider.admob.ids
            is TopOnProviderConfig -> error("topon_banner_not_supported")
        }
        val id = checkNotNull(ids.bannerId) { "Configure AdMobIds.bannerId before using a configured Banner" }
        return BannerRequest(AdPlatform.ADMOB, id, position, size)
    }

    /**
     * Preloads the AdMob Banner for this measured placement.
     * With autoRefill=false, loads at most one ad once; polling never replenishes it.
     */
    fun preloadBanner(
        activity: Activity,
        request: BannerRequest,
        contentWidthDp: Int,
        autoRefill: Boolean = true,
    ) = onMain {
        if (!::config.isInitialized || request.platform != AdPlatform.ADMOB || contentWidthDp <= 0) {
            return@onMain
        }
        val provider = when (val configured = config.provider) {
            is AdMobProviderConfig -> configured
            is BiddingProviderConfig -> configured.admob
            is TopOnProviderConfig -> return@onMain
        }
        if (provider.preload.banner == 0 || request.sizeError(contentWidthDp) != null) return@onMain
        AdMobAds.preloadBanner(
            request = request,
            size = request.resolveAdSize(activity, contentWidthDp),
            bufferSize = provider.preload.banner,
            autoRefill = autoRefill,
        )
    }

    /** Request-time consent stays a live read; runtime privacy observation is not introduced. */
    internal fun bannerReadiness(platform: AdPlatform): BannerReadiness =
        bannerProviders.read(platform, consentSnapshot.canRequestAds)

    /** Main-thread registration and disposal, owned by the Banner's page lifecycle. */
    internal fun observeBannerReadiness(platform: AdPlatform, onChanged: () -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Banner observation requires the main thread" }
        return bannerProviders.observe(platform, onChanged)
    }

    internal fun bannerEvents(platform: AdPlatform): AdEventDispatcher? =
        if (!::config.isInitialized) null else AdEventDispatcher(
            application, platform, config.provider.mediationMode, config.eventListener,
            config.loggingEnabled, config.logTag,
        )

    internal val bannerRevenueListener: AdRevenueListener
        get() = if (::config.isInitialized) config.revenueListener else AdRevenueListener.NONE

    internal fun bannerLogger(): com.cashcraft.ads.mediation.internal.AdsModuleLogger =
        com.cashcraft.ads.mediation.internal.AdsModuleLogger(
            ::config.isInitialized && config.loggingEnabled,
            if (::config.isInitialized) config.logTag else "AdsMediation",
        )

    /**
     * 无需业务提供容器。TopOn 使用 Activity 的 android.R.id.content（回退到 decorView）
     * 挂载临时广告容器，并在关闭或失败后移除；AdMob 直接调用 SDK 的全屏展示接口。
     */
    fun showAppOpen(
        activity: Activity,
        position: String = "manual",
        onResult: (AdShowResult) -> Unit = {},
    ) = showAppOpenInternal(activity, position, onResult)

    private fun showAppOpenInternal(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit,
    ) = onMain {
        val failure = commonShowFailure(AdFormat.APP_OPEN)
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
            )
            is BiddingProviderConfig -> bidAndShow(
                format = AdFormat.APP_OPEN,
                activity = activity,
                position = position,
                onResult = { onResult(it.showResult) },
            )
        }
    }

    fun showInterstitial(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit = {},
    ) = onMain {
        val failure = commonShowFailure(AdFormat.INTERSTITIAL)
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
        val failure = commonShowFailure(AdFormat.REWARDED)
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

    /**
     * 等待平台结果，截止时使用可用缓存兜底；取消机会不停止底层加载。
     * 开屏等待在宿主暂停时冻结计时，加载继续；恢复后优先展示有效缓存，否则继续剩余等待。
     * 销毁宿主、切换到其他 Activity 或主动 cancel() 仍会结束机会。
     * 无需业务提供容器：TopOn 默认挂载到 Activity 的 android.R.id.content，回退到 decorView。
     */
    fun showAppOpenWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long = 12_000L,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.APP_OPEN, activity, position, timeoutMillis, isSceneValid,
        onResult = { onResult(it.showResult) },
    )

    fun showInterstitialWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long = 5_000L,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.INTERSTITIAL, activity, position, timeoutMillis, isSceneValid,
        onResult = { onResult(it.showResult) },
    )

    fun showRewardedWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long = 5_000L,
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
        onResult: (AdRewardResult) -> Unit,
    ): AdDisplayOpportunity {
        require(format != AdFormat.BANNER) { "Banner does not use full-screen display opportunities" }
        if (format == AdFormat.NATIVE) {
            onMain { onResult(AdRewardResult(false, AdShowResult.Failed("unsupported_ad_format"))) }
            return AdDisplayOpportunity { }
        }
        // Capture before posting: a congested main queue must not extend the caller's deadline.
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var controller: DisplayOpportunityController
        val handle = AdDisplayOpportunity { onMain { controller.cancel() } }
        var admobFailureVersion = 0L
        var topOnFailureVersion = 0L
        val boundActivity = activity
        val listener = object : AdLifecycleMonitor.Listener {
            override fun onActivityPaused(activity: Activity) {
                if (activity === boundActivity) {
                    if (format == AdFormat.APP_OPEN) controller.pause()
                    else controller.cancel("activity_not_resumed")
                }
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === boundActivity) controller.cancel("activity_not_available")
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity !== boundActivity) controller.cancel("activity_not_resumed")
                else if (format == AdFormat.APP_OPEN) controller.resume()
            }
            override fun onAppEnteredBackground() {
                if (format == AdFormat.APP_OPEN) controller.pause()
                else controller.cancel("app_not_in_foreground")
            }
        }
        controller = DisplayOpportunityController(
            startedAtMillis = startedAt,
            timeoutMillis = timeoutMillis,
            nowMillis = SystemClock::elapsedRealtime,
            schedule = { check, delay -> mainHandler.postDelayed(check, delay) },
            unschedule = mainHandler::removeCallbacks,
            precondition = { opportunityPrecondition(format) },
            sceneValid = isSceneValid,
            hostFailure = { AdLifecycleMonitor.activityWaitFailureReason(activity) },
            loadSnapshot = {
                // SDK caches can change between calls; reuse each readiness sample for both decisions.
                val admobEnabled = config.provider.isFormatEnabled(AdPlatform.ADMOB, format)
                val topOnEnabled = config.provider.isFormatEnabled(AdPlatform.TOPON, format)
                val admobReady = admobEnabled && AdMobAds.isReady(format)
                val topOnReady = topOnEnabled && TopOnAds.isReady(format)
                val admobFinished = !admobEnabled || admobReady || AdMobAds.state == AdMobState.FAILED ||
                    AdMobAds.loadFailureVersion(format) != admobFailureVersion
                val topOnFinished = !topOnEnabled || topOnReady || TopOnAds.state == TopOnState.FAILED ||
                    TopOnAds.loadFailureVersion(format) != topOnFailureVersion
                when (config.provider) {
                    is AdMobProviderConfig -> DisplayOpportunityController.LoadSnapshot(admobReady, admobFinished)
                    is TopOnProviderConfig -> DisplayOpportunityController.LoadSnapshot(topOnReady, topOnFinished)
                    is BiddingProviderConfig -> DisplayOpportunityController.LoadSnapshot(
                        ready = admobReady || topOnReady,
                        settled = admobFinished && topOnFinished,
                    )
                }
            },
            ensureLoaded = {
                if (config.provider.isFormatEnabled(AdPlatform.TOPON, format)) TopOnAds.ensureLoaded(format)
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
                        winner, format, activity, position, attempt,
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
            admobFailureVersion = AdMobAds.loadFailureVersion(format)
            topOnFailureVersion = TopOnAds.loadFailureVersion(format)
            AdLifecycleMonitor.addListener(listener)
            if (format == AdFormat.APP_OPEN && AdLifecycleMonitor.activityWaitFailureReason(activity) in
                setOf("activity_not_resumed", "app_not_in_foreground")
            ) controller.pause()
            controller.start()
        }
        return handle
    }

    private fun opportunityPrecondition(format: AdFormat): String? = displayOpportunityFailureReason(
        commonFailure = commonShowFailure(format),
        state = state,
        hasReadyBiddingProvider = providerInitializationStarted.get() &&
            ::config.isInitialized && config.provider is BiddingProviderConfig &&
            ((config.provider.isFormatEnabled(AdPlatform.ADMOB, format) && AdMobAds.state == AdMobState.READY) ||
                (config.provider.isFormatEnabled(AdPlatform.TOPON, format) && TopOnAds.state == TopOnState.READY)),
    )

    /** Both waiting and immediate bidding pass the same owner all the way to SDK show(). */
    private fun showSelected(
        winner: AdPlatform,
        format: AdFormat,
        activity: Activity,
        position: String,
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
                AdFormat.BANNER -> error("Banner does not use full-screen display")
                AdFormat.NATIVE -> onResult(AdRewardResult(false, AdShowResult.Failed("unsupported_ad_format")))
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
                AdFormat.BANNER -> error("Banner does not use full-screen display")
                AdFormat.NATIVE -> onResult(AdRewardResult(false, AdShowResult.Failed("unsupported_ad_format")))
                AdFormat.APP_OPEN -> TopOnAds.showBiddingAppOpen(
                    activity, position, onSessionStarted, callback, attempt, created,
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

    private fun commonShowFailure(format: AdFormat): String? = when {
        !::config.isInitialized -> "sdk_not_initialized"
        !config.provider.isFormatEnabled(format) -> "ad_format_disabled"
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
    ) {
        require(format != AdFormat.BANNER) { "Banner does not use full-screen bidding" }
        val attempt = FullScreenShowAttempt().apply { guard = { commonShowFailure(format) } }
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
            selection.winner, format, activity, position, attempt,
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

    internal fun nativeAvailability(platform: AdPlatform): NativeAvailability {
        if (!::config.isInitialized) return NativeAvailability(failure = "sdk_not_initialized")
        return nativeAvailability(
            platformConfigured = config.provider is BiddingProviderConfig || config.provider.platform == platform,
            consentPending = initializationStage == InitializationStage.WAITING_FOR_UMP,
            consentAllowed = consentSnapshot.canRequestAds,
            providerState = when (platform) {
                AdPlatform.ADMOB -> AdMobAds.state.toCommonState()
                AdPlatform.TOPON -> TopOnAds.state.toCommonState()
            },
        )
    }

    internal fun resolveNativeRequest(request: NativeRequest): ResolvedNativeRequest? =
        if (::config.isInitialized) config.provider.resolveNativeRequest(request) else null

    internal fun nativeAvailability(request: ResolvedNativeRequest): NativeAvailability {
        request.failureReason()?.let { return NativeAvailability(failure = it) }
        val candidates = request.candidates().map { nativeAvailability(requireNotNull(it.platform)) }
        return candidates.firstOrNull { it.ready } ?: candidates.firstOrNull { it.failure == null }
            ?: candidates.first()
    }

    internal fun nativeLog(position: String, debug: Boolean = false, warning: Boolean = false,
        error: Throwable? = null, message: () -> String) {
        // 日志不能中断平台交付、收益回调或资源清理。
        runCatching { nativeLogger?.native(position, debug, warning, error, message) }
    }

    internal fun beginNativeInventoryLoad(platform: AdPlatform, adUnitId: String): com.cashcraft.ads.mediation.internal.AdLoadSession {
        val mode = if (platform == AdPlatform.ADMOB) AdMediationMode.ADMOB else AdMediationMode.TOPON
        return AdEventDispatcher(application, platform, mode, config.eventListener,
            config.loggingEnabled, config.logTag).beginLoad(AdFormat.NATIVE, adUnitId, 1)
    }

    internal fun newNativeSlot(request: ResolvedNativeRequest): NativeSlot? {
        if (!::config.isInitialized || request.failureReason() != null) return null
        val platform = requireNotNull(request.candidates().first().platform)
        val mode = if (request.isBidding) AdMediationMode.BIDDING
            else if (platform == AdPlatform.ADMOB) AdMediationMode.ADMOB else AdMediationMode.TOPON
        return AdEventDispatcher(application, platform, mode, config.eventListener,
            config.loggingEnabled, config.logTag).nativeSlot(request, config.revenueListener)
    }

    internal fun observeNativeReadiness(listener: () -> Unit): AutoCloseable {
        val subscribed = AtomicBoolean(true)
        val guarded = { if (subscribed.get()) listener() }
        nativeReadinessListeners.add(guarded)
        return AutoCloseable {
            subscribed.set(false)
            nativeReadinessListeners.remove(guarded)
        }
    }

    internal fun notifyNativeReadiness() {
        if (!consentSnapshot.canRequestAds) {
            com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache.consentRevoked()
        }
        startPendingNativePreloads()
        nativeReadinessListeners.forEach { runCatching(it) }
    }

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
            notifyNativeReadiness()
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
