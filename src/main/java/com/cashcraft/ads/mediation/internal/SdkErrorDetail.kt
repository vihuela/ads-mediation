package com.cashcraft.ads.mediation.internal

import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.thinkup.core.api.AdError

internal data class SdkFailure(val code: String?, val detail: String?)

/** TopOn includes its own code/description and the underlying network's platformCode/platformMsg. */
internal fun AdError.analyticsErrorDetail(): String =
    runCatching { getFullErrorInfo() }.getOrElse { "code=$code desc=$desc" }

/** Keep mediation errors, but never upload ResponseInfo credentials or extras. */
internal fun LoadAdError.analyticsErrorDetail(): String = runCatching {
    buildString {
        append("code=").append(code).append(" message=").append(message)
        responseInfo?.responseId?.let { append(" response_id=").append(it) }
        responseInfo?.adSourceResponses?.forEach { source ->
            val error = source.adError ?: return@forEach
            append("; source=").append(source.name)
                .append(" source_id=").append(source.id)
                .append(" instance_id=").append(source.instanceId)
                .append(" adapter=").append(source.adapterClassName)
                .append(" domain=").append(error.domain)
                .append(" code=").append(error.code)
                .append(" message=").append(error.message)
        }
    }
}.getOrElse { "code=$code message=$message" }

internal fun FullScreenContentError.analyticsErrorDetail(): String = runCatching {
    buildString {
        append("code=").append(code).append(" message=").append(message)
        mediationAdError?.let {
            append("; mediation_domain=").append(it.domain)
                .append(" mediation_code=").append(it.code)
                .append(" mediation_message=").append(it.message)
        }
    }
}.getOrElse { "code=$code message=$message" }
