package com.cashcraft.ads.mediation.smoke

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.InterstitialRewardPolicy

/** Keeps both legacy and mixed public show APIs reachable through R8 without requesting on launch. */
class SmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(host)
        fun action(label: String, show: () -> Unit) {
            host.addView(Button(this).apply {
                text = label
                setOnClickListener { show() }
            })
        }
        action("App open") { Ads.showAppOpen(this, host, "smoke") { title = it.toString() } }
        action("Interstitial") { Ads.showInterstitial(this, "smoke") { title = it.toString() } }
        action("Rewarded") { Ads.showRewarded(this, "smoke") { title = it.toString() } }
        action("App open or interstitial") {
            Ads.showAppOpenOrInterstitial(this, "smoke") { title = it.toString() }
        }
        action("Rewarded or interstitial") {
            Ads.showRewardedOrInterstitial(this, "smoke") { title = it.toString() }
        }
        action("Rewarded or interstitial with business reward") {
            Ads.showRewardedOrInterstitial(this, "smoke", InterstitialRewardPolicy.ON_DISMISSED) {
                title = it.toString()
            }
        }
    }
}
