package com.cashcraft.ads.mediation.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cashcraft.ads.mediation.AdPlatform
import com.cashcraft.ads.mediation.AdsBannerView
import com.cashcraft.ads.mediation.BannerRequest
import com.cashcraft.ads.mediation.BannerSize
import com.cashcraft.ads.mediation.BannerState

/** Owns one Banner View for this Activity, page owner, and request value. */
@Composable
fun AdsBanner(
    request: BannerRequest,
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    active: Boolean = true,
    visible: Boolean = true,
    onState: (BannerState) -> Unit = {},
) {
    if (LocalInspectionMode.current) {
        Box(modifier.fillMaxWidth().heightIn(min = 50.dp), contentAlignment = Alignment.Center) {
            BasicText("Banner preview")
        }
        return
    }

    val activity = LocalContext.current.findActivity()
    val latestOnState = rememberUpdatedState(onState)
    val identity = Triple(activity, lifecycleOwner, request)
    val latestIdentity = rememberUpdatedState(identity)
    val reportState: (BannerState) -> Unit = { state ->
        // A replaced View can release after its successor has already reported its initial state.
        if (latestIdentity.value == identity) latestOnState.value(state)
    }
    key(identity) {
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = {
                AdsBannerView(activity, lifecycleOwner, request, active, reportState).apply {
                    visibility = if (visible) View.VISIBLE else View.INVISIBLE
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
            },
            update = { view ->
                view.onState = reportState
                // Deactivate before revealing; hide before activating. Neither combined update
                // may transiently make a stopped/hidden banner eligible for a request.
                if (!active) view.setActive(false)
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (active) view.setActive(true)
            },
            onRelease = { it.destroy() },
        )
    }
}

private fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("AdsBanner requires an Activity context")
}

@Preview(showBackground = true)
@Composable
private fun AdsBannerPreview() {
    AdsBanner(BannerRequest(AdPlatform.ADMOB, "preview", "preview", BannerSize.Standard320x50))
}
