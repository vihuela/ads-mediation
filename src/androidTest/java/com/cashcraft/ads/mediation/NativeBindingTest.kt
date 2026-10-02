package com.cashcraft.ads.mediation

import android.test.AndroidTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.cashcraft.ads.mediation.internal.nativeads.createDefaultNativeLayout
import com.cashcraft.ads.mediation.internal.nativeads.DefaultNativeMediaView
import com.cashcraft.ads.mediation.internal.nativeads.validateTopOnMaterial
import com.cashcraft.ads.mediation.internal.nativeads.bindTopOnText
import com.cashcraft.ads.mediation.internal.nativeads.attachTopOnView
import com.cashcraft.ads.mediation.internal.nativeads.bindAdMobText
import com.cashcraft.ads.mediation.internal.nativeads.bindTopOnAttribution
import com.cashcraft.ads.mediation.internal.nativeads.requireTopOnSdkMedia
import com.cashcraft.ads.mediation.internal.nativeads.topOnNativeTemplateHeight
import com.thinkup.nativead.unitgroup.api.CustomNativeAd
import kotlin.math.roundToInt

/** Uses Android's built-in runner; no extra test framework or SDK ad request is needed. */
@Suppress("DEPRECATION")
class NativeBindingTest : AndroidTestCase() {
    fun testDefaultMediaUsesActualAssetRatioAcrossWidthChanges() {
        val binding = createDefaultNativeLayout(context)
        val media = binding.media as DefaultNativeMediaView
        val sdkView = View(context)
        media.addView(sdkView, ViewGroup.LayoutParams(-1, -1))
        val fallbackHeight = media.layoutParams.height
        media.setMediaDimensions(-1, 0)
        measure(binding.root, dp(320))
        assertEquals(fallbackHeight, media.measuredHeight)
        media.setMediaDimensions(1200, 600)
        listOf(320, 240, 400).forEach { width ->
            measure(binding.root, dp(width))
            assertEquals((media.measuredWidth / 2.0).roundToInt(), media.measuredHeight)
            assertEquals(media.measuredWidth, sdkView.measuredWidth)
            assertEquals(media.measuredHeight, sdkView.measuredHeight)
        }
        // Square and portrait assets must not inherit a landscape preference ratio.
        listOf(600 to 600, 600 to 900).forEach { (width, height) ->
            media.setMediaDimensions(width, height)
            measure(binding.root, dp(320))
            assertEquals((media.measuredWidth.toDouble() * height / width).roundToInt(), media.measuredHeight)
        }
        media.setPadding(dp(4), dp(2), dp(4), dp(2))
        media.setMediaDimensions(1200, 600)
        measure(binding.root, dp(320))
        assertEquals((sdkView.measuredWidth / 2.0).roundToInt(), sdkView.measuredHeight)
    }

    fun testDefaultBindingsAreIndependentAndValid() {
        val first = createDefaultNativeLayout(context)
        val second = createDefaultNativeLayout(context)
        first.validate()
        second.validate()
        assertNotSame(first.root, second.root)
        assertNotSame(first.media, second.media)
        assertNotSame(first.icon, second.icon)
        assertNotSame(first.adChoices, second.adChoices)
        listOf(first, second).forEach { binding ->
            assertNotNull(binding.adFrom)
            assertNotNull(binding.domain)
            assertNotNull(binding.warning)
            assertNotNull(binding.advertiserInfo)
        }
    }

    fun testAdMobTextPreservesCopyAndRestoresHiddenRequiredSlots() {
        val binding = createDefaultNativeLayout(context)
        val adLabel = binding.adLabel.text.toString()
        val assets = NativeAssets(
            headline = "  真实标题\n第二行  ", body = "原始正文", callToAction = "立即查看",
            advertiser = "真实广告主",
        )
        binding.headline.visibility = View.GONE
        binding.callToAction.visibility = View.INVISIBLE
        listOf(binding.adFrom!!, binding.domain!!, binding.warning!!).forEach {
            it.text = "业务占位"
            it.visibility = View.VISIBLE
        }
        binding.bindAdMobText(assets)
        listOf(
            binding.headline to assets.headline, binding.body!! to assets.body,
            binding.callToAction to assets.callToAction, binding.advertiser!! to assets.advertiser,
        ).forEach { (view, expected) ->
            assertEquals(expected, view.text.toString())
            assertEquals(View.VISIBLE, view.visibility)
        }
        binding.bindAdMobText(assets.copy(body = null, advertiser = null))
        listOf(binding.body!!, binding.advertiser!!, binding.adFrom!!, binding.domain!!,
            binding.warning!!).forEach {
            assertEquals("", it.text.toString())
            assertEquals(View.GONE, it.visibility)
        }
        assertEquals(adLabel, binding.adLabel.text.toString())
        assertEquals(View.VISIBLE, binding.adLabel.visibility)
        assertEquals(0, binding.adChoices.childCount)
    }

    fun testAdMobMissingRequiredCopyFailsBeforeChangingText() {
        val binding = createDefaultNativeLayout(context)
        val assets = NativeAssets(headline = "标题", callToAction = "查看")
        listOf(null, "", "  ").forEach { missing ->
            listOf(
                assets.copy(headline = missing) to "native_missing_headline",
                assets.copy(callToAction = missing) to "native_missing_call_to_action",
            ).forEach { (invalid, reason) ->
                binding.headline.text = "未绑定"
                try {
                    binding.bindAdMobText(invalid)
                    fail("Expected $reason")
                } catch (error: IllegalArgumentException) {
                    assertEquals(reason, error.message)
                }
                assertEquals("未绑定", binding.headline.text.toString())
            }
        }
    }

    fun testTopOnAttributionKeepsSeparateAndLegacySlotSemantics() {
        val binding = createDefaultNativeLayout(context)
        val assets = NativeAssets(advertiser = "  真实广告主  ", adFrom = "真实来源")
        val bound = mutableListOf<View>()
        binding.bindTopOnAttribution(assets) { bound.add(it) }
        assertEquals(assets.adFrom, binding.adFrom!!.text.toString())
        assertEquals(assets.advertiser, binding.advertiser!!.text.toString())
        assertEquals(listOf(binding.adFrom), bound)

        val legacy = binding.copy(adFrom = null)
        bound.clear()
        legacy.bindTopOnAttribution(assets) { bound.add(it) }
        assertEquals(assets.adFrom, legacy.advertiser!!.text.toString())
        assertEquals(listOf(legacy.advertiser), bound)
        bound.clear()
        legacy.bindTopOnAttribution(assets.copy(adFrom = null)) { bound.add(it) }
        assertEquals(assets.advertiser, legacy.advertiser!!.text.toString())
        assertEquals(View.VISIBLE, legacy.advertiser!!.visibility)
        assertTrue(bound.isEmpty())
        legacy.bindTopOnAttribution(NativeAssets()) { bound.add(it) }
        assertEquals("", legacy.advertiser!!.text.toString())
        assertEquals(View.GONE, legacy.advertiser!!.visibility)
    }

    fun testTopOnMetadataPreservesCopyAndClearsMissingValues() {
        val binding = createDefaultNativeLayout(context)
        val clicks = mutableListOf<View>()
        val bound = mutableListOf<View>()
        listOf(binding.domain!!, binding.warning!!).forEach { view ->
            val text = "  原始文案\n下一行  "
            view.bindTopOnText(text, { bound.add(it) }, clicks)
            assertEquals(text, view.text.toString())
            assertEquals(View.VISIBLE, view.visibility)
        }
        assertEquals(listOf(binding.domain, binding.warning), bound)
        assertEquals(bound, clicks)
        clicks.clear()
        bound.clear()
        listOf(binding.domain!!, binding.warning!!).forEach { view ->
            view.bindTopOnText(null, { bound.add(it) }, clicks)
            assertEquals("", view.text.toString())
            assertEquals(View.GONE, view.visibility)
        }
        assertTrue(bound.isEmpty())
        assertTrue(clicks.isEmpty())
    }

    fun testTopOnKnownVideoCannotFallBackToImages() {
        val video = CustomNativeAd.NativeAdConst.VIDEO_TYPE
        requireTopOnSdkMedia(video, View(context))
        try {
            requireTopOnSdkMedia(video, null)
            fail("Expected missing SDK video media to fail before image fallback")
        } catch (error: IllegalArgumentException) {
            assertEquals("native_video_media_missing", error.message)
        }
        // 图片和未知类型继续现有图片兜底；本检查不宣称无媒体槽位可用。
        listOf(null, CustomNativeAd.NativeAdConst.IMAGE_TYPE,
            CustomNativeAd.NativeAdConst.UNKNOWN_TYPE).forEach { requireTopOnSdkMedia(it, null) }
    }

    fun testTopOnTemplateRequiresConfirmedRatioAndUnchangedWidth() {
        assertEquals(160, topOnNativeTemplateHeight(320, 320, 2f))
        assertEquals(120, topOnNativeTemplateHeight(240, 240, 2f))
        listOf(
            Triple(320, 320, null) to "native_template_size_unknown",
            Triple(240, 320, 2f) to "native_template_size_changed",
        ).forEach { (size, reason) ->
            try {
                topOnNativeTemplateHeight(size.first, size.second, size.third)
                fail("Expected $reason")
            } catch (error: IllegalArgumentException) {
                assertEquals(reason, error.message)
            }
        }
    }

    fun testHistoricalTextOnlyLayoutDoesNotRequireMediaSlot() {
        val binding = createDefaultNativeLayout(context).copy(media = null)
        binding.validate()
        binding.validateTopOnMaterial("正文", null, "https://example.com/icon.png")
        val clicks = mutableListOf<View>()
        val bound = mutableListOf<View>()
        binding.headline.bindTopOnText("标题", { bound.add(it) }, clicks)
        binding.body!!.bindTopOnText("正文", { bound.add(it) }, clicks)
        binding.callToAction.bindTopOnText("查看", { bound.add(it) }, clicks)
        assertEquals(listOf(binding.headline, binding.body, binding.callToAction), bound)
        assertEquals(bound, clicks)
        assertEquals("标题", binding.headline.text.toString())
        assertEquals("正文", binding.body!!.text.toString())
        assertEquals("查看", binding.callToAction.text.toString())
        assertNull(binding.media)
    }

    fun testAlreadyAttachedRootIsRejectedWithoutRemovingItsParent() {
        val binding = createDefaultNativeLayout(context)
        val host = FrameLayout(context)
        host.addView(binding.root)
        assertInvalid(binding)
        assertSame(host, binding.root.parent)
    }

    fun testForeignAndDuplicateAssetsAreRejected() {
        val binding = createDefaultNativeLayout(context)
        assertInvalid(binding.copy(headline = TextView(context)))
        assertInvalid(binding.copy(callToAction = binding.headline))
    }

    fun testOccupiedMediaAndMissingAttributionAreRejected() {
        val occupied = createDefaultNativeLayout(context)
        occupied.media!!.addView(TextView(context))
        assertInvalid(occupied)
        val unlabeled = createDefaultNativeLayout(context)
        unlabeled.adLabel.text = ""
        assertInvalid(unlabeled)
    }

    fun testTopOnReturnedRequiredAssetsCannotBeSilentlyOmitted() {
        val binding = createDefaultNativeLayout(context)
        val sdkIcon = FrameLayout(context)
        binding.validateTopOnMaterial("Description", sdkIcon)
        binding.copy(body = null, icon = null).validateTopOnMaterial(null, null)
        listOf(
            binding.copy(body = null) to "native_body_required",
            binding.copy(icon = null) to "native_icon_required",
        ).forEach { (missingAsset, reason) ->
            try {
                missingAsset.validateTopOnMaterial("Description", sdkIcon)
                fail("Expected $reason")
            } catch (error: IllegalArgumentException) {
                assertEquals(reason, error.message)
            }
        }
    }

    fun testTopOnOptionalTextIsHiddenAndOnlyReturnedTextBindsClicks() {
        val binding = createDefaultNativeLayout(context)
        val clicks = mutableListOf<View>()
        val bound = mutableListOf<View>()
        listOf(binding.headline, binding.body!!, binding.callToAction).forEach { view ->
            view.bindTopOnText(null, { bound.add(it) }, clicks)
            assertEquals(View.GONE, view.visibility)
            assertEquals("", view.text.toString())
            view.bindTopOnText("  ", { bound.add(it) }, clicks)
            assertEquals(View.GONE, view.visibility)
            view.bindTopOnText("Returned text", { bound.add(it) }, clicks)
            assertEquals(View.VISIBLE, view.visibility)
            assertEquals("Returned text", view.text.toString())
        }
        assertEquals(3, bound.size)
        assertEquals(bound, clicks)
        binding.adFrom!!.bindTopOnText("Source", { bound.add(it) })
        assertEquals(View.VISIBLE, binding.adFrom!!.visibility)
        assertEquals(3, clicks.size)
    }

    fun testTopOnMetadataAndUrlOnlyIconRequireSlots() {
        val binding = createDefaultNativeLayout(context)
        binding.validateTopOnMaterial(null, null, "icon", "source", "domain", "warning")
        listOf(
            binding.copy(icon = null) to "native_icon_required",
            binding.copy(adFrom = null, advertiser = null) to "native_ad_from_required",
            binding.copy(domain = null) to "native_domain_required",
            binding.copy(warning = null) to "native_warning_required",
        ).forEach { (missing, reason) ->
            try {
                missing.validateTopOnMaterial(null, null, "icon", "source", "domain", "warning")
                fail("Expected $reason")
            } catch (error: IllegalArgumentException) {
                assertEquals(reason, error.message)
            }
        }
    }

    fun testTopOnSdkViewMovesFromOldParentWithoutChangingHostSize() {
        val oldParent = FrameLayout(context)
        val host = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(dp(80), dp(100)) }
        val sdkView = View(context)
        oldParent.addView(sdkView, ViewGroup.LayoutParams(dp(40), dp(60)))
        host.attachTopOnView(sdkView)
        assertEquals(0, oldParent.childCount)
        assertSame(host, sdkView.parent)
        assertEquals(dp(80), host.layoutParams.width)
        assertEquals(dp(100), host.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, sdkView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, sdkView.layoutParams.height)
        host.attachTopOnView(sdkView)
        assertEquals(1, host.childCount)
        try {
            host.attachTopOnView(host)
            fail("Expected self attachment to fail")
        } catch (error: IllegalArgumentException) {
            assertEquals("native_asset_invalid", error.message)
        }
    }

    fun testDefaultMultiImageRatioAccountsForAllImages() {
        val binding = createDefaultNativeLayout(context)
        val media = binding.media as DefaultNativeMediaView
        media.setMediaDimensions(600, 400, 3)
        measure(binding.root, dp(320))
        assertEquals((media.measuredWidth / 4.5).roundToInt(), media.measuredHeight)
    }

    fun testVideoGuardUsesMeasuredMediaIncludingContainerPadding() {
        val binding = createDefaultNativeLayout(context)
        val sdkMedia = View(context)
        binding.media!!.addView(sdkMedia, ViewGroup.LayoutParams(-1, -1))
        measure(binding.root, dp(320))
        sdkMedia.requireNativeVideoSize()
        // The default's 180dp height does not make a narrow host valid.
        measure(binding.root, dp(130))
        assertSmallVideo(sdkMedia)
        binding.media!!.layoutParams.width = dp(60)
        binding.media!!.layoutParams.height = dp(60)
        binding.media!!.requestLayout()
        measure(binding.root, dp(320))
        assertSmallVideo(sdkMedia)
        // A nominal 120dp box with internal padding leaves less than 120dp for the SDK.
        binding.media!!.layoutParams.width = dp(120)
        binding.media!!.layoutParams.height = dp(120)
        binding.media!!.setPadding(dp(1), 0, 0, 0)
        measure(binding.root, dp(320))
        assertSmallVideo(sdkMedia)
        binding.media!!.setPadding(0, 0, 0, 0)
        measure(binding.root, dp(320))
        sdkMedia.requireNativeVideoSize()
    }

    fun testVideoGuardObservesLaterShrinkAndCanBeRemoved() {
        val media = View(context)
        measure(media, dp(120), dp(120))
        var failures = 0
        val remove = media.observeNativeVideoSize { failures++ }
        media.layout(0, 0, dp(120), dp(120))
        assertEquals(0, failures)
        measure(media, dp(119), dp(120))
        media.layout(0, 0, dp(119), dp(120))
        assertEquals(1, failures)
        remove()
        media.layout(0, 0, dp(60), dp(60))
        assertEquals(1, failures)
    }

    private fun dp(value: Int) = kotlin.math.ceil(value * context.resources.displayMetrics.density).toInt()

    private fun measure(view: View, width: Int, height: Int? = null) = view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height ?: 0,
            if (height == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY),
    )

    private fun assertSmallVideo(view: View) {
        try {
            view.requireNativeVideoSize()
            fail("Expected undersized video to fail before registration")
        } catch (error: IllegalArgumentException) {
            assertEquals("native_video_media_too_small", error.message)
        }
    }

    private fun assertInvalid(binding: NativeLayoutBinding) {
        try {
            binding.validate()
            fail("Expected invalid binding")
        } catch (_: IllegalArgumentException) {
            // Invalid bindings are refused before any platform rendering.
        }
    }
}
