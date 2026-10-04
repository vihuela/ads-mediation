package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.AdFormat
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.Ads

/** Called after selecting a material; the business identity remains the original request. */
internal fun AdPolicyAttempt.logMaterial(format: AdFormat, platform: AdPlatform?) {
    Ads.nativeLog(request.position ?: "unknown", debug = true) {
        "ad_policy_material opportunity_id=$id sceneType=${request.sceneType?.configKey ?: "missing"}" +
            " actual_format=${format.analyticsValue} platform=$platform"
    }
}
