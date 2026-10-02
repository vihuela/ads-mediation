package com.cashcraft.ads.mediation.smoke

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class NativeSmokeDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(TextView(context).apply {
                text = "Native smoke details\n\nUse Retry only after Failed. " +
                    "TopOn templateRatio must come from nativeTestConfig; a blank ratio must fail explicitly."
            })
            addView(Button(context).apply {
                text = "Return"
                setOnClickListener { finish() }
            })
        })
    }
}
