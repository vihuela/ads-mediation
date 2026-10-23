package com.cashcraft.ads.mediation

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cashcraft.ads.mediation.internal.AdLifecycleMonitor
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdHandle
import com.cashcraft.ads.mediation.internal.nativeads.NativeCallbacks
import com.cashcraft.ads.mediation.internal.nativeads.NativeRevenue
import com.cashcraft.ads.mediation.internal.nativeads.createDefaultNativeLayout
import com.cashcraft.ads.mediation.internal.topon.TopOnAds
import com.cashcraft.ads.mediation.internal.topon.TopOnLoadCooldown
import com.cashcraft.ads.mediation.internal.topon.TopOnState
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** Opt in with -PtopOnLiveTest=<method>; GAID test mode is set in TopOn's console. */
@Suppress("DEPRECATION")
class TopOnLiveDeviceTest : InstrumentationTestCase() {
    private data class Observation(val at: Long, val event: AdEvent,
        val sdkCallAt: Long? = null, val failureHandledAt: Long? = null)

    private val observations = CopyOnWriteArrayList<Observation>()
    private val notes = mutableListOf<String>()
    private val leases = mutableListOf<AutoCloseable>()
    private var activity: NativeFragmentTestActivity? = null
    private var consumed: NativeAdHandle? = null
    private var secret = ""
    private var mergeEnsures = false
    @Volatile private var listenerFailure: Throwable? = null
    private var evidenceName = ""
    private var nativeImpressions = 0
    private var nativeClicks = 0
    private val formats = listOf(AdFormat.APP_OPEN, AdFormat.INTERSTITIAL, AdFormat.REWARDED)

    fun testRealLoadsAndSharedNativeRefill() {
        verifyRealLoadsAndSharedNativeRefill()
    }

    fun testRealFailuresRespectCooldownAndRetryBudget() {
        verifyRealFailuresRespectCooldownAndRetryBudget()
    }

    fun testRealSharedNativeTakeRefillAndRender() = verifyNativeTakeRefillAndRender(expectTemplate = false)

    fun testRealTemplateNativeTakeRefillAndRender() = verifyNativeTakeRefillAndRender(expectTemplate = true)

    private fun verifyNativeTakeRefillAndRender(expectTemplate: Boolean) {
        evidenceName = if (expectTemplate) "topon-live-native-template.jsonl" else "topon-live-native.jsonl"
        val ids = readIds(invalid = false).copy(
            appOpenPlacementId = "", interstitialPlacementId = "", rewardedPlacementId = "",
        )
        var request: ResolvedNativeRequest? = null
        var width = 0
        var firstRequestId: String? = null
        var stage = 0
        try {
            initialize(ids)
            awaitOnce("Native shared demand, consumption, render, SDK impression and real refill") {
                onMain {
                    assertSdkHealthy()
                    if (!ready()) return@onMain false
                    val host = checkNotNull(activity)
                    if (stage == 0) {
                        width = host.resources.displayMetrics.widthPixels
                        request = ResolvedNativeRequest(AdPlatform.TOPON,
                            checkNotNull(ids.nativePlacementId), "live-native-render-a",
                            topOnTemplateAspectRatio = if (expectTemplate) 2f else null)
                        val native = checkNotNull(request)
                        leases += NativeAdCache.acquireTopOnInventory(host, native, width)
                        val before = loads(AdFormat.NATIVE).size
                        leases += NativeAdCache.acquireTopOnInventory(host,
                            native.copy(position = "live-native-render-b"), width)
                        assertEquals("Shared demand duplicated the Native SDK call", before, loads(AdFormat.NATIVE).size)
                        assertEquals("Initial Native SDK request missing", 1, before)
                        stage = 1
                    }
                    val native = checkNotNull(request)
                    if (stage == 1 && NativeAdCache.peekTopOnInventory(native, width) != null) {
                        val fill = events(AdFormat.NATIVE).firstOrNull { it.event.name == AdEventName.LOADED }
                            ?: return@onMain false
                        assertPaired(fill)
                        firstRequestId = fill.event.requestId
                        consumed = NativeAdCache.takeTopOnInventory(native, width, callbacks)
                        val ad = checkNotNull(consumed) { "Ready Native inventory could not be consumed" }
                        assertEquals(AdPlatform.TOPON, ad.platform)
                        assertEquals("Unexpected Native rendering mode", expectTemplate, ad.isTemplate)
                        assertTrue("Native SDK source metadata absent", !ad.adSource.isNullOrBlank())
                        if (expectTemplate) assertEquals(NativeAssets(), ad.assets)
                        else assertTrue("Native headline absent", !ad.assets.headline.isNullOrBlank())
                        assertTrue("Consumed Native invalid", ad.isValid)
                        assertEquals(fill.event.responseId, ad.responseId)
                        val rendered = ad.render(host, if (expectTemplate) null else createDefaultNativeLayout(host), width)
                        host.setContentView(FrameLayout(host).apply {
                            addView(rendered, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT))
                        })
                        notes += "native_take_render source=${ad.adSource} response=${ad.responseId} media=${ad.assets.mediaType}"
                        stage = 2
                    }
                    if (stage != 2 || nativeImpressions == 0) return@onMain false
                    val refill = events(AdFormat.NATIVE).firstOrNull {
                        it.event.name == AdEventName.LOADED && it.event.requestId != firstRequestId
                    } ?: return@onMain false
                    if (NativeAdCache.peekTopOnInventory(native, width) == null) return@onMain false
                    assertPaired(refill)
                    assertEquals("Unexpected ad click", 0, nativeClicks)
                    assertTrue("Fullscreen should be disabled", formats.all { events(it).isEmpty() })
                    notes += "covered=native_shared_demand,take,render,SDK_impression,real_SDK_refill; impressions=$nativeImpressions; clicks=$nativeClicks"
                    true
                }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.filesDir, evidenceName.removeSuffix(".jsonl") + ".png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        } finally {
            cleanupAndSave()
        }
    }

    private fun verifyRealLoadsAndSharedNativeRefill() {
        evidenceName = "topon-live-valid.jsonl"
        val ids = readIds(invalid = false)
        var request: ResolvedNativeRequest? = null
        var width = 0
        var firstRequestId: String? = null
        var stage = 0
        try {
            initialize(ids)
            awaitOnce("three fullscreen fills, self-rendered Native consumption and SDK refill") {
                onMain {
                    assertSdkHealthy()
                    if (!ready()) return@onMain false
                    if (stage == 0) {
                        width = checkNotNull(activity).resources.displayMetrics.widthPixels
                        // Same placement and compatible dimensions, separate business positions.
                        request = ResolvedNativeRequest(AdPlatform.TOPON,
                            checkNotNull(ids.nativePlacementId), "live-native-a")
                        val native = checkNotNull(request)
                        leases += NativeAdCache.acquireTopOnInventory(checkNotNull(activity), native, width)
                        val before = events(AdFormat.NATIVE).count { it.event.name == AdEventName.LOAD }
                        leases += NativeAdCache.acquireTopOnInventory(checkNotNull(activity),
                            native.copy(position = "live-native-b"), width)
                        assertEquals("Shared demand duplicated a real Native SDK request", before,
                            events(AdFormat.NATIVE).count { it.event.name == AdEventName.LOAD })
                        assertEquals("Initial Native SDK request was not issued", 1, before)
                        stage = 1
                    }
                    val native = checkNotNull(request)
                    if (stage == 1 && NativeAdCache.peekTopOnInventory(native, width) != null) {
                        val filled = events(AdFormat.NATIVE).firstOrNull { it.event.name == AdEventName.LOADED }
                            ?: return@onMain false
                        assertPaired(filled)
                        firstRequestId = filled.event.requestId
                        consumed = NativeAdCache.takeTopOnInventory(native, width, callbacks)
                        assertNotNull("Ready Native inventory could not be consumed", consumed)
                        val ad = checkNotNull(consumed)
                        notes += "native_take source=${ad.adSource} template=${ad.isTemplate} response=${ad.responseId}"
                        assertEquals(AdPlatform.TOPON, ad.platform)
                        assertFalse("UNCOVERED: SDK returned template Native; self-rendered take/refill not validated", ad.isTemplate)
                        assertTrue("Native SDK source metadata absent", !ad.adSource.isNullOrBlank())
                        assertTrue("Consumed Native handle is invalid", ad.isValid)
                        assertEquals(filled.event.responseId, ad.responseId)
                        stage = 2
                    }
                    if (stage != 2) return@onMain false
                    val refill = events(AdFormat.NATIVE).firstOrNull {
                        it.event.name == AdEventName.LOADED && it.event.requestId != firstRequestId
                    } ?: return@onMain false
                    if (NativeAdCache.peekTopOnInventory(native, width) == null) return@onMain false
                    assertPaired(refill)
                    for (format in formats) {
                        val fill = events(format).firstOrNull { it.event.name == AdEventName.LOADED }
                            ?: return@onMain false
                        assertPaired(fill)
                        assertTrue("$format SDK source metadata absent", !fill.event.adSource.isNullOrBlank())
                    }
                    assertNoDisplay()
                    notes += "covered=fullscreen_LOAD_LOADED,native_shared_demand,take,real_SDK_refill"
                    true
                }
            }
        } finally {
            cleanupAndSave()
        }
    }

    private fun verifyRealFailuresRespectCooldownAndRetryBudget() {
        evidenceName = "topon-live-invalid.jsonl"
        val ids = readIds(invalid = true)
        var resumedAt: Long? = null
        var awaitingBackground = false
        try {
            mergeEnsures = true
            initialize(ids)
            awaitOnce("four real SDK failures per format and exhausted foreground resume") {
                val exhausted = onMain {
                    assertSdkHealthy()
                    for (format in formats) {
                        assertTrue("$format exceeded four real LOAD calls", loads(format).size <= 4)
                        assertTrue("$format exceeded four real LOAD_FAIL callbacks", failures(format).size <= 4)
                        assertTrue("Invalid $format placement unexpectedly filled",
                            events(format).none { it.event.name == AdEventName.LOADED })
                    }
                    formats.all { failures(it).size == 4 }
                }
                if (!exhausted) return@awaitOnce false
                if (!awaitingBackground && resumedAt == null) {
                    onMain {
                        formats.forEach(::assertFailureRound)
                        assertTrue("Native must be disabled", events(AdFormat.NATIVE).isEmpty())
                        checkNotNull(activity).finish()
                    }
                    awaitingBackground = true
                    return@awaitOnce false
                }
                if (awaitingBackground) {
                    if (onMain { AdLifecycleMonitor.isAppInForeground }) return@awaitOnce false
                    launchActivity()
                    awaitingBackground = false
                    resumedAt = SystemClock.elapsedRealtime()
                    return@awaitOnce false
                }
                if (SystemClock.elapsedRealtime() - checkNotNull(resumedAt) < 9_000L) return@awaitOnce false
                onMain {
                    assertTrue("Replacement Activity did not resume", ready())
                    formats.forEach(::assertFailureRound)
                    assertNoDisplay()
                    val codes = formats.associateWith { format -> failures(format).map { it.event.errorCode }.distinct() }
                    notes += "actual_sdk_codes=$codes"
                    notes += if (failuresAll().any { it.event.errorCode == "2008" })
                        "2008=observed_with_same_four_attempt_budget" else "UNCOVERED: SDK code 2008 was not returned"
                    notes += "covered=real_failure_pairing,merged_ensureLoaded,2100ms_cooldown,2_4_8_backoff,exhausted_resume_9s"
                    true
                }
            }
        } finally {
            mergeEnsures = false
            cleanupAndSave()
        }
    }

    private fun readIds(invalid: Boolean): TopOnIds {
        val properties = Properties()
        File(instrumentation.targetContext.filesDir, "topon-test.properties").inputStream().use(properties::load)
        fun required(name: String) = properties.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("Missing private TopOn property: $name")
        secret = required("applicationKey")
        return TopOnIds(
            applicationId = required("applicationId"), applicationKey = secret,
            appOpenPlacementId = if (invalid) "invalid_live_appopen" else required("appOpenPlacement"),
            interstitialPlacementId = if (invalid) "invalid_live_interstitial" else required("interstitialPlacement"),
            rewardedPlacementId = if (invalid) "invalid_live_rewarded" else required("rewardedPlacement"),
            nativePlacementId = if (invalid) null else required("nativePlacement"),
        )
    }

    private fun initialize(ids: TopOnIds) {
        val config = AdsConfig(
            provider = TopOnProviderConfig(ids), umpConsent = UmpConsentConfig(enabled = false),
            autoShowAppOpen = false, loggingEnabled = false,
            eventListener = AdEventListener { event ->
                runCatching {
                    val fullscreen = event.platform == AdPlatform.TOPON && event.format in formats
                    val callAt = if (fullscreen && event.name == AdEventName.LOAD) {
                        val field = when (event.format) {
                            AdFormat.APP_OPEN -> "appOpenLoadSession"
                            AdFormat.INTERSTITIAL -> "interstitialLoadSession"
                            else -> "rewardedLoadSession"
                        }
                        ownedField(checkNotNull(ownedField(TopOnAds, field)), "startedAtMillis") as Long
                    } else null
                    val failureAt = if (fullscreen && event.name == AdEventName.LOAD_FAIL) {
                        val deadlines = ownedField(TopOnLoadCooldown.shared, "deadlines") as Map<*, *>
                        (deadlines[event.adUnitId] as? Long)?.minus(TopOnLoadCooldown.COOLDOWN_MILLIS)
                    } else null
                    observations += Observation(SystemClock.elapsedRealtime(), event, callAt, failureAt)
                    if (mergeEnsures && fullscreen && event.name == AdEventName.LOAD) {
                        val controller = checkNotNull(ownedField(TopOnAds, "fullScreenLoadControl"))
                        val rounds = ownedField(controller, "rounds") as Map<*, *>
                        val round = checkNotNull(rounds[event.adUnitId])
                        // A synchronous fourth SDK failure can exhaust before deferred LOAD is emitted.
                        if (ownedField(round, "active") == true) repeat(8) { TopOnAds.ensureLoaded(event.format) }
                    }
                }.onFailure { listenerFailure = it }
            },
        )
        onMain {
            assertEquals("Run each #method in a fresh process", TopOnState.NOT_INITIALIZED, TopOnAds.state)
            Ads.initialize(instrumentation.targetContext.applicationContext as Application, config)
        }
        launchActivity()
    }

    private fun launchActivity() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            NativeFragmentTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeFragmentTestActivity
    }

    private fun ready() = TopOnAds.state == TopOnState.READY &&
        AdLifecycleMonitor.isAppInForeground && AdLifecycleMonitor.currentActivity === activity

    private fun assertSdkHealthy() {
        listenerFailure?.let { throw it }
        assertFalse("TopOn SDK initialization failed; load coverage unavailable", TopOnAds.state == TopOnState.FAILED)
    }

    private fun events(format: AdFormat) = observations.filter {
        it.event.platform == AdPlatform.TOPON && it.event.format == format && it.event.isLoadEvent
    }

    private fun loads(format: AdFormat) = events(format).filter { it.event.name == AdEventName.LOAD }
    private fun failures(format: AdFormat) = events(format).filter { it.event.name == AdEventName.LOAD_FAIL }
    private fun failuresAll() = formats.flatMap(::failures)

    private fun assertPaired(terminal: Observation) {
        val event = terminal.event
        assertTrue("Missing requestId for ${event.format}", !event.requestId.isNullOrBlank())
        val pair = events(event.format).filter { it.event.requestId == event.requestId }
        assertEquals("Unpaired actual SDK event for ${event.format}",
            listOf(AdEventName.LOAD, event.name), pair.map { it.event.name })
        assertFalse("Host-driven TopOn request marked as SDK auto-load", event.sdkAutoLoad)
        assertNotNull("SDK latency missing", event.latencyMillis)
    }

    private fun assertFailureRound(format: AdFormat) {
        val starts = loads(format)
        val failed = failures(format)
        assertEquals("$format did not finish exactly four SDK requests", 4, starts.size)
        assertEquals("$format failure budget or resume changed", 4, failed.size)
        failed.forEachIndexed { index, result ->
            assertPaired(result)
            assertEquals(starts[index].event.requestId, result.event.requestId)
            assertTrue("$format local exception is not real SDK failure coverage",
                !result.event.errorCode.isNullOrBlank() && result.event.errorCode != "exception")
            assertTrue("$format SDK failure detail missing", !result.event.errorDetail.isNullOrBlank())
            if (index < 3) {
                val minimum = maxOf(2_100L, 2_000L shl index)
                // SDK callbacks publish telemetry after installing cooldown; use that owned timestamp.
                val gap = checkNotNull(starts[index + 1].sdkCallAt) - checkNotNull(result.failureHandledAt)
                assertTrue("$format retry ${index + 1}: failure-to-load=${gap}ms, required>=${minimum}ms", gap >= minimum)
            }
        }
    }

    private fun assertNoDisplay() {
        assertTrue("Load-only test unexpectedly displayed/clicked an ad", observations.none {
            it.event.name in listOf(AdEventName.IMPRESSION, AdEventName.CLICK, AdEventName.REWARD)
        })
    }

    private fun awaitOnce(label: String, step: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (step()) return
            SystemClock.sleep(100L)
        }
        val coverage = (formats + AdFormat.NATIVE).associateWith { format ->
            events(format).groupingBy { it.event.name }.eachCount()
        }
        fail("UNCOVERED after one 90s window: $label; actual=$coverage; evidence=$evidenceName")
    }

    private fun cleanupAndSave() {
        try {
            onMain {
                leases.asReversed().forEach { it.close() }
                consumed?.destroy()
                activity?.finish()
            }
        } finally {
            val evidence = File(instrumentation.targetContext.filesDir, evidenceName)
            evidence.bufferedWriter().use { writer ->
                observations.forEach { observed ->
                    val event = observed.event
                    val json = JSONObject().put("elapsed_ms", observed.at)
                        .put("format", event.format.name).put("name", event.name.name)
                        .put("placement", event.adUnitId).put("request_id", event.requestId)
                        .put("response_id", event.responseId).put("source", event.adSource)
                        .put("code", event.errorCode).put("detail", event.errorDetail)
                        .put("latency_ms", event.latencyMillis).put("sdk_auto_load", event.sdkAutoLoad)
                        .put("sdk_call_ms", observed.sdkCallAt).put("failure_handled_ms", observed.failureHandledAt)
                    writer.appendLine(redact(json.toString()))
                }
                notes.forEach { writer.appendLine(redact(JSONObject().put("note", it).toString())) }
            }
            Log.i("TopOnLiveDevice", "Evidence saved: $evidenceName; observed=${observations.size}")
        }
    }

    private fun redact(text: String) = if (secret.isEmpty()) text else text.replace(secret, "[redacted]")

    /** Only module-owned state from source; never inspect a third-party SDK object. */
    private fun ownedField(owner: Any, name: String): Any? {
        check(owner.javaClass.name.startsWith("com.cashcraft.ads.mediation.internal."))
        return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private val callbacks = object : NativeCallbacks {
        override fun loaded(ad: NativeAdHandle) = Unit
        override fun failed(reason: String, errorCode: String?, errorDetail: String?) = Unit
        override fun impression(adSource: String?, responseId: String?, revenue: NativeRevenue?) {
            nativeImpressions++
            notes += "SDK_native_impression source=$adSource response=$responseId"
        }
        override fun clicked(adSource: String?, responseId: String?) { nativeClicks++ }
        override fun closed() = Unit
        override fun overlayOpened() = Unit
        override fun overlayClosed() = Unit
        override fun paid(revenue: NativeRevenue) = Unit
    }
}
