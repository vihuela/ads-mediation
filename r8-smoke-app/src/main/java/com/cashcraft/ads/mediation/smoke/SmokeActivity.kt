package com.cashcraft.ads.mediation.smoke

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import com.cashcraft.ads.mediation.*

/** Real public API demo, also used as the minified consumer for device regression. */
class SmokeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var nativeHandle: NativeAdHandle? = null
    private var nativeDialog: Dialog? = null
    private lateinit var nativeContainer: FrameLayout
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var scroll: ScrollView
    private val actions = linkedMapOf<String, () -> Unit>()
    private val refresh = object : Runnable {
        override fun run() {
            status.text = AdFormat.entries.joinToString(" | ") { "${it.analyticsValue}: ${if (Ads.isReady(it)) "ready" else "loading"}" } +
                "\n" + DemoAudit.summary()
            handler.postDelayed(this, 1_000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SmokeApplication
        val provider = app.providerName ?: intent.getStringExtra("provider")
        if (provider == null) {
            val chooser = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 120, 24, 24) }
            chooser.addView(TextView(this).apply { text = "Select mediation mode\nTo change mode, force-stop and reopen this demo."; textSize = 20f })
            listOf("admob", "topon", "bidding").forEach { mode ->
                chooser.addView(Button(this).apply { text = mode; setOnClickListener {
                    intent.putExtra("provider", mode)
                    recreate() // Initialize before the new Activity reaches onStart/onResume.
                } })
            }
            setContentView(chooser)
        } else startDemo(provider)
    }

    private fun startDemo(provider: String) {
        (application as SmokeApplication).initializeAds(provider, intent.getStringExtra("auto") ?: "off")
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 24); setBackgroundColor(0xfff3f4f6.toInt()) }
        scroll = ScrollView(this).apply { addView(host) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            applyBars(view, insets)
            insets
        }
        setContentView(scroll)
        host.addView(TextView(this).apply { text = "Ads demo · $provider · auto ${intent.getStringExtra("auto") ?: "off"}"; textSize = 20f; setTextColor(Color.BLACK) })
        status = TextView(this).apply { textSize = 11f; setTextColor(0xff374151.toInt()); minHeight = dp(110) }
        host.addView(status)
        result = TextView(this).apply { text = "Choose an action. Prices are USD per impression; null means unknown."; textSize = 12f }
        host.addView(result)
        var row: LinearLayout? = null
        fun action(key: String, label: String, block: () -> Unit) {
            actions[key] = { result.text = "$key: showing…"; block() }
            if (actions.size % 2 == 1) {
                row = LinearLayout(this)
                host.addView(row)
            }
            row!!.addView(Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false
                contentDescription = key
                setOnClickListener { actions.getValue(key).invoke() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        action("app_open", "App open") { Ads.showAppOpen(this, "demo_app_open") { report("app_open", it) } }
        action("interstitial", "Interstitial") { Ads.showInterstitial(this, "demo_interstitial") { report("interstitial", it) } }
        action("rewarded", "Rewarded") { Ads.showRewarded(this, "demo_rewarded") { report("rewarded", it) } }
        action("mixed_open", "App open / interstitial") { Ads.showAppOpenOrInterstitial(this, "demo_mixed_open") { report("mixed_open", it) } }
        action("mixed_reward", "Rewarded / interstitial") { Ads.showRewardedOrInterstitial(this, "demo_mixed_reward") { report("mixed_reward", it) } }
        action("mixed_reward_policy", "Mixed · reward on close") {
            Ads.showRewardedOrInterstitial(this, "demo_mixed_reward_policy", InterstitialRewardPolicy.ON_DISMISSED) { report("mixed_reward_policy", it) }
        }
        action("native_card", "Native · card") { showCard("native_card") }
        action("native_fullscreen", "Native · fullscreen") { showNativeDialog() }
        action("preload_native", "Preload / retry native") { Ads.preloadNative(); report("preload_native", "requested") }
        action("destroy_native", "Destroy native") { nativeHandle?.destroy(); nativeHandle = null; report("destroy_native", "destroyed") }
        action("native_destroy_early", "QA · destroy before exposure") { showCard("native_destroy_early", destroyEarly = true) }
        action("native_missing_renderer", "QA · missing renderer") { showCard("native_missing_renderer", renderers = NativeAdRenderers()) }
        action("native_render_error", "QA · renderer throws") {
            showCard("native_render_error", renderers = NativeAdRenderers(
                admob = object : AdMobNativeAdRenderer {
                    override fun createView(context: android.content.Context): com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView = error("demo_render_error")
                    override fun bind(view: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView, ad: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd) = null
                },
                topon = object : TopOnNativeAdRenderer {
                    override fun createView(context: android.content.Context): ViewGroup = error("demo_render_error")
                    override fun bind(view: ViewGroup, material: com.thinkup.nativead.api.TUNativeMaterial) = com.thinkup.nativead.api.TUNativePrepareInfo()
                },
            ))
        }
        action("finish", "Finish · verify cleanup") { finish() }
        nativeContainer = FrameLayout(this)
        host.addView(nativeContainer, LinearLayout.LayoutParams(-1, -2))
        refresh.run()
        executeIntent(intent)
    }

    private fun showCard(key: String, destroyEarly: Boolean = false, renderers: NativeAdRenderers = smokeNativeRenderers()) {
        nativeHandle?.destroy()
        nativeHandle = null
        Ads.showNative(this, nativeContainer, "demo_$key", renderers) {
            if (it is NativeAdShowResult.Rendered) {
                nativeHandle = it.handle
                if (destroyEarly) { it.handle.destroy(); nativeHandle = null }
                else scroll.post { scroll.smoothScrollTo(0, nativeContainer.bottom) }
            }
            report(key, it)
        }
    }

    private fun showNativeDialog() {
        nativeDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        nativeDialog = dialog
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        content.setOnApplyWindowInsetsListener { view, insets ->
            applyBars(view, insets)
            insets
        }
        val container = FrameLayout(this)
        var handle: NativeAdHandle? = null
        content.addView(Button(this).apply { text = "Close native"; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(content)
        dialog.setOnDismissListener { handle?.destroy(); if (nativeDialog === dialog) nativeDialog = null }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        Ads.showNative(this, container, "demo_native_fullscreen", smokeNativeRenderers(fullscreen = true)) {
            when (it) {
                is NativeAdShowResult.Rendered -> handle = it.handle
                is NativeAdShowResult.Failed -> dialog.dismiss()
            }
            report("native_fullscreen", it)
        }
    }

    // Repeatable device testing without coordinate-dependent taps. Only this demo exposes this intent.
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); executeIntent(intent) }
    private fun executeIntent(intent: Intent) {
        val key = intent.getStringExtra("action") ?: return
        intent.removeExtra("action")
        handler.postDelayed({ if (!isFinishing && !isDestroyed) actions[key]?.invoke() }, 500)
    }
    private fun report(action: String, value: Any) {
        val message = "$action: $value"
        runOnUiThread { if (!isDestroyed) result.text = message }
        Log.i("AdsDemoResult", message)
    }
    @Suppress("DEPRECATION")
    private fun applyBars(view: View, insets: WindowInsets) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nativeDialog?.dismiss()
        nativeHandle?.destroy()
        super.onDestroy()
    }
}
