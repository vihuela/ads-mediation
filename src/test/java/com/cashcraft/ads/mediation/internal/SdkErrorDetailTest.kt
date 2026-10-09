package com.cashcraft.ads.mediation.internal

import android.os.Bundle
import com.google.android.libraries.ads.mobile.sdk.common.AdSourceResponseInfo
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.MediationAdError
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SdkErrorDetailTest {
    @Test fun `加载详情保留多个失败广告源但排除凭据及额外参数`() {
        val credentials = Bundle().apply { putString("credential_key", "credential_secret") }
        val extras = Bundle().apply { putString("extra_key", "extra_secret") }
        val sources = listOf(
            AdSourceResponseInfo("adapter.first", 10L, MediationAdError(3, "no fill", "first.domain"),
                credentials, "first", "source-1", "instance-first", "instance-1"),
            AdSourceResponseInfo("adapter.unattempted", 0L, null,
                credentials, "unattempted", "source-2", "instance-unattempted", "instance-2"),
            AdSourceResponseInfo("adapter.last", 20L, MediationAdError(7, "network failure", "last.domain"),
                credentials, "last", "source-3", "instance-last", "instance-3"),
        )
        val response = ResponseInfo(null, "response-id", extras, null, sources)
        val error = LoadAdError(LoadAdError.ErrorCode.NO_FILL, "load failed", response)

        assertEquals(
            "code=NO_FILL message=load failed response_id=response-id" +
                "; source=first source_id=source-1 instance_id=instance-1 adapter=adapter.first" +
                " domain=first.domain code=3 message=no fill" +
                "; source=last source_id=source-3 instance_id=instance-3 adapter=adapter.last" +
                " domain=last.domain code=7 message=network failure",
            error.analyticsErrorDetail(),
        )
    }

    @Test fun `加载失败没有响应信息时只保留主错误`() {
        val error = LoadAdError(LoadAdError.ErrorCode.INTERNAL_ERROR, "load failed", null)

        assertEquals("code=INTERNAL_ERROR message=load failed", error.analyticsErrorDetail())
    }

    @Test fun `读取广告源响应异常时回退到主错误`() {
        val sources = object : AbstractList<AdSourceResponseInfo>() {
            override val size = 1
            override fun get(index: Int): AdSourceResponseInfo = error("模拟 SDK 响应读取异常")
        }
        val response = ResponseInfo(null, "response-id", Bundle(), null, sources)
        val error = LoadAdError(LoadAdError.ErrorCode.INTERNAL_ERROR, "load failed", response)

        assertEquals("code=INTERNAL_ERROR message=load failed", error.analyticsErrorDetail())
    }

    @Test fun `全屏展示失败保留底层聚合错误`() {
        val error = FullScreenContentError(FullScreenContentError.ErrorCode.MEDIATION_SHOW_ERROR,
            "show failed", MediationAdError(42, "network show failed", "network.domain"))

        assertEquals(
            "code=MEDIATION_SHOW_ERROR message=show failed" +
                "; mediation_domain=network.domain mediation_code=42 mediation_message=network show failed",
            error.analyticsErrorDetail(),
        )
    }

    @Test fun `全屏展示失败没有聚合错误时只保留主错误`() {
        val error = FullScreenContentError(FullScreenContentError.ErrorCode.INTERNAL_ERROR, "show failed", null)

        assertEquals("code=INTERNAL_ERROR message=show failed", error.analyticsErrorDetail())
    }
}
