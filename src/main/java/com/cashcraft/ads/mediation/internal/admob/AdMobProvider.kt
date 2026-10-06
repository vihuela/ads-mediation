@file:Suppress("DEPRECATION")

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
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdPreloader
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
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
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.BannerRequest
import com.cashcraft.ads.mediation.AdMobRevenuePayload
import com.cashcraft.ads.mediation.AdShowResult
import com.cashcraft.ads.mediation.adUnitId
import com.cashcraft.ads.mediation.bufferSize
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
import com.cashcraft.ads.mediation.AdSceneType
import com.cashcraft.ads.mediation.internal.AdPolicyAttempt
import com.cashcraft.ads.mediation.internal.AdPolicyRequest
import com.cashcraft.ads.mediation.internal.admob.analyticsLoadResult
import com.cashcraft.ads.mediation.internal.admob.AdMobConfig
import com.cashcraft.ads.mediation.internal.admob.AdMobEventName
import com.cashcraft.ads.mediation.internal.admob.AdMobFormat
import com.cashcraft.ads.mediation.internal.admob.AdMobRewardResult
import com.cashcraft.ads.mediation.internal.admob.AdMobShowResult
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
 * Retained only for existing integrations. New integrations should use
 * `com.cashcraft.ads.mediation.Ads` with `com.cashcraft.ads.mediation.AdsConfig`.
 * Do not mix this entry point with `Ads`; this legacy entry point does not provide the unified gates.
 *
 * The SDK preloaders own cache lifetime and replenishment. Every public show attempt emits one
 * `ad_position`, followed by exactly one terminal `ad_impression` or `ad_show_fail` event.
 */
@Deprecated(
    message = "Retained for existing integrations only. Use com.cashcraft.ads.mediation.Ads with " +
        "com.cashcraft.ads.mediation.AdsConfig for new integrations. Do not mix with Ads; " +
        "this legacy entry point does not provide the unified gates.",
    level = DeprecationLevel.WARNING,
)
object AdMobAds {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mobileAdsInitializationStarted = AtomicBoolean(false)
    private val initializationListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var removePolicyListener: (() -> Unit)? = null

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
    private val bannerPreloadDescriptors = mutableMapOf<Pair<String, String>, BannerPreloadDescriptor>()
    private val responseLoadBounds = mutableMapOf<String, Long>()
    private val pendingAds = mutableMapOf<AdMobFormat, RetainedAd<Ad>>()
    private val takenAds = mutableMapOf<Ad, RetainedAd<Ad>>()

    private var initializationAttempt = 0L
    internal var initializationError: Throwable? = null
        private set
    // MobileAds documents repeated initialize calls as supported; never reset the SDK itself.
    // https://developers.google.com/ad-manager/mobile-ads-sdk/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/MobileAds
    // Only direct I/O failures are classified as transient. Configuration/unknown failures stay terminal.
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
                isEnabled = {
                    Ads.isPlatformEnabled(AdPlatform.ADMOB) &&
                        this.config.autoShowAppOpen && isFormatEnabled(AdMobFormat.APP_OPEN)
                },
                isProviderReady = { state == AdMobState.READY },
                providerFailureReason = {
                    state.takeUnless { it == AdMobState.READY }?.showFailureReason()
                },
                isAdAvailable = { isReady(AdMobFormat.APP_OPEN) },
                beginOpportunity = {
                    events.begin(
                        AdMobFormat.APP_OPEN,
                        this.config.appOpenPosition,
                        this.config.ids.appOpenId,
                        attempt = FullScreenShowAttempt().apply {
                            policy = AdPolicyAttempt(AdPolicyRequest(AdMobAds.config.appOpenPosition,
                                fullscreen = true, sceneType = AdSceneType.OPEN))
                        },
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
            if (removePolicyListener == null) {
                removePolicyListener = Ads.addPolicyListener(::onPolicyChanged)
            }
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
                    InitializationConfig.Builder(config.ids.applicationId).build(),
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

    private val loadFailures = mutableMapOf<String, Long>()
    internal fun loadFailureVersion(format: AdMobFormat): Long =
        if (format == AdMobFormat.BANNER) 0L else loadFailures[format.preloadId()] ?: 0L

    private fun isFormatEnabled(format: AdMobFormat): Boolean =
        ::config.isInitialized && config.ids.isFormatEnabled(format, config.preload)

    fun isReady(format: AdMobFormat): Boolean {
        if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) return false
        if (format == AdMobFormat.BANNER || format == AdMobFormat.NATIVE || !isFormatEnabled(format)) return false
        if (state != AdMobState.READY) return false
        if (pendingAd(format) != null) return true
        return when (format) {
            AdMobFormat.BANNER -> false
            AdMobFormat.APP_OPEN -> AppOpenAdPreloader.isAdAvailable(PRELOAD_APP_OPEN)
            AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.isAdAvailable(PRELOAD_INTERSTITIAL)
            AdMobFormat.REWARDED -> RewardedAdPreloader.isAdAvailable(PRELOAD_REWARDED)
            AdMobFormat.NATIVE -> false
        }
    }

    internal fun preloadBanner(
        request: BannerRequest,
        size: AdSize,
        bufferSize: Int,
        autoRefill: Boolean = true,
    ) = onMain {
        if (state == AdMobState.FAILED || bufferSize <= 0) return@onMain
        val descriptor = BannerPreloadDescriptor(
            preloadId = bannerPreloadId(request, size),
            adUnitId = request.adUnitId,
            position = request.position,
            size = size,
            bufferSize = if (autoRefill) bufferSize else 1,
            autoRefill = autoRefill,
        )
        val placement = request.adUnitId to request.position
        val previous = bannerPreloadDescriptors[placement]
        if (previous == descriptor && (previous.autoRefill || !previous.settled ||
                previous.ad?.isUsable(SystemClock.elapsedRealtime()) == true)
        ) return@onMain
        bannerPreloadDescriptors[placement] = descriptor
        if (previous != null) {
            previous.ad?.ad?.destroy()
            previous.ad = null
            if (previous.autoRefill && previous.started) BannerAdPreloader.destroy(previous.preloadId)
        }
        if (state == AdMobState.READY) startBannerPreloading(descriptor)
    }

    internal fun pollBanner(request: BannerRequest, size: AdSize): BannerAd? {
        if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) return null
        if (state != AdMobState.READY || request.platform != AdPlatform.ADMOB || config.preload.banner == 0) {
            return null
        }
        val placement = request.adUnitId to request.position
        val descriptor = bannerPreloadDescriptors[placement] ?: return null
        if (descriptor.preloadId != bannerPreloadId(request, size)) return null
        if (descriptor.autoRefill) return BannerAdPreloader.pollAd(descriptor.preloadId)
        val retained = descriptor.ad ?: return null // An empty poll leaves an in-flight load intact.
        descriptor.ad = null // Ownership transfers to the View only while the ad is still usable.
        if (retained.isUsable(SystemClock.elapsedRealtime())) return retained.ad
        retained.ad.destroy()
        return null
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
        if (state != AdMobState.INITIALIZING) return
        state = if (success) AdMobState.READY else AdMobState.FAILED
        if (success) startPreloading()
        val listeners = initializationListeners.toList()
        initializationListeners.clear()
        listeners.forEach { listener -> runCatching { listener(success) } }
        if (success) autoAppOpenController.onProviderInitialized()
    }

    private fun startPreloading() {
        preloadDescriptors.clear()
        AdMobFormat.entries.filter {
            it != AdMobFormat.BANNER && it != AdMobFormat.NATIVE && isFormatEnabled(it)
        }.forEach { format ->
            preloadDescriptors[format.preloadId()] = PreloadDescriptor(
                format, config.ids.adUnitId(format), config.preload.bufferSize(format),
            )
        }
        onPolicyChanged()
    }

    /** Stops SDK-owned automatic retries/refills, then starts fresh pools when loading is allowed. */
    internal fun onPolicyChanged() = onMain {
        if (state != AdMobState.READY) return@onMain
        if (Ads.canLoadAds(AdPlatform.ADMOB)) {
            preloadDescriptors.forEach { (preloadId, descriptor) ->
                startFullScreenPreloading(preloadId, descriptor)
            }
            bannerPreloadDescriptors.values.toList().forEach(::startBannerPreloading)
        } else {
            preloadDescriptors.forEach { (preloadId, descriptor) ->
                if (descriptor.started) {
                    descriptor.started = false
                    descriptor.generation++
                    when (descriptor.format) {
                        AdMobFormat.APP_OPEN -> AppOpenAdPreloader.destroy(preloadId)
                        AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.destroy(preloadId)
                        AdMobFormat.REWARDED -> RewardedAdPreloader.destroy(preloadId)
                        AdMobFormat.BANNER, AdMobFormat.NATIVE -> Unit
                    }
                    preloadLoadSessions.remove(preloadId)?.failed("cancelled", null, null, null)
                    preloadStartedAt.remove(preloadId)
                }
            }
            bannerPreloadDescriptors.values.toList().forEach { descriptor ->
                if (descriptor.started) {
                    descriptor.started = false
                    descriptor.settled = false
                    descriptor.generation++
                    if (descriptor.autoRefill) BannerAdPreloader.destroy(descriptor.preloadId)
                    descriptor.ad?.ad?.destroy()
                    descriptor.ad = null
                }
            }
        }
    }

    private fun startFullScreenPreloading(preloadId: String, descriptor: PreloadDescriptor) {
        if (descriptor.started || !Ads.canLoadAds(AdPlatform.ADMOB)) return
        descriptor.started = true
        val generation = ++descriptor.generation
        val preloadCallback = object : PreloadCallback {
            override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
                mainHandler.post {
                    if (!descriptor.started || descriptor.generation != generation) return@post
                    if (!Ads.canLoadAds(AdPlatform.ADMOB)) {
                        onPolicyChanged()
                        return@post
                    }
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
                    FullScreenLoadSignals.changed()
                    if (preloadId == PRELOAD_APP_OPEN) {
                        autoAppOpenController.onAdAvailable()
                    }
                }
            }

            override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
                mainHandler.post {
                    if (!descriptor.started || descriptor.generation != generation) return@post
                    if (!Ads.canLoadAds(AdPlatform.ADMOB)) {
                        onPolicyChanged()
                        return@post
                    }
                    loadFailures[preloadId] = (loadFailures[preloadId] ?: 0L) + 1
                    preloadLoadSessions[preloadId]?.failed(
                        result = adError.analyticsLoadResult(),
                        errorCode = adError.code.name,
                        reason = adError.message,
                        responseId = adError.responseInfo?.responseId,
                    )
                    FullScreenLoadSignals.changed()
                }
            }

            override fun onAdsExhausted(preloadId: String) {
                mainHandler.post {
                    if (!descriptor.started || descriptor.generation != generation) return@post
                    // Exhaustion is a cache notification, not an observable SDK load start.
                    if (!Ads.canLoadAds(AdPlatform.ADMOB)) onPolicyChanged()
                }
            }
        }
        val loadSession = events.createLoad(descriptor.format, descriptor.adUnitId, descriptor.bufferSize)
        preloadLoadSessions[preloadId] = loadSession
        if (!descriptor.started || descriptor.generation != generation || !Ads.canLoadAds(AdPlatform.ADMOB)) {
            onPolicyChanged()
            return
        }
        preloadStartedAt[preloadId] = SystemClock.elapsedRealtime()
        val configuration = preloadConfiguration(descriptor.adUnitId, descriptor.bufferSize)
        try {
            loadSession.invokeLoad {
                when (descriptor.format) {
                    AdMobFormat.APP_OPEN -> AppOpenAdPreloader.start(preloadId, configuration, preloadCallback)
                    AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.start(preloadId, configuration, preloadCallback)
                    AdMobFormat.REWARDED -> RewardedAdPreloader.start(preloadId, configuration, preloadCallback)
                    AdMobFormat.BANNER, AdMobFormat.NATIVE -> Unit
                }
            }
        } catch (error: Throwable) {
            loadSession.failed("error", "exception", error.message, null)
            throw error // Preserve the SDK start failure behavior.
        }
    }

    private fun startBannerPreloading(descriptor: BannerPreloadDescriptor) {
        if (descriptor.started || !Ads.canLoadAds(AdPlatform.ADMOB)) return
        descriptor.started = true
        val generation = ++descriptor.generation
        val request = BannerAdRequest.Builder(descriptor.adUnitId, descriptor.size).build()
        if (descriptor.autoRefill) {
            BannerAdPreloader.start(descriptor.preloadId, PreloadConfiguration(request, descriptor.bufferSize))
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val loadSession = events.createBannerPreloadLoad(descriptor.adUnitId)
        // ponytail: one ad per placement; use a native no-refill switch if the SDK adds one.
        val callback = object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) = onMain {
                val responseInfo: ResponseInfo? = ad.getResponseInfo()
                // Loading completed even when this generation can no longer retain the ad.
                loadSession.loaded(responseInfo?.loadedAdSourceResponseInfo?.name, responseInfo?.responseId)
                if (bannerPreloadDescriptors[descriptor.adUnitId to descriptor.position] !== descriptor ||
                    descriptor.settled || descriptor.generation != generation ||
                    !Ads.canLoadAds(AdPlatform.ADMOB)
                ) {
                    ad.destroy()
                    return@onMain
                }
                descriptor.settled = true
                val retained = RetainedAd(ad, startedAt, BANNER_MAX_AGE_MILLIS)
                if (retained.isUsable(SystemClock.elapsedRealtime())) descriptor.ad = retained
                else ad.destroy()
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = onMain {
                val result = adError.analyticsLoadResult()
                loadSession.failed(
                    result, adError.code.name, if (result == "error") "ad_error" else result,
                    adError.responseInfo?.responseId,
                )
                if (descriptor.generation != generation) return@onMain
                descriptor.settled = true // No retry or replenishment, including after a failure.
            }
        }
        try {
            loadSession.invokeLoad { BannerAd.load(request, callback) }
        } catch (error: Throwable) {
            loadSession.failed("error", "load_exception", "exception", null)
            throw error // Preserve the SDK load failure behavior.
        }
    }

    private fun bannerPreloadId(request: BannerRequest, size: AdSize) =
        "cashcraft_banner:${request.adUnitId}:${request.position}:${size.width}x${size.height}"

    private fun preloadConfiguration(adUnitId: String, bufferSize: Int) = PreloadConfiguration(
        AdRequest.Builder(adUnitId).build(),
        bufferSize,
    )

    internal fun bidPrice(format: AdMobFormat): Double? {
        if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) return null
        if (format == AdMobFormat.BANNER || format == AdMobFormat.NATIVE || !isFormatEnabled(format)) return null
        pendingAd(format)?.let { return it.priceUsd }
        if (!isReady(format)) return null
        return AdMobNextGenBidPrice.peek(format, format.preloadId())
    }

    internal fun bidAdSource(format: AdMobFormat): String? = runCatching {
        if (!isReady(format)) return@runCatching null
        val info = pendingAd(format)?.ad?.getResponseInfo() ?: when (format) {
            AdMobFormat.APP_OPEN -> AppOpenAdPreloader.peekAdResponseInfo(format.preloadId())
            AdMobFormat.INTERSTITIAL -> InterstitialAdPreloader.peekAdResponseInfo(format.preloadId())
            AdMobFormat.REWARDED -> RewardedAdPreloader.peekAdResponseInfo(format.preloadId())
            else -> null
        }
        info?.loadedAdSourceResponseInfo?.name
    }.getOrNull()

    private fun pendingAd(format: AdMobFormat): RetainedAd<Ad>? = synchronized(pendingAds) {
        val pending = pendingAds[format] ?: return@synchronized null
        if (pending.isUsable(SystemClock.elapsedRealtime())) return@synchronized pending
        pendingAds.remove(format)
        onMain { pending.ad.destroy() }
        null
    }

    private fun takeAd(format: AdMobFormat): Ad? {
        if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) return null
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
            AdMobFormat.NATIVE -> error("unsupported_ad_format")
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
        AdMobFormat.NATIVE -> error("unsupported_ad_format")
    }

    private fun showAppOpenOnMain(
        activity: Activity,
        position: String,
        onResult: (AdMobShowResult) -> Unit,
        session: AdShowSession = events.begin(AdMobFormat.APP_OPEN, position, config.ids.appOpenId,
            FullScreenShowAttempt().apply { policy = AdPolicyAttempt(AdPolicyRequest(position,
                fullscreen = true, userInitiated = false, sceneType = AdSceneType.OPEN)) }),
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
        session: AdShowSession = events.begin(AdMobFormat.INTERSTITIAL, position, config.ids.interstitialId,
            FullScreenShowAttempt().apply { policy = AdPolicyAttempt(AdPolicyRequest(position,
                fullscreen = true, userInitiated = false, sceneType = AdSceneType.INTER)) }),
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
        session: AdShowSession = events.begin(AdMobFormat.REWARDED, position, config.ids.rewardedId,
            FullScreenShowAttempt().apply { policy = AdPolicyAttempt(AdPolicyRequest(position,
                fullscreen = true, userInitiated = true, sceneType = AdSceneType.REWARDED)) }),
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
                        AdMobEventName.REWARD,
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
        if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) {
            failBeforeShow(session, "ad_platform_disabled", onResult)
            return false
        }
        if (!isFormatEnabled(session.format)) {
            failBeforeShow(session, "ad_format_disabled", onResult)
            return false
        }
        val reason = FullScreenShowGate.tryAcquire(
            activity = activity,
            providerFailureReason = state.takeUnless { it == AdMobState.READY }?.showFailureReason(),
            attempt = session.attempt,
        )
        if (reason != null) {
            failBeforeShow(session, reason, onResult)
            return false
        }
        session.admit()
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
                if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) {
                    "ad_platform_disabled"
                } else if (taken?.wasRetained == true && !taken.isUsable(SystemClock.elapsedRealtime())) {
                    NO_AD_AVAILABLE
                } else null
            },
        )
        if (reason != null) {
            session.attempt.invalidate(reason)
            failBeforeShow(session, reason, onResult)
            return
        }
        // Reservation/onCommitted can invoke host code. The reserved opportunity is allowed
        // when canLoadAds becomes false, but a platform toggle still prevents the SDK handoff.
        if (!Ads.isPlatformEnabled(AdPlatform.ADMOB)) {
            takenAds.remove(ad)
            ad.destroy()
            failBeforeShow(session, "ad_platform_disabled", onResult)
            return
        }
        takenAds.remove(ad)
        runCatching(show).onFailure { error ->
            if (!session.attempt.complete()) return@onFailure
            ad.destroy()
            val failure = error.message ?: "show_exception"
            session.showFailure("exception", cause = error)
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
        session.showFailure("ad_error", error.code.toString())
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
        if (!revenue(
            adSource = adNetwork,
            responseId = impressionId,
            value = valueMicros / MICROS_PER_UNIT,
            valueMicros = valueMicros,
            currency = currencyCode,
            mediationAdapterClassName = adapterClassName,
            precisionType = precisionType,
        )) return
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


    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private const val PRELOAD_APP_OPEN = "lcb_admob_app_open"
    private const val PRELOAD_INTERSTITIAL = "lcb_admob_interstitial"
    private const val PRELOAD_REWARDED = "lcb_admob_rewarded"
    private const val NO_AD_AVAILABLE = "no_preloaded_ad"
    private const val MICROS_PER_UNIT = 1_000_000.0
    private const val APP_OPEN_MAX_AGE_MILLIS = 4 * 60 * 60 * 1_000L
    private const val BANNER_MAX_AGE_MILLIS = 60 * 60 * 1_000L

    private data class PreloadDescriptor(
        val format: AdMobFormat,
        val adUnitId: String,
        val bufferSize: Int,
    ) {
        var started = false
        var generation = 0L
    }

    private data class BannerPreloadDescriptor(
        val preloadId: String,
        val adUnitId: String,
        val position: String,
        val size: AdSize,
        val bufferSize: Int,
        val autoRefill: Boolean,
    ) {
        var started = false
        var generation = 0L
        var settled = false
        var ad: RetainedAd<BannerAd>? = null
    }
}

/** Keeps failed show opportunities distinguishable from requests made while SDK startup is pending. */
internal fun AdMobState.showFailureReason(): String = when (this) {
    AdMobState.NOT_INITIALIZED -> "sdk_not_initialized"
    AdMobState.INITIALIZING -> "sdk_initializing"
    AdMobState.FAILED -> "sdk_initialization_failed"
    AdMobState.READY -> "sdk_not_ready"
}
