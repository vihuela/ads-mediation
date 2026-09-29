package com.cashcraft.ads.mediation.smoke

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity

/** Real attached/resumed/focused window for the device contract tests. */
class BannerContractActivity : FragmentActivity() {
    lateinit var host: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}
