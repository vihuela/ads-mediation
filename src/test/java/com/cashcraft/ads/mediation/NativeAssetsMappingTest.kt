package com.cashcraft.ads.mediation

import com.cashcraft.ads.mediation.internal.nativeads.adMobNativeMediaType
import com.cashcraft.ads.mediation.internal.nativeads.topOnNativeMediaType
import com.thinkup.nativead.unitgroup.api.CustomNativeAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAssetsMappingTest {
    @Test fun `AdMob needs explicit video or a real main image`() {
        assertEquals(NativeMediaType.VIDEO, adMobNativeMediaType(true, false))
        assertEquals(NativeMediaType.VIDEO, adMobNativeMediaType(true, true))
        assertEquals(NativeMediaType.IMAGE, adMobNativeMediaType(false, true))
        assertEquals(NativeMediaType.UNKNOWN, adMobNativeMediaType(false, false))
    }

    @Test fun `TopOn unknown types stay unknown`() {
        assertEquals(NativeMediaType.IMAGE, topOnNativeMediaType(CustomNativeAd.NativeAdConst.IMAGE_TYPE))
        assertEquals(NativeMediaType.VIDEO, topOnNativeMediaType(CustomNativeAd.NativeAdConst.VIDEO_TYPE))
        listOf(null, "", "unexpected", CustomNativeAd.NativeAdConst.UNKNOWN_TYPE).forEach {
            assertEquals(NativeMediaType.UNKNOWN, topOnNativeMediaType(it))
        }
    }

    @Test fun `aspect ratio requires finite positive dimensions`() {
        assertEquals(2f, nativeMediaAspectRatio(640, 320)!!, 0f)
        assertEquals(0.5f, nativeMediaAspectRatio(320, 640)!!, 0f)
        listOf(0 to 320, -1 to 320, 640 to 0, 640 to -1).forEach { (width, height) ->
            assertNull(nativeMediaAspectRatio(width, height))
        }
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertNull(nativeMediaAspectRatio(it))
        }
        assertEquals(1.5f, nativeMediaAspectRatio(1.5f)!!, 0f)
    }
}
