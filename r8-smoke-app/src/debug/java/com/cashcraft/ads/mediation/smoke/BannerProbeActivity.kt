package com.cashcraft.ads.mediation.smoke

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsState
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.thinkup.banner.api.TUBannerListener
import com.thinkup.banner.api.TUBannerView
import com.thinkup.core.api.AdError
import com.thinkup.core.api.TUAdConst
import com.thinkup.core.api.TUAdInfo
import com.thinkup.network.admob.AdmobTUConst

/** Manual SDK capability probe. Logs raw callback identity, not normalized ad events. */
class BannerProbeActivity : Activity() {
    private lateinit var admobHost: FrameLayout
    private lateinit var topOnHost: FrameLayout
    private lateinit var logView: TextView
    private var admobView: AdView? = null
    private var admobLoaded = false
    private var topOnView: TUBannerView? = null
    private var admobGeneration = 0
    private var topOnGeneration = 0
    private var admobRequests = 0
    private var topOnRequests = 0
    private var destroyed = false
    private var resumed = false
    private var childrenDetached = false
    private var topOnInitListener: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)

        fun button(label: String, action: () -> Unit): Button = Button(this).also {
            it.text = label
            it.setOnClickListener { action() }
            column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        fun host(label: String): FrameLayout {
            column.addView(TextView(this).apply { text = label })
            return FrameLayout(this).also {
                it.minimumHeight = dp(50)
                column.addView(it, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                var previous = ""
                it.viewTreeObserver.addOnGlobalLayoutListener {
                    val size = "${it.width}x${it.height}px child=${it.getChildAt(0)?.width ?: 0}x${it.getChildAt(0)?.height ?: 0}px"
                    if (size != previous) {
                        previous = size
                        event(label, "content_size", size)
                    }
                }
            }
        }

        button("AdMob load") { admobHost.post { loadAdMob() } }
        button("AdMob visibility") { toggle(admobHost, "admob") }
        button("AdMob destroy") { destroyAdMob() }
        admobHost = host("admob")

        val app = application as ProbeApplication
        val topOnSizeLabel = if (intent.getBooleanExtra("topon_adaptive", false)) "adaptive" else "request 320x50 dp"
        val topOnButton = button("TopOn load ($topOnSizeLabel)") { topOnHost.post { loadTopOn() } }
        topOnButton.isEnabled = app.topOnReady
        topOnInitListener = { ready ->
            topOnButton.isEnabled = ready
            event("topon", "init", "ready=$ready")
        }
        app.onTopOnInit = topOnInitListener
        button("TopOn visibility") { toggle(topOnHost, "topon") }
        button("TopOn destroy") { destroyTopOn() }
        topOnHost = host("topon")
        button("Dialog (focus pause)") {
            AlertDialog.Builder(this).setMessage("Banner children must be hidden while this dialog has focus")
                .setPositiveButton("Close", null).show()
        }
        button("Detach / reattach children") {
            childrenDetached = !childrenDetached
            listOf(admobView to admobHost, topOnView to topOnHost).forEach { (view, host) ->
                if (view != null) {
                    if (childrenDetached) {
                        host.minimumHeight = maxOf(host.minimumHeight, view.height)
                        host.removeView(view)
                    }
                    else if (view.parent == null) host.addView(view)
                }
            }
            event("probe", "attachment", "detached=$childrenDetached")
            updateChildVisibility()
        }

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        column.addView(logView)
        check(!Ads.isReady(AdFormat.BANNER)) { "public full-screen readiness accepted BANNER" }
        event("probe", "full_screen_isReady", "BANNER=false")
        event("probe", "start", "TopOn configured=${app.hasTopOnConfig}; TopOn enabled=${app.topOnReady}")
    }

    private fun loadAdMob() {
        if (Ads.state != AdsState.READY) { event("admob", "skip", "Ads.state=${Ads.state}"); return }
        val widthPx = admobHost.width - admobHost.paddingLeft - admobHost.paddingRight
        if (widthPx <= 0) { event("admob", "skip", "no content width"); return }
        destroyAdMob()
        val generation = admobGeneration
        val size = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
            this, (widthPx / resources.displayMetrics.density).toInt(),
        )
        admobHost.minimumHeight = size.getHeightInPixels(this)
        val view = AdView(this)
        admobView = view // retain from creation, including a failed or in-flight load
        try {
            val attachBeforeLoad = intent.getBooleanExtra("attach_before_load", false)
            if (attachBeforeLoad) {
                view.visibility = View.INVISIBLE
                admobHost.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            val request = BannerAdRequest.Builder(ADMOB_TEST_BANNER, size).build()
            admobRequests++
            event("admob", "load_call", "request=$admobRequests width=${widthPx}px size=${size.width}x${size.height}dp attachBeforeLoad=$attachBeforeLoad generation=$generation")
            view.loadAd(request, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    val id = ad.getResponseInfo().responseId
                    event("admob", "loaded", "responseId=$id generation=$generation stale=${generation != admobGeneration}")
                    runOnUiThread {
                        if (destroyed || generation != admobGeneration || admobView !== view) {
                            ad.destroy()
                            return@runOnUiThread
                        }
                        ad.adEventCallback = object : BannerAdEventCallback {
                            override fun onAdImpression() = event("admob", "impression", "responseId=${ad.getResponseInfo().responseId}")
                            override fun onAdClicked() = event("admob", "click", "responseId=${ad.getResponseInfo().responseId}")
                            override fun onAdPaid(value: AdValue) = event(
                                "admob", "paid",
                                "responseId=${ad.getResponseInfo().responseId} micros=${value.valueMicros} currency=${value.currencyCode} precision=${value.precisionType}",
                            )
                            override fun onAdShowedFullScreenContent() = event("admob", "overlay_open", "responseId=${ad.getResponseInfo().responseId}")
                            override fun onAdDismissedFullScreenContent() = event("admob", "overlay_close", "responseId=${ad.getResponseInfo().responseId}")
                        }
                        ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                            override fun onAdRefreshed() = event("admob", "refreshed", "responseId=${ad.getResponseInfo().responseId}")
                            override fun onAdFailedToRefresh(adError: LoadAdError) = event(
                                "admob", "refresh_failed", "responseId=${adError.responseInfo?.responseId} error=$adError",
                            )
                        }
                        if (destroyed || generation != admobGeneration || admobView !== view) {
                            ad.destroy()
                            return@runOnUiThread
                        }
                        admobLoaded = true
                        updateChildVisibility()
                        if (!childrenDetached && view.parent == null) {
                            admobHost.addView(view, FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            ))
                            event("admob", "attached", "responseId=$id")
                        } else event("admob", "duplicate_loaded", "responseId=$id")
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = event(
                    "admob", "load_failed", "responseId=${adError.responseInfo?.responseId} error=$adError generation=$generation",
                )
            })
        } catch (error: Exception) {
            event("admob", "setup_failed", error.toString())
            destroyAdMob()
        }
    }

    private fun loadTopOn() {
        val app = application as ProbeApplication
        if (!app.hasTopOnConfig || !app.topOnReady) { event("topon", "skip", "no ready test config"); return }
        val adaptive = intent.getBooleanExtra("topon_adaptive", false)
        val availableWidth = topOnHost.width - topOnHost.paddingLeft - topOnHost.paddingRight
        val width = if (adaptive) availableWidth else dp(320)
        if (width <= 0 || availableWidth < width) {
            event("topon", "skip", "content narrower than 320dp")
            return
        }
        destroyTopOn()
        topOnHost.minimumHeight = dp(50)
        val generation = topOnGeneration
        val view = TUBannerView(this)
        topOnView = view // retain before any configuration can fail
        try {
            view.setPlacementId(BuildConfig.TOPON_BANNER_PLACEMENT)
            val extras = mutableMapOf<String, Any>(
                TUAdConst.KEY.AD_WIDTH to width,
                TUAdConst.KEY.AD_HEIGHT to dp(50),
            )
            if (adaptive) {
                extras[AdmobTUConst.ADAPTIVE_TYPE] = AdmobTUConst.ADAPTIVE_ANCHORED
                extras[AdmobTUConst.ADAPTIVE_ORIENTATION] = AdmobTUConst.ORIENTATION_CURRENT
                extras[AdmobTUConst.ADAPTIVE_WIDTH] = width
            }
            view.setLocalExtra(extras)
            view.setAdRevenueListener { info ->
                event("topon", "paid", "${identity(info)} revenueUSD=${info.getPublisherRevenue(TUAdConst.CURRENCY.USD)} generation=$generation stale=${topOnView !== view}")
            }
            view.setBannerAdListener(object : TUBannerListener {
                override fun onBannerLoaded() = event("topon", "loaded", "generation=$generation stale=${topOnView !== view}")
                override fun onBannerFailed(error: AdError) = event("topon", "load_failed", "error=${error.fullErrorInfo} generation=$generation stale=${topOnView !== view}")
                override fun onBannerShow(info: TUAdInfo) = event("topon", "show", "${identity(info)} generation=$generation stale=${topOnView !== view}")
                override fun onBannerClicked(info: TUAdInfo) = event("topon", "click", "${identity(info)} generation=$generation stale=${topOnView !== view}")
                override fun onBannerClose(info: TUAdInfo) = event("topon", "close", "${identity(info)} generation=$generation stale=${topOnView !== view}")
                override fun onBannerAutoRefreshed(info: TUAdInfo) = event("topon", "refreshed", "${identity(info)} generation=$generation stale=${topOnView !== view}")
                override fun onBannerAutoRefreshFail(error: AdError) = event("topon", "refresh_failed", "error=${error.fullErrorInfo} generation=$generation stale=${topOnView !== view}")
            })
            topOnHost.addView(view, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
            topOnRequests++
            updateChildVisibility()
            event("topon", "load_call", "request=$topOnRequests size=${width}x${dp(50)}px adaptive=$adaptive generation=$generation")
            view.loadAd()
        } catch (error: Exception) {
            event("topon", "setup_failed", error.toString())
            destroyTopOn()
        }
    }

    private fun identity(info: TUAdInfo): String = "showId=${info.showId} network=${info.networkName}"

    private fun toggle(host: FrameLayout, platform: String) {
        host.visibility = if (host.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        event(platform, "visibility", "visible=${host.visibility == View.VISIBLE}")
    }

    private fun destroyAdMob() {
        admobGeneration++
        admobLoaded = false
        admobView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            event("admob", "destroy", "generation=$admobGeneration")
        }
        admobView = null
    }

    private fun destroyTopOn() {
        topOnGeneration++
        topOnView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            event("topon", "destroy", "generation=$topOnGeneration")
        }
        topOnView = null
    }

    private fun event(platform: String, name: String, detail: String) {
        val line = "${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} $platform $name $detail"
        Log.i("BannerProbe", line)
        if (!destroyed) runOnUiThread {
            if (!destroyed && ::logView.isInitialized) logView.append("$line\n")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        updateChildVisibility()
    }

    override fun onPause() {
        resumed = false
        updateChildVisibility()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateChildVisibility()
    }

    /** Candidate retain path under test, not the library's final pause policy. */
    private fun updateChildVisibility() {
        val visible = resumed && hasWindowFocus() && !childrenDetached
        admobView?.visibility = if (visible && admobLoaded) View.VISIBLE else View.INVISIBLE
        topOnView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        event("probe", "eligibility", "resumed=$resumed focus=${hasWindowFocus()} detached=$childrenDetached visible=$visible")
    }

    override fun onDestroy() {
        destroyed = true
        val app = application as ProbeApplication
        if (app.onTopOnInit === topOnInitListener) app.onTopOnInit = null
        destroyAdMob()
        destroyTopOn()
        super.onDestroy()
    }

    private companion object {
        const val ADMOB_TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
    }
}
