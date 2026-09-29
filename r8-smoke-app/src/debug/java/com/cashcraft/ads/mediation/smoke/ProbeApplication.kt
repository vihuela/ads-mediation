package com.cashcraft.ads.mediation.smoke

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cashcraft.ads.mediation.AdMobIds
import com.cashcraft.ads.mediation.AdMobProviderConfig
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdRevenueListener
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsConfig
import com.cashcraft.ads.mediation.UmpConsentConfig
import com.thinkup.core.api.TUNetworkConfig
import com.thinkup.core.api.TUDebuggerConfig
import com.thinkup.core.api.TUSDK
import com.thinkup.core.api.TUSDKInitListener

/** Device probe only. Full-screen R8 smoke remains in the release source set. */
class ProbeApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    val hasTopOnConfig: Boolean
        get() = BuildConfig.TOPON_APP_ID.isNotBlank() &&
            BuildConfig.TOPON_APP_KEY.isNotBlank() &&
            BuildConfig.TOPON_BANNER_PLACEMENT.isNotBlank()
    /** Set and cleared by the visible Activity on the main thread. */
    var onTopOnInit: ((Boolean) -> Unit)? = null
    var topOnReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        Ads.initialize(
            application = this,
            config = AdsConfig(
                provider = AdMobProviderConfig(AdMobIds.TEST),
                umpConsent = UmpConsentConfig(enabled = false),
                autoShowAppOpen = false,
                loggingEnabled = true,
                revenueListener = AdRevenueListener { payload ->
                    if (payload.format == AdFormat.BANNER) Log.i("BannerRevenue",
                        "session_id=${payload.sessionId} response_id=${payload.impressionId} micros=${payload.valueMicros}")
                },
            ),
        )
        if (!hasTopOnConfig) return
        if (BuildConfig.TOPON_TEST_DEVICE_GAID.isBlank()) {
            Log.w("BannerProbe", "topon disabled: explicit test-device GAID required")
            return
        }
        TUSDK.setNetworkLogDebug(true)
        TUSDK.setDebuggerConfig(
            this,
            BuildConfig.TOPON_TEST_DEVICE_GAID,
            // TopOn's public network table assigns AdMob network ID 2.
            TUDebuggerConfig.Builder(2).build(),
        )

        // The core's TopOn initializer preloads full-screen placements. This probe initializes TU
        // directly so a Banner test never requests a fake or unrelated full-screen placement.
        @Suppress("DEPRECATION")
        TUSDK.init(
            this,
            BuildConfig.TOPON_APP_ID,
            BuildConfig.TOPON_APP_KEY,
            TUNetworkConfig.Builder().withInitConfigList(emptyList()).build(),
            object : TUSDKInitListener {
                override fun onSuccess() {
                    mainHandler.post {
                        topOnReady = true
                        Log.i("BannerProbe", "topon init success")
                        onTopOnInit?.invoke(true)
                    }
                }

                override fun onFail(message: String) {
                    mainHandler.post {
                        Log.e("BannerProbe", "topon init failed: $message")
                        onTopOnInit?.invoke(false)
                    }
                }
            },
        )
    }
}
