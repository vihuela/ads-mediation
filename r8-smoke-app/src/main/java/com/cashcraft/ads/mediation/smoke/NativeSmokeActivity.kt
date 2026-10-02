package com.cashcraft.ads.mediation.smoke

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as ComposeText
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.FloatingWindow
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import com.cashcraft.ads.mediation.AdsNativeView
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.NativeLayout
import com.cashcraft.ads.mediation.NativeLayoutBinding
import com.cashcraft.ads.mediation.NativeMediaType
import com.cashcraft.ads.mediation.NativeRetentionPolicy
import com.cashcraft.ads.mediation.NativeRequest
import com.cashcraft.ads.mediation.NativeState
import com.cashcraft.ads.mediation.compose.AdsNative
import java.util.Locale
import kotlinx.serialization.Serializable

private const val TAG = "NativeSmoke"

class NativeSmokeActivity : ComponentActivity() {
    private val cards = mutableListOf<AdsNativeView>()
    private var viewUseCustomLayout = false
    private var viewActive = true
    private var viewVisible = true
    private var viewCallbackGeneration = 0
    private lateinit var viewCardContainer: LinearLayout
    private lateinit var viewStatus: TextView
    private lateinit var viewVisibleButton: Button
    private lateinit var viewActiveButton: Button
    private lateinit var viewLayoutButton: Button
    private lateinit var viewCallbackButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.NATIVE_SMOKE) {
            showDefaultSmokeNotice()
            return
        }

        val options = runCatching { NativeSmokeOptions.from(intent) }
            .getOrElse { error ->
                showError(error.message ?: "invalid native smoke options")
                return
            }
        if (options.platform == AdPlatform.TOPON && (
                BuildConfig.NATIVE_TEST_TOPON_APP_ID.isBlank() ||
                    BuildConfig.NATIVE_TEST_TOPON_APP_KEY.isBlank() ||
                    BuildConfig.NATIVE_TEST_NATIVE_PLACEMENT.isBlank()
            )
        ) {
            showError("TopOn requires nativeTestConfig with applicationId, applicationKey, and nativePlacement")
            return
        }
        if (options.mode == SmokeMode.NAV) {
            setContent { NativeSmokeNavigation(options) }
        } else if (options.mode == SmokeMode.COMPOSE) {
            setContent {
                NativeSmokeComposeScreen(
                    options = options,
                    lifecycleOwner = this@NativeSmokeActivity,
                    onDetails = ::openDetails,
                )
            }
        } else {
            viewUseCustomLayout = options.customLayout
            viewActive = options.initialActive
            viewVisible = options.initialVisible
            showViewSmoke(options)
        }
    }

    override fun onDestroy() {
        cards.toList().forEach(AdsNativeView::destroy)
        cards.clear()
        super.onDestroy()
    }

    private fun showDefaultSmokeNotice() {
        val message = "Default Bidding R8 smoke is active. Build with -PnativeSmoke=true for Native UI smoke."
        setContentView(TextView(this).apply { text = message; setPadding(32, 48, 32, 32) })
    }

    private fun showError(message: String) {
        setContentView(TextView(this).apply { text = message; setPadding(32, 48, 32, 32) })
        Log.e(TAG, message)
    }

    private fun showViewSmoke(options: NativeSmokeOptions) {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 24)
        }
        root.addView(TextView(this).apply { text = options.summary() })

        viewStatus = TextView(this).apply { text = "state=Idle" }
        root.addView(viewStatus)
        root.addView(controlRow(options))
        viewCardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(viewCardContainer, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(root)
        setContentView(scroll)
        rebuildViewCards(options)
    }

    private fun controlRow(options: NativeSmokeOptions): LinearLayout {
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstRow = LinearLayout(this)
        firstRow.addView(button("Retry") { cards.forEach(AdsNativeView::retry) })
        viewVisibleButton = button("Visible: $viewVisible") {
            viewVisible = !viewVisible
            viewVisibleButton.text = "Visible: $viewVisible"
            cards.forEach { it.setVisible(viewVisible) }
        }
        firstRow.addView(viewVisibleButton)
        viewActiveButton = button("Active: $viewActive") {
            viewActive = !viewActive
            viewActiveButton.text = "Active: $viewActive"
            cards.forEach { it.setActive(viewActive) }
        }
        firstRow.addView(viewActiveButton)
        controls.addView(firstRow)

        val secondRow = LinearLayout(this)
        viewCallbackButton = button("Callback: 0") {
            viewCallbackGeneration++
            viewCallbackButton.text = "Callback: $viewCallbackGeneration"
            cards.forEachIndexed { index, card -> card.setOnStateChanged(stateCallback(index)) }
        }
        secondRow.addView(viewCallbackButton)
        viewLayoutButton = button("Layout: ${if (viewUseCustomLayout) "custom" else "default"}") {
            viewUseCustomLayout = !viewUseCustomLayout
            viewLayoutButton.text = "Layout: ${if (viewUseCustomLayout) "custom" else "default"}"
            rebuildViewCards(options)
        }
        viewLayoutButton.isEnabled = options.templatePreset == null
        secondRow.addView(viewLayoutButton)
        secondRow.addView(button("Details") { openDetails() })
        controls.addView(secondRow)
        return controls
    }

    private fun rebuildViewCards(options: NativeSmokeOptions) {
        cards.toList().forEach(AdsNativeView::destroy)
        cards.clear()
        viewCardContainer.removeAllViews()
        val layout = if (viewUseCustomLayout) options.customNativeLayout() else NativeLayout.Default
        repeat(if (options.twoCards) 2 else 1) { index ->
            val card = AdsNativeView(
                activity = this,
                lifecycleOwner = this,
                request = options.request(index),
                layout = layout,
                active = viewActive,
                visible = viewVisible,
                onStateChanged = stateCallback(index),
                retentionPolicy = options.retentionPolicy,
            )
            cards += card
            viewCardContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 12
            })
        }
    }

    private fun stateCallback(index: Int): (NativeState) -> Unit = { state ->
        val message = "card=$index callback=$viewCallbackGeneration state=$state"
        Log.i(TAG, message)
        runOnUiThread { if (!isFinishing && !isDestroyed) viewStatus.text = message }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun openDetails() {
        startActivity(Intent(this, NativeSmokeDetailsActivity::class.java))
    }
}

internal enum class SmokeMode { VIEW, COMPOSE, NAV }

internal data class NativeSmokeOptions(
    val mode: SmokeMode,
    val platform: AdPlatform,
    val customLayout: Boolean,
    val compactLayout: Boolean,
    val twoCards: Boolean,
    val ratio: Float?,
    val initialActive: Boolean = true,
    val initialVisible: Boolean = true,
    val templatePreset: String? = null,
    val assetsLayout: Boolean = false,
    val retentionPolicy: NativeRetentionPolicy = NativeRetentionPolicy.DESTROY_ON_HIDE,
) {
    init {
        require(templatePreset == null || templatePreset == "healthtracker-4x1") {
            "templatePreset must be healthtracker-4x1 or omitted"
        }
        if (templatePreset != null) {
            require(BuildConfig.DEBUG && BuildConfig.NATIVE_SMOKE) {
                "Historical template presets require Native smoke Debug"
            }
            require(platform == AdPlatform.TOPON && !customLayout) {
                "Historical template presets require TopOn default layout"
            }
            require(ratio == null) { "Use templateRatio or templatePreset, not both" }
        }
    }

    private val templateRatio: Float?
        // Explicit diagnostic candidate from HealthTracker a856c1fe, never a production default.
        get() = if (templatePreset != null) 4f else ratio ?: BuildConfig.NATIVE_TEST_TEMPLATE_RATIO.toFloatOrNull()

    fun request(index: Int) = NativeRequest(
        position = "native_smoke_${index + 1}",
        topOnTemplateAspectRatio = templateRatio,
    )

    fun customNativeLayout(): NativeLayout.Custom = if (assetsLayout) {
        NativeLayout.Custom.withAssets { context, assets ->
            // 快照用于业务样式选择；真实素材及点击仍由库绑定。
            createCustomNativeLayout(context, compact = assets.mediaType == NativeMediaType.IMAGE)
        }
    } else NativeLayout.Custom { createCustomNativeLayout(it, compactLayout) }

    fun summary() = "mode=${mode.name.lowercase(Locale.US)} platform=初始化配置 " +
        "retention=$retentionPolicy assets=$assetsLayout layout=${if (compactLayout) "compact" else if (customLayout) "custom" else "default"} twoCards=$twoCards" +
        if (platform == AdPlatform.TOPON) {
            "\ntemplateRatio=${templateRatio ?: "unknown"} sizing=" + when {
                templatePreset != null -> "healthtracker-4x1 CANDIDATE (not backend-confirmed)"
                ratio != null -> "explicit"
                templateRatio != null -> "test-config"
                else -> "unconfigured"
            }
        } else ""

    companion object {
        fun from(intent: Intent) = NativeSmokeOptions(
            mode = when (intent.getStringExtra("mode").orEmpty().lowercase(Locale.US)) {
                "view", "" -> SmokeMode.VIEW
                "compose" -> SmokeMode.COMPOSE
                "nav" -> SmokeMode.NAV
                else -> error("mode must be view, compose or nav")
            },
            platform = if (BuildConfig.NATIVE_PLATFORM == "topon") AdPlatform.TOPON else AdPlatform.ADMOB,
            customLayout = when (intent.getStringExtra("layout").orEmpty().lowercase(Locale.US)) {
                "", "default" -> false
                "custom", "compact", "assets" -> true
                else -> error("layout must be default, custom, compact or assets")
            },
            compactLayout = intent.getStringExtra("layout") == "compact",
            twoCards = intent.getBooleanExtra("twoCards", false),
            ratio = intent.getStringExtra("templateRatio")?.toFloat(),
            initialActive = intent.getBooleanExtra("active", true),
            initialVisible = intent.getBooleanExtra("visible", true),
            templatePreset = intent.getStringExtra("templatePreset"),
            assetsLayout = intent.getStringExtra("layout") == "assets",
            retentionPolicy = when (intent.getStringExtra("retention").orEmpty()) {
                "", "destroy" -> NativeRetentionPolicy.DESTROY_ON_HIDE
                "retain" -> NativeRetentionPolicy.RETAIN_WHILE_PAGE_ALIVE
                else -> error("retention must be destroy or retain")
            },
        )
    }
}

@Composable
private fun NativeSmokeComposeScreen(
    options: NativeSmokeOptions,
    lifecycleOwner: LifecycleOwner,
    onDetails: () -> Unit,
    businessActive: Boolean = true,
    onDialog: (() -> Unit)? = null,
) {
    var active by remember { mutableStateOf(options.initialActive) }
    var visible by remember { mutableStateOf(options.initialVisible) }
    var retryToken by remember { mutableIntStateOf(0) }
    var callbackGeneration by remember { mutableIntStateOf(0) }
    var customLayout by remember { mutableStateOf(options.customLayout) }
    val stableCustomLayout = remember(options) { options.customNativeLayout() }
    val layout = if (customLayout) stableCustomLayout else NativeLayout.Default
    MaterialTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ComposeText(options.summary())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposeButton(onClick = { retryToken++ }) { ComposeText("Retry") }
                ComposeButton(onClick = { visible = !visible }) { ComposeText("Visible: $visible") }
                ComposeButton(onClick = { active = !active }) { ComposeText("Active: $active") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposeButton(onClick = { callbackGeneration++ }) { ComposeText("Callback: $callbackGeneration") }
                ComposeButton(enabled = options.templatePreset == null, onClick = { customLayout = !customLayout }) {
                    ComposeText("Layout: ${if (customLayout) "custom" else "default"}")
                }
            }
            ComposeButton(onClick = onDetails) { ComposeText("Details") }
            if (onDialog != null) ComposeButton(onClick = onDialog) { ComposeText("Dialog") }
            Spacer(Modifier.height(8.dp))
            repeat(if (options.twoCards) 2 else 1) { index ->
                AdsNative(
                    request = options.request(index),
                    modifier = Modifier.fillMaxWidth(),
                    layout = layout,
                    lifecycleOwner = lifecycleOwner,
                    active = active && businessActive,
                    visible = visible,
                    retryToken = retryToken,
                    retentionPolicy = options.retentionPolicy,
                    onStateChanged = { state ->
                        Log.i(TAG, "compose card=$index callback=$callbackGeneration state=$state")
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Serializable internal object NativeHome
@Serializable internal object NativeDetails
@Serializable internal object NativeDialog

/** 具体页面 owner 决定最终销毁；暂离按 retentionPolicy 处理，未知来源降级释放。 */
@Composable
internal fun NativeSmokeNavigation(
    options: NativeSmokeOptions,
    navController: NavHostController = rememberNavController(),
) {
    val current by navController.currentBackStackEntryAsState()
    MaterialTheme {
        NavHost(navController, startDestination = NativeHome) {
            composable<NativeHome> { entry ->
                NativeSmokeComposeScreen(
                    options = options,
                    lifecycleOwner = entry,
                    businessActive = current == entry || current?.destination is FloatingWindow,
                    onDetails = { navController.navigate(NativeDetails) },
                    onDialog = { navController.navigate(NativeDialog) },
                )
            }
            composable<NativeDetails> {
                Column(Modifier.padding(16.dp)) {
                    ComposeText("Native navigation details")
                    ComposeButton(onClick = { navController.popBackStack() }) { ComposeText("Return") }
                }
            }
            dialog<NativeDialog> {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(24.dp)) {
                        ComposeText("Native host dialog")
                        ComposeText("The home entry is paused, not destroyed.")
                        ComposeButton(onClick = { navController.popBackStack() }) { ComposeText("Close") }
                    }
                }
            }
        }
    }
}

private fun createCustomNativeLayout(context: Context, compact: Boolean): NativeLayoutBinding {
    val root = LayoutInflater.from(context).inflate(if (compact) R.layout.smoke_native_compact else R.layout.smoke_native_custom, null, false)
    return NativeLayoutBinding(
        root = root,
        headline = root.findViewById(R.id.smoke_native_headline),
        callToAction = root.findViewById(R.id.smoke_native_call_to_action),
        media = root.findViewById(R.id.smoke_native_media),
        adLabel = root.findViewById(R.id.smoke_native_ad_label),
        adChoices = root.findViewById(R.id.smoke_native_ad_choices),
        body = root.findViewById(R.id.smoke_native_body),
        advertiser = root.findViewById(R.id.smoke_native_advertiser),
        icon = root.findViewById(R.id.smoke_native_icon),
        adFrom = root.findViewById(R.id.smoke_native_ad_from),
        domain = root.findViewById(R.id.smoke_native_domain),
        warning = root.findViewById(R.id.smoke_native_warning),
    )
}
