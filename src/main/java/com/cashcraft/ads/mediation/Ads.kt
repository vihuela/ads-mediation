package com.cashcraft.ads.mediation

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.cashcraft.ads.mediation.admob.AdMobAds
import com.cashcraft.ads.mediation.internal.admob.AdMobConfig
import com.cashcraft.ads.mediation.admob.AdMobState
import com.cashcraft.ads.mediation.internal.AdPolicyAttempt
import com.cashcraft.ads.mediation.internal.AdPolicyChecker
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.AdUsageStore
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
import com.cashcraft.ads.mediation.internal.FullScreenAdAuction
import com.cashcraft.ads.mediation.internal.FullScreenLoadSignals
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
    private val initializationStateListeners = CopyOnWriteArrayList<(AdsState) -> Unit>()
    private val pendingInitializationStates = java.util.ArrayDeque<Pair<AdsState, List<(AdsState) -> Unit>>>()
    private var notifyingInitializationState = false
    private var lastNotifiedInitializationState = AdsState.NOT_INITIALIZED
    private var initializationAttempt = 0L
    private var retryingProviders = false
    @Volatile
    private var initializationFailure: InitializationFailure? = null
    private val nativeReadinessListeners = CopyOnWriteArrayList<() -> Unit>()
    private val pendingNativePreloads = linkedMapOf<Pair<AdPlatform, String>, ResolvedNativeRequest>()
    private val providerInitializationStarted = AtomicBoolean(false)
    private val bannerProviders = BannerProviderReadiness()
    @Volatile internal var policyChecker: AdPolicyChecker? = null
        private set
    private val policyListeners = CopyOnWriteArrayList<() -> Unit>()
    private var policyUpdatePosted = false
    private val observedEvents = AdEventListener { event ->
        config.eventListener.onEvent(event)
    }

    /** Replace one complete effective policy. Existing SDK displays keep their ownership. */
    fun updatePolicy(policy: AdPolicy) = onMain {
        policyChecker?.policy = policy
        onPolicyUsageChanged()
    }

    internal fun isPlatformEnabled(platform: AdPlatform): Boolean =
        policyChecker?.policy?.platforms?.get(platform) != false

    internal fun canLoadAds(platform: AdPlatform): Boolean =
        isPlatformEnabled(platform) && policyChecker?.checkLoad() !is AdPolicyCheckResult.Blocked

    internal fun addPolicyListener(listener: () -> Unit): () -> Unit {
        policyListeners += listener
        return { policyListeners -= listener }
    }

    internal fun notifyAdBlocked(info: AdBlockInfo) {
        if (::config.isInitialized) runCatching { config.onAdBlocked(info) }
    }

    internal fun onPolicyUsageChanged() = onMain {
        if (policyUpdatePosted || !::config.isInitialized) return@onMain
        policyUpdatePosted = true
        mainHandler.post {
            policyUpdatePosted = false
            AdMobAds.onPolicyChanged()
            TopOnAds.onPolicyChanged()
            notifyNativeReadiness()
            policyListeners.forEach { runCatching(it) }
            FullScreenLoadSignals.changed()
        }
    }

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
            onPolicyUsageChanged()
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

    /** True only for a failed consent request or a supported transient SDK failure. */
    val canRetryInitialization: Boolean
        get() = state == AdsState.FAILED && initializationFailure?.retryable == true

    /**
     * Main thread only. Reuses installed configuration; null uses the current resumed host or waits
     * for the next resume. Other states and terminal failures are no-ops. A supplied host must be
     * live, verified RESUMED, and belong to the installed Application, as with [initialize].
     * Scheduling/backoff and network observation belong to the app, never this library.
     */
    fun retryInitialization(activity: Activity? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Initialization retry requires the main thread" }
        if (!canRetryInitialization) return
        activity?.let {
            require(it.application === application && !it.isFinishing && !it.isDestroyed) {
                "Retry host must be live and belong to the installed Application"
            }
            require(if (it is LifecycleOwner) it.lifecycle.currentState == Lifecycle.State.RESUMED
                else AdLifecycleMonitor.currentActivity === it) { "Retry host must be verified RESUMED" }
        }
        val failed = requireNotNull(initializationFailure)
        if (failed.stage == InitializationStage.WAITING_FOR_UMP && !umpConsentManager.prepareRetry()) return
        retryingProviders = failed.stage == InitializationStage.PROVIDER_INITIALIZING
        initializationAttempt++
        initializationFailure = null
        providerInitializationStarted.set(false)
        if (admobInitializationResult != true) admobInitializationResult = null
        if (topOnInitializationResult != true) topOnInitializationResult = null
        initializationStage = InitializationStage.WAITING_FOR_UMP
        notifyInitializationState()
        activity?.let { AdLifecycleMonitor.install(application, it) }
        AdLifecycleMonitor.currentActivity?.let(::gatherConsentIfNeeded)
    }

    /** Main-thread registration, immediate snapshot and persistent transitions, independent of Splash. */
    fun addInitializationStateListener(listener: (AdsState) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Initialization observation requires the main thread" }
        val registration: (AdsState) -> Unit = { listener(it) }
        initializationStateListeners += registration
        runCatching { registration(state) }
        return { onMain { initializationStateListeners.remove(registration) } }
    }

    private fun notifyInitializationState() {
        val current = state
        if (current == lastNotifiedInitializationState) return
        lastNotifiedInitializationState = current
        pendingInitializationStates.addLast(current to initializationStateListeners.toList())
        if (notifyingInitializationState) return
        notifyingInitializationState = true
        try {
            while (pendingInitializationStates.isNotEmpty()) {
                val (next, listeners) = pendingInitializationStates.removeFirst()
                listeners.forEach { listener ->
                    if (listener in initializationStateListeners) runCatching { listener(next) }
                }
            }
        } finally { notifyingInitializationState = false }
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
        initializeInternal(application, config, onInitialized, initialActivity = null)
    }

    /**
     * Initializes from an already RESUMED host, including when installation follows its onResume.
     * Must be called on the main thread, with a live Activity belonging to the original Application.
     * LifecycleOwner hosts must report RESUMED. A plain Activity must already be observed as resumed
     * by AdLifecycleMonitor; an unobserved plain Activity cannot be verified and is rejected.
     * The Application/config equality and callback rules of the Application overload still apply.
     * @throws IllegalStateException if called off the main thread.
     * @throws IllegalArgumentException if the host is unavailable, unverified, or changes the owner/config.
     */
    fun initialize(
        activity: Activity,
        config: AdsConfig,
        onInitialized: ((Boolean) -> Unit)? = null,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Activity initialization requires the main thread" }
        require(!activity.isFinishing && !activity.isDestroyed) { "Activity must be live and RESUMED" }
        require(
            if (activity is LifecycleOwner) activity.lifecycle.currentState == Lifecycle.State.RESUMED
            else AdLifecycleMonitor.currentActivity === activity
        ) { "Activity must be verified RESUMED; unobserved plain Activities are not supported" }
        initializeInternal(activity.application, config, onInitialized, initialActivity = activity)
    }

    private fun initializeInternal(
        application: Application,
        config: AdsConfig,
        onInitialized: ((Boolean) -> Unit)?,
        initialActivity: Activity?,
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
                initialActivity?.let { activity ->
                    AdLifecycleMonitor.install(application, activity)
                    gatherConsentIfNeeded(activity)
                }
                return
            }
            this.application = application
            this.config = config
            policyChecker = AdPolicyChecker(AdUsageStore(application, config.firstLaunchTimeMillis)).apply { policy = config.policy }
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
                listener = observedEvents,
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
            notifyInitializationState()
            bannerProviders.configure(config.provider)
            AdLifecycleMonitor.addListener(lifecycleListener)
            AdLifecycleMonitor.install(application, initialActivity)
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
        if (activity.isFinishing || activity.isDestroyed || activity.application !== application) return
        if (retryingProviders) {
            if (umpConsentManager.snapshot.canRequestAds) startProviderInitialization()
            else {
                initializationFailure = InitializationFailure(InitializationStage.WAITING_FOR_UMP, "consent_not_obtained", false)
                finishInitialization(false)
            }
            return
        }
        val token = initializationAttempt
        umpConsentManager.gatherConsent(
            activity = activity,
            onAdsAllowed = { if (token == initializationAttempt) startProviderInitialization() },
            onAdsUnavailable = { reason ->
                if (token == initializationAttempt) {
                    initializationFailure = InitializationFailure(InitializationStage.WAITING_FOR_UMP, reason,
                        umpConsentManager.failure?.retryable == true)
                    finishInitialization(false)
                }
            },
        )
    }

    private fun startProviderInitialization() {
        if (!umpConsentManager.snapshot.canRequestAds) return
        if (!providerInitializationStarted.compareAndSet(false, true)) return
        initializationStage = InitializationStage.PROVIDER_INITIALIZING
        retryingProviders = false
        val token = initializationAttempt
        notifyInitializationState()
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
                    eventListener = observedEvents,
                    revenueListener = config.revenueListener,
                    loggingEnabled = config.loggingEnabled,
                    logTag = config.logTag,
                    autoShowAppOpen = config.autoShowAppOpen,
                    appOpenPosition = config.appOpenPosition,
                    mediationMode = AdMediationMode.ADMOB,
                ),
                onInitialized = { success ->
                    if (token == initializationAttempt) finishSingleProviderInitialization(AdPlatform.ADMOB, success)
                },
                initialActivity = initialActivity,
            )

            is TopOnProviderConfig -> TopOnAds.initialize(
                application = application,
                commonConfig = config.copy(eventListener = observedEvents),
                mediationMode = AdMediationMode.TOPON,
                onInitialized = { success ->
                    if (token == initializationAttempt) finishSingleProviderInitialization(AdPlatform.TOPON, success)
                },
                initialActivity = initialActivity,
            )

            is BiddingProviderConfig -> {
                AdMobAds.initialize(
                    application = application,
                    config = AdMobConfig(
                        ids = provider.admob.ids,
                        preload = provider.admob.preload,
                        eventListener = observedEvents,
                        revenueListener = config.revenueListener,
                        loggingEnabled = config.loggingEnabled,
                        logTag = config.logTag,
                        autoShowAppOpen = false,
                        appOpenPosition = config.appOpenPosition,
                        mediationMode = AdMediationMode.BIDDING,
                    ),
                    onInitialized = { success -> if (token == initializationAttempt) finishBiddingProviderInitialization(true, success) },
                    initialActivity = initialActivity,
                )
                TopOnAds.initialize(
                    application = application,
                    commonConfig = config.copy(
                        provider = provider.topon,
                        autoShowAppOpen = false,
                        eventListener = observedEvents,
                    ),
                    mediationMode = AdMediationMode.BIDDING,
                    onInitialized = { success -> if (token == initializationAttempt) finishBiddingProviderInitialization(false, success) },
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
        if (!success && initializationFailure == null) {
            val usesAdMob = config.provider is AdMobProviderConfig || config.provider is BiddingProviderConfig
            val usesTopOn = config.provider is TopOnProviderConfig || config.provider is BiddingProviderConfig
            val reasons = listOfNotNull(
                if (usesAdMob) AdMobAds.initializationError?.let {
                    "admob:${it.javaClass.simpleName}:${it.message}"
                } else null,
                if (usesTopOn) TopOnAds.initializationFailureReason?.let { "topon:$it" } else null,
            )
            initializationFailure = InitializationFailure(initializationStage,
                reasons.joinToString("; ").ifEmpty { "provider_initialization_failed" },
                usesAdMob && AdMobAds.canRetryInitialization)
        }
        if (success) initializationFailure = null
        initializationStage = if (success) InitializationStage.COMPLETE else InitializationStage.FAILED
        if (!success) bannerProviders.failedPending()
        val callback = initializationCallback
        initializationCallback = null
        notifyInitializationState()
        notifyNativeReadiness()
        callback?.let { runCatching { it(success) } }
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
        listOf(false, true).forEach { fullScreen ->
            val request = config.provider.resolveNativeRequest(NativeRequest("preload_native"), fullScreen)
            if (request.failureReason() == null) request.candidates().forEach {
                pendingNativePreloads[requireNotNull(it.platform) to it.adUnitId] = it
            }
        }
        startPendingNativePreloads()
    }

    private fun startPendingNativePreloads() {
        if (!AdLifecycleMonitor.isAppInForeground) return
        pendingNativePreloads.toMap().forEach { (key, request) ->
            if (nativeAvailability(key.first).ready) {
                pendingNativePreloads.remove(key)
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
    @JvmOverloads
    fun bannerRequest(
        position: String,
        size: BannerSize = BannerSize.AnchoredAdaptive,
        mainType: AdMainType? = AdMainType.BANNER,
    ): BannerRequest {
        require(position.isNotBlank()) { "position must not be blank" }
        check(::config.isInitialized) { "Call Ads.initialize before using a configured Banner" }
        val ids = when (val provider = config.provider) {
            is AdMobProviderConfig -> provider.ids
            is BiddingProviderConfig -> provider.admob.ids
            is TopOnProviderConfig -> error("topon_banner_not_supported")
        }
        val id = checkNotNull(ids.bannerId) { "Configure AdMobIds.bannerId before using a configured Banner" }
        return BannerRequest(AdPlatform.ADMOB, id, position, size, mainType)
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
        if (!::config.isInitialized || !canLoadAds(request.platform) || request.platform != AdPlatform.ADMOB || contentWidthDp <= 0) {
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
            application, platform, config.provider.mediationMode, observedEvents,
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
    ) = onMain { showImmediate(AdFormat.APP_OPEN, activity, position, AdMainType.OPEN) { onResult(it.showResult) } }

    fun showInterstitial(
        activity: Activity,
        position: String,
        onResult: (AdShowResult) -> Unit = {},
    ) = onMain { showImmediate(AdFormat.INTERSTITIAL, activity, position, AdMainType.INTER) { onResult(it.showResult) } }

    fun showRewarded(
        activity: Activity,
        position: String,
        onResult: (AdRewardResult) -> Unit,
    ) = onMain { showImmediate(AdFormat.REWARDED, activity, position, AdMainType.REWARDED, onResult) }

    private fun showImmediate(format: AdFormat, activity: Activity, position: String,
        mainType: AdMainType?,
        onResult: (AdRewardResult) -> Unit) {
        val policy = AdPolicyAttempt(AdPolicyRequest(position, fullscreen = true,
            userInitiated = format == AdFormat.REWARDED, mainType = mainType))
        val checked = policy.check()
        if (checked is AdPolicyCheckResult.Blocked) {
            policy.complete()
            onResult(AdRewardResult(false, AdShowResult.Blocked(checked.reason)))
            return
        }
        commonShowFailure(format)?.let { reason ->
            policy.complete()
            val id = emitFacadeShowFailure(format, position, reason)
            onResult(AdRewardResult(false, AdShowResult.Failed(reason), id))
            return
        }
        val result: (AdRewardResult) -> Unit = { value ->
            policy.complete()
            onResult(value.copy(showResult = policy.result(value.showResult)))
        }
        if (config.provider is BiddingProviderConfig) {
            bidAndShow(format, activity, position, policy, result)
        } else {
            val attempt = FullScreenShowAttempt().also {
                it.policy = policy
                it.guard = { commonShowFailure(format) }
            }
            showSelected(config.provider.platform, format, activity, position, attempt,
                onSessionStarted = {}, onResult = result)
        }
    }

    /**
     * One open scene: wait for app-open/interstitial bidders, compare cached USD quotes, then use
     * cached full-screen Native only when neither format is available. One ad and one final result.
     * Binds the calling Activity on the main thread. Background time does not consume the budget.
     * Late loads refill shared caches; they never restart this task. Existing format entry points
     * keep their own policies. Configure timeout and Native layout once in [AdsConfig].
     */
    fun showOpen(position: String, onResult: (AdShowResult) -> Unit = {}): AdTask =
        showOpen(position, AdMainType.OPEN, onResult)

    fun showOpen(position: String, mainType: AdMainType?, onResult: (AdShowResult) -> Unit = {}): AdTask =
        showScene(AdFormat.APP_OPEN, position, mainType, onResult)

    /**
     * Wait for interstitial candidates, then use cached full-screen Native only if none is available.
     * One task, one full-screen owner and one result; Native keeps the original position.
     * Leaving the host while waiting cancels this task. Shared SDK loads continue for later requests.
     * [onLoadingChanged] runs on the main thread: true while waiting, false before either ad is
     * handed off or the wait ends. It may run synchronously; rejected requests never start loading.
     */
    fun showInter(
        position: String,
        onLoadingChanged: (Boolean) -> Unit = {},
        onResult: (AdShowResult) -> Unit = {},
    ): AdTask = showScene(AdFormat.INTERSTITIAL, position, AdMainType.INTER, onResult, onLoadingChanged)

    /** Rechecks the host's business condition while waiting and immediately before SDK handoff. */
    fun showInter(
        position: String,
        isSceneValid: () -> Boolean,
        onLoadingChanged: (Boolean) -> Unit = {},
        onResult: (AdShowResult) -> Unit = {},
    ): AdTask = showScene(AdFormat.INTERSTITIAL, position, AdMainType.INTER, onResult, onLoadingChanged, isSceneValid)

    fun showInter(
        position: String,
        mainType: AdMainType?,
        isSceneValid: () -> Boolean = { true },
        onLoadingChanged: (Boolean) -> Unit = {},
        onResult: (AdShowResult) -> Unit = {},
    ): AdTask = showScene(AdFormat.INTERSTITIAL, position, mainType, onResult, onLoadingChanged, isSceneValid)

    private fun showScene(
        scene: AdFormat,
        position: String,
        mainType: AdMainType?,
        onResult: (AdShowResult) -> Unit,
        onLoadingChanged: ((Boolean) -> Unit)? = null,
        isSceneValid: () -> Boolean = { true },
    ): AdTask {
        val startedAt = SystemClock.elapsedRealtime()
        var controller: DisplayOpportunityController? = null
        var nativeSession: NativeFullScreenSession? = null
        var cancelled = false
        val task = AdTask {
            onMain {
                cancelled = true
                nativeSession?.finish("opportunity_cancelled") ?: controller?.cancel()
            }
        }
        onMain {
            val trace: (String) -> Unit = { message ->
                nativeLogger?.sceneTask(scene, task.id, position, SystemClock.elapsedRealtime() - startedAt, message)
            }
            val policy = AdPolicyAttempt(AdPolicyRequest(position, fullscreen = true, mainType = mainType))
            var finished = false
            fun finish(result: AdShowResult) {
                if (finished) return
                finished = true
                task.detach()
                policy.complete()
                trace(when (result) {
                    AdShowResult.Dismissed -> "任务结束：广告已展示并关闭。"
                    is AdShowResult.Blocked -> "任务结束：策略拦截，原因=${result.reason.code}。"
                    is AdShowResult.Failed -> "任务结束：未展示广告；${result.reason.flowReason()}（原因码=${result.reason}）。"
                })
                runCatching { onResult(result) }
            }
            trace("开始${scene.flowName()}广告请求。")
            if (cancelled) { finish(AdShowResult.Failed("opportunity_cancelled")); return@onMain }
            if (position.isBlank()) { finish(AdShowResult.Failed("invalid_position")); return@onMain }
            if (!::config.isInitialized) { finish(AdShowResult.Failed("sdk_not_initialized")); return@onMain }
            val policyResult = policy.check()
            if (policyResult is AdPolicyCheckResult.Blocked) {
                finish(AdShowResult.Blocked(policyResult.reason)); return@onMain
            }
            val activity = AdLifecycleMonitor.requestActivity
            if (activity == null) { finish(AdShowResult.Failed("activity_not_available")); return@onMain }
            val timeout = runCatching {
                if (scene == AdFormat.APP_OPEN) config.openTimeoutMillis(position) else config.interTimeoutMillis(position)
            }.getOrElse {
                finish(AdShowResult.Failed("invalid_timeout")); return@onMain
            }
            trace("绑定展示页面：${activity.javaClass.simpleName}；最多等待 ${timeout} 毫秒。")
            val formats = if (scene == AdFormat.APP_OPEN) listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL)
                else listOf(AdFormat.INTERSTITIAL)
            val failures = formats.associateWith { format ->
                AdPlatform.entries.associateWith { platform ->
                    if (platform == AdPlatform.ADMOB) AdMobAds.loadFailureVersion(format)
                    else TopOnAds.loadFailureVersion(format)
                }
            }
            val auction = FullScreenAdAuction(
                formats = formats,
                read = { format, platform ->
                    val enabled = (isPlatformEnabled(platform) && config.provider.isFormatEnabled(platform, format))
                    val ready = enabled && if (platform == AdPlatform.ADMOB) AdMobAds.isReady(format) else TopOnAds.isReady(format)
                    val failed = if (platform == AdPlatform.ADMOB) {
                        AdMobAds.state == AdMobState.FAILED || AdMobAds.loadFailureVersion(format) != failures[format]?.get(platform)
                    } else {
                        TopOnAds.state == TopOnState.FAILED || TopOnAds.loadFailureVersion(format) != failures[format]?.get(platform)
                    }
                    FullScreenAdAuction.Inventory(ready, ready || failed || !enabled, enabled)
                },
                price = { format, platform ->
                    if (platform == AdPlatform.ADMOB) AdMobAds.bidPrice(format) else TopOnAds.bidPrice(format)
                },
                trace = trace,
                traceTable = { table ->
                    nativeLogger?.sceneTask(scene, task.id, position, SystemClock.elapsedRealtime() - startedAt, table, multiline = true)
                },
            )
            lateinit var waiting: DisplayOpportunityController
            val loadListener: () -> Unit = {
                auction.snapshot(acceptNewResults = waiting.elapsedMillis() < timeout)
                waiting.inventoryChanged()
            }
            val lifecycle = object : AdLifecycleMonitor.Listener {
                override fun onActivityPaused(paused: Activity) {
                    if (paused === activity) {
                        if (scene == AdFormat.APP_OPEN) waiting.pause() else waiting.cancel("activity_not_resumed")
                    }
                }
                override fun onActivityDestroyed(destroyed: Activity) {
                    if (destroyed === activity) waiting.cancel("activity_not_available")
                }
                override fun onActivityResumed(resumed: Activity) {
                    if (resumed !== activity) waiting.cancel("activity_not_resumed")
                    else if (scene == AdFormat.APP_OPEN) waiting.resume()
                }
                override fun onAppEnteredBackground() {
                    if (scene == AdFormat.APP_OPEN) waiting.pause() else waiting.cancel("app_not_in_foreground")
                }
            }
            waiting = DisplayOpportunityController(
                startedAtMillis = startedAt,
                timeoutMillis = timeout,
                nowMillis = SystemClock::elapsedRealtime,
                schedule = { check, delay -> mainHandler.postDelayed(check, delay) },
                unschedule = mainHandler::removeCallbacks,
                precondition = ::fullScreenSceneFailure,
                sceneValid = { !activity.isFinishing && !activity.isDestroyed && isSceneValid() },
                hostFailure = { AdLifecycleMonitor.activityWaitFailureReason(activity) },
                loadSnapshot = { auction.snapshot(acceptNewResults = waiting.elapsedMillis() < timeout) },
                ensureLoaded = {
                    AdMobAds.onPolicyChanged()
                    formats.filter { canLoadAds(AdPlatform.TOPON) && config.provider.isFormatEnabled(AdPlatform.TOPON, it) }.forEach(TopOnAds::ensureLoaded)
                },
                show = { attempt, callback ->
                    FullScreenLoadSignals.remove(loadListener)
                    val winner = auction.select()
                    if (winner == null) {
                        trace("${formats.joinToString("和") { it.flowName() }}无可用广告，尝试全屏原生兜底；仅使用缓存，不追加加载等待。")
                        val layout = config.nativeFullScreenLayout
                        if (layout == null) {
                            callback(AdRewardResult(false, AdShowResult.Failed("native_layout_not_configured")))
                        } else {
                            nativeSession = NativeFullScreenSession.start(activity, position, layout,
                                sceneValid = { !activity.isFinishing && !activity.isDestroyed && isSceneValid() },
                                attempt = attempt, trace = trace,
                            ) { callback(AdRewardResult(false, it)) }
                        }
                    } else {
                        trace("准备展示：${winner.bid.selection!!.winner.flowName()} ${winner.format.flowName()}。")
                        showSelected(winner.bid.selection.winner, winner.format, activity, position, attempt,
                            onSessionCreated = {
                                waiting.sessionStarted(it)
                                val sessionId = it.sessionId
                                it.onImpressionConfirmed = {
                                    trace("已确认广告曝光：${winner.bid.selection.winner.flowName()} ${winner.format.flowName()}；展示记录=$sessionId。")
                                }
                                trace("关联展示记录：${winner.bid.selection.winner.flowName()} ${winner.format.flowName()}；展示记录=${it.sessionId}。")
                            },
                            onSessionStarted = { session ->
                                if (config.provider is BiddingProviderConfig) session.bidResult(winner.bid.toEventData(winner.format))
                            },
                            onResult = callback,
                        )
                    }
                },
                onCleanup = {
                    AdLifecycleMonitor.removeListener(lifecycle)
                    FullScreenLoadSignals.remove(loadListener)
                },
                onResult = { finish(it.showResult) },
                showWhenEmpty = true,
                preferAvailableAfterResume = false,
                trace = trace,
                onLoadingChanged = onLoadingChanged,
                preferCachedImmediately = scene == AdFormat.INTERSTITIAL,
                policy = policy,
                excludeWaitingTime = { initializationStage == InitializationStage.WAITING_FOR_UMP },
            )
            controller = waiting
            // Capture pre-existing inventory even if posting to main already spent the budget.
            auction.snapshot(acceptNewResults = true)
            FullScreenLoadSignals.add(loadListener)
            AdLifecycleMonitor.addListener(lifecycle)
            if (scene == AdFormat.APP_OPEN && AdLifecycleMonitor.activityWaitFailureReason(activity) in
                setOf("activity_not_resumed", "app_not_in_foreground")) waiting.pause()
            waiting.start()
        }
        return task
    }

    private fun fullScreenSceneFailure(): String? = displayOpportunityFailureReason(
        commonFailure = when {
            !::config.isInitialized -> "sdk_not_initialized"
            initializationStage == InitializationStage.WAITING_FOR_UMP -> "sdk_initializing"
            !consentSnapshot.canRequestAds -> CONSENT_NOT_OBTAINED
            !providerInitializationStarted.get() -> "sdk_initializing"
            initializationStage == InitializationStage.FAILED -> "sdk_initialization_failed"
            else -> null
        },
        state = state,
        hasReadyBiddingProvider = providerInitializationStarted.get() &&
            ::config.isInitialized && config.provider is BiddingProviderConfig &&
            (AdMobAds.state == AdMobState.READY || TopOnAds.state == TopOnState.READY),
    )

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
        mainType = AdMainType.OPEN,
        onResult = { onResult(it.showResult) },
    )

    /** Uses cached Native inventory only. The layout must provide a visible close action.
     * Owns the same full-screen gate as interstitial/app-open/rewarded ads, through Activity teardown.
     * Native impressions and revenue retain their NATIVE format and supplied business position.
     */
    fun showNativeFullScreen(
        activity: Activity,
        position: String,
        layout: NativeLayout.Custom,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity = showNativeFullScreen(activity, position, layout, AdMainType.NATIVE_FULLSCREEN, isSceneValid, onResult)

    fun showNativeFullScreen(
        activity: Activity,
        position: String,
        layout: NativeLayout.Custom,
        mainType: AdMainType?,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity {
        var session: NativeFullScreenSession? = null
        var cancelled = false
        val opportunity = AdDisplayOpportunity {
            onMain { cancelled = true; session?.finish("opportunity_cancelled") }
        }
        onMain {
            if (cancelled) {
                opportunity.detach()
                onResult(AdShowResult.Failed("opportunity_cancelled"))
            } else {
                val attempt = FullScreenShowAttempt().also {
                    it.policy = AdPolicyAttempt(AdPolicyRequest(position, fullscreen = true, mainType = mainType))
                }
                session = NativeFullScreenSession.start(activity, position, layout, isSceneValid, attempt = attempt) {
                    opportunity.detach()
                    onResult(it)
                }
            }
        }
        return opportunity
    }

    fun showRewardedWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long = if (::config.isInitialized) config.rewardedTimeoutMillis(position) else 3_000L,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdRewardResult) -> Unit = {},
    ): AdDisplayOpportunity = showRewardedWhenReady(activity, position, AdMainType.REWARDED, timeoutMillis, isSceneValid, onResult)

    fun showRewardedWhenReady(
        activity: Activity,
        position: String,
        mainType: AdMainType?,
        timeoutMillis: Long = if (::config.isInitialized) config.rewardedTimeoutMillis(position) else 3_000L,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdRewardResult) -> Unit = {},
    ): AdDisplayOpportunity = createOpportunity(
        AdFormat.REWARDED, activity, position, timeoutMillis, isSceneValid, mainType, onResult,
    )

    private fun createOpportunity(
        format: AdFormat,
        activity: Activity,
        position: String,
        timeoutMillis: Long,
        isSceneValid: () -> Boolean,
        mainType: AdMainType?,
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
                val admobEnabled = (isPlatformEnabled(AdPlatform.ADMOB) && config.provider.isFormatEnabled(AdPlatform.ADMOB, format))
                val topOnEnabled = (isPlatformEnabled(AdPlatform.TOPON) && config.provider.isFormatEnabled(AdPlatform.TOPON, format))
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
                AdMobAds.onPolicyChanged()
                if ((isPlatformEnabled(AdPlatform.TOPON) && config.provider.isFormatEnabled(AdPlatform.TOPON, format))) TopOnAds.ensureLoaded(format)
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
            preferCachedImmediately = format != AdFormat.APP_OPEN,
            policy = AdPolicyAttempt(AdPolicyRequest(position, fullscreen = true,
                userInitiated = format == AdFormat.REWARDED, mainType = mainType)),
            excludeWaitingTime = { initializationStage == InitializationStage.WAITING_FOR_UMP },
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
        commonFailure = commonShowFailure(format).let { failure ->
            if (failure == CONSENT_NOT_OBTAINED && initializationStage == InitializationStage.WAITING_FOR_UMP)
                "sdk_initializing" else failure
        },
        state = state,
        hasReadyBiddingProvider = providerInitializationStarted.get() &&
            ::config.isInitialized && config.provider is BiddingProviderConfig &&
            (((isPlatformEnabled(AdPlatform.ADMOB) && config.provider.isFormatEnabled(AdPlatform.ADMOB, format)) && AdMobAds.state == AdMobState.READY) ||
                ((isPlatformEnabled(AdPlatform.TOPON) && config.provider.isFormatEnabled(AdPlatform.TOPON, format)) && TopOnAds.state == TopOnState.READY)),
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
        val originalGuard = attempt.handoffGuard
        attempt.handoffGuard = { originalGuard?.invoke() ?: if (!isPlatformEnabled(winner)) "ad_platform_disabled" else null }
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
        policy: AdPolicyAttempt,
        onResult: (AdRewardResult) -> Unit,
    ) {
        require(format != AdFormat.BANNER) { "Banner does not use full-screen bidding" }
        val attempt = FullScreenShowAttempt().apply {
            this.policy = policy
            guard = { commonShowFailure(format) }
        }
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
            platformConfigured = isPlatformEnabled(platform) && (config.provider is BiddingProviderConfig || config.provider.platform == platform),
            consentPending = initializationStage == InitializationStage.WAITING_FOR_UMP,
            consentAllowed = consentSnapshot.canRequestAds,
            providerState = when (platform) {
                AdPlatform.ADMOB -> AdMobAds.state.toCommonState()
                AdPlatform.TOPON -> TopOnAds.state.toCommonState()
            },
        )
    }

    internal fun resolveNativeRequest(request: NativeRequest, fullScreen: Boolean = false): ResolvedNativeRequest? =
        if (::config.isInitialized) config.provider.resolveNativeRequest(request, fullScreen) else null

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
        return AdEventDispatcher(application, platform, mode, observedEvents,
            config.loggingEnabled, config.logTag).beginLoad(AdFormat.NATIVE, adUnitId, 1)
    }

    internal fun newNativeSlot(request: ResolvedNativeRequest, onImpression: (() -> Unit)? = null): NativeSlot? {
        if (!::config.isInitialized || request.failureReason() != null) return null
        val platform = requireNotNull(request.candidates().first().platform)
        val mode = if (request.isBidding) AdMediationMode.BIDDING
            else if (platform == AdPlatform.ADMOB) AdMediationMode.ADMOB else AdMediationMode.TOPON
        val listener = AdEventListener { event ->
            // Observe the confirmed event after binding; NativeCardController rebinds SDK callbacks.
            if (event.name == AdEventName.IMPRESSION) onImpression?.invoke()
            observedEvents.onEvent(event)
        }
        return AdEventDispatcher(application, platform, mode, listener,
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

private data class InitializationFailure(val stage: InitializationStage, val reason: String, val retryable: Boolean)

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
