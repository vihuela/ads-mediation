package com.cashcraft.ads.mediation.smoke

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.BannerRequest
import com.cashcraft.ads.mediation.BannerSize
import com.cashcraft.ads.mediation.BannerState
import com.cashcraft.ads.mediation.compose.AdsBanner

/** Release-reachable host for navigation, ownership, IME, and R8 smoke checks. */
class SmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialActive = intent.getBooleanExtra("banner_active", true)
        val initialHidden = !intent.getBooleanExtra("banner_visible", true)
        val sharedFooter = intent.getBooleanExtra("shared_footer", false)
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    NavHost(
                        navController = nav,
                        startDestination = "home",
                        modifier = Modifier.weight(1f),
                    ) {
                        composable("home") { entry ->
                            SmokePage("Home", entry, nav, initialActive, initialHidden, !sharedFooter,
                                showFullscreen = { Ads.showInterstitial(this@SmokeActivity, "banner_smoke") }) {
                                openTraditional()
                            }
                        }
                        composable("detail") { entry ->
                            SmokePage("Detail", entry, nav, initialActive, initialHidden, !sharedFooter,
                                showFullscreen = { Ads.showInterstitial(this@SmokeActivity, "banner_smoke") }) {
                                openTraditional()
                            }
                        }
                    }
                    if (sharedFooter) {
                        // This shell explicitly owns one common slot across both allowed destinations.
                        AdsBanner(
                            BannerRequest(AdPlatform.ADMOB, TEST_BANNER_ID, "smoke_shared_footer", BannerSize.AnchoredAdaptive),
                            lifecycleOwner = this@SmokeActivity,
                            active = initialActive,
                            visible = !initialHidden,
                        )
                    }
                }
            }
        }
    }

    private fun openTraditional() = startActivity(
        Intent(this, TraditionalBannerActivity::class.java)
            .putExtra("standard_banner", intent.getBooleanExtra("standard_banner", false)),
    )
}

private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/9214589741"

@Composable
private fun SmokePage(
    label: String,
    owner: LifecycleOwner,
    nav: NavHostController,
    initialActive: Boolean,
    initialHidden: Boolean,
    pageBanner: Boolean,
    showFullscreen: () -> Unit,
    openFragment: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(initialHidden && pageBanner) }
    var active by remember { mutableStateOf(initialActive) }
    var useTopOn by remember { mutableStateOf(false) }
    var bannerState by remember { mutableStateOf<BannerState>(BannerState.Inactive) }
    val request = BannerRequest(
        if (useTopOn) AdPlatform.TOPON else AdPlatform.ADMOB,
        if (useTopOn) "unsupported-smoke" else TEST_BANNER_ID,
        "smoke_${label.lowercase()}",
        BannerSize.AnchoredAdaptive,
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$label · tab $tab · ${if (pageBanner) bannerState else "Shared footer"}")
                Button(onClick = { nav.navigate("detail") }) { Text("Push detail, including same route") }
                Button(onClick = { nav.navigate("home") }) { Text("Push another home entry") }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
                Button(onClick = { tab++ }) { Text("Change tab / recompose") }
                Button(onClick = { dialog = true }) { Text("Dialog / lost focus") }
                Button(onClick = showFullscreen) { Text("Show test interstitial") }
                if (pageBanner) {
                    Button(onClick = { overlay = true }) { Text("Same-window overlay") }
                    Button(onClick = { active = !active }) { Text("Active: $active") }
                    Button(onClick = { useTopOn = !useTopOn }) { Text("Platform: ${if (useTopOn) "TopOn unsupported" else "AdMob"}") }
                }
                Button(onClick = openFragment) { Text("Traditional View / Fragment") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("IME and ordinary recomposition") },
                )
            }
            if (pageBanner) AdsBanner(
                request = request,
                modifier = Modifier.fillMaxWidth(),
                lifecycleOwner = owner,
                active = active,
                visible = !overlay,
                onState = { bannerState = it },
            )
        }
        if (overlay) {
            Box(Modifier.matchParentSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column {
                    Button(onClick = { overlay = false }) { Text("Close overlay") }
                    Button(onClick = { active = false; overlay = false }) { Text("Reveal while deactivating") }
                }
            }
        }
    }
    if (dialog) {
        Dialog(onDismissRequest = { dialog = false }) {
            Button(onClick = { dialog = false }) { Text("Close dialog") }
        }
    }
}
