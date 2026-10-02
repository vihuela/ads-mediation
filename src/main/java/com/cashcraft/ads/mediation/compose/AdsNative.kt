package com.cashcraft.ads.mediation.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cashcraft.ads.mediation.AdsNativeView
import com.cashcraft.ads.mediation.NativeLayout
import com.cashcraft.ads.mediation.NativeRequest
import com.cashcraft.ads.mediation.NativeState
import com.cashcraft.ads.mediation.NativeRetentionPolicy

@Composable
fun AdsNative(
    request: NativeRequest,
    modifier: Modifier = Modifier,
    layout: NativeLayout = NativeLayout.Default,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    active: Boolean = true,
    visible: Boolean = true,
    retryToken: Int = 0,
    onStateChanged: (NativeState) -> Unit = {},
    retentionPolicy: NativeRetentionPolicy = NativeRetentionPolicy.DESTROY_ON_HIDE,
) {
    if (LocalInspectionMode.current) {
        NativePreviewPlaceholder(modifier)
        return
    }

    val context = LocalContext.current
    val activity = context.findActivity()
        ?: error("AdsNative requires an Activity-backed Context")
    val connection = remember { NativeViewConnection(retryToken) }

    androidx.compose.runtime.key(activity, lifecycleOwner, request, layout, retentionPolicy) {
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                // key 更换时新 factory 可能先于旧 onRelease；先结束本组件的旧配置。
                connection.view?.destroy()
                connection.retryToken = retryToken
                val activity = viewContext.findActivity()
                    ?: error("AdsNative requires an Activity-backed Context")
                AdsNativeView(
                    activity = activity,
                    lifecycleOwner = lifecycleOwner,
                    request = request,
                    layout = layout,
                    retentionPolicy = retentionPolicy,
                    active = active,
                    visible = visible,
                    onStateChanged = onStateChanged,
                ).also { connection.view = it }
            },
            update = { nativeView ->
                nativeView.update(
                    active = active,
                    visible = visible,
                    onStateChanged = onStateChanged,
                )
                if (connection.retryToken != retryToken) {
                    connection.retryToken = retryToken
                    nativeView.retry()
                }
            },
            onReset = null,
            onRelease = { released ->
                released.release()
                if (connection.view === released) connection.view = null
            },
        )
    }
}

// 保留旧 Compose 接入的尾随 lambda 调用形式。
@Composable
fun AdsNative(
    request: NativeRequest,
    modifier: Modifier = Modifier,
    layout: NativeLayout = NativeLayout.Default,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    active: Boolean = true,
    visible: Boolean = true,
    retryToken: Int = 0,
    onStateChanged: (NativeState) -> Unit,
) = AdsNative(
    request, modifier, layout, lifecycleOwner, active, visible, retryToken, onStateChanged,
    NativeRetentionPolicy.DESTROY_ON_HIDE,
)

private class NativeViewConnection(var retryToken: Int) {
    var view: AdsNativeView? = null
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper || current.baseContext === current) return null
        current = current.baseContext
    }
}

@Composable
private fun NativePreviewPlaceholder(modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .border(BorderStroke(1.dp, Color.Gray), RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        BasicText("Native ad preview")
    }
}

@Preview(showBackground = true)
@Composable
private fun AdsNativePreview() {
    AdsNative(
        request = NativeRequest(
            position = "preview",
        ),
    )
}
