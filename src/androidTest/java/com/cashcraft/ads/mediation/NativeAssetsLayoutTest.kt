package com.cashcraft.ads.mediation

import android.content.Context
import android.test.AndroidTestCase
import com.cashcraft.ads.mediation.internal.nativeads.createDefaultNativeLayout

/** 仅验证布局工厂契约，不请求广告；沿用 Android 内置测试框架。 */
@Suppress("DEPRECATION")
class NativeAssetsLayoutTest : AndroidTestCase() {
    fun testLegacyConstructorAndFactoryRemainCompatible() {
        var receivedContext: Context? = null
        val factory: (Context) -> NativeLayoutBinding = {
            receivedContext = it
            createDefaultNativeLayout(it)
        }
        val layout = NativeLayout.Custom(factory = factory)
        assertSame(factory, layout.factory)
        val first = layout.factory(context)
        val second = layout.create(context, NativeAssets(headline = "真实标题"))
        assertSame(context, receivedContext)
        assertNotSame(first.root, second.root)
        first.validate()
        second.validate()
        NativeLayout.Custom { createDefaultNativeLayout(it) }.create(context, NativeAssets()).validate()
    }

    fun testWithAssetsReceivesCurrentContextAndSnapshotAndCreatesFreshTrees() {
        val assets = NativeAssets(headline = "真实标题", mediaType = NativeMediaType.VIDEO)
        var receivedContext: Context? = null
        var receivedAssets: NativeAssets? = null
        val layout = NativeLayout.Custom.withAssets { currentContext, currentAssets ->
            receivedContext = currentContext
            receivedAssets = currentAssets
            createDefaultNativeLayout(currentContext)
        }
        val first = layout.create(context, assets)
        val second = layout.create(context, assets)
        assertSame(context, receivedContext)
        assertSame(assets, receivedAssets)
        assertNotSame(first.root, second.root)
        first.validate()
        second.validate()

        // 旧 factory 签名没有素材参数；直接调用时只提供空快照。
        layout.factory(context).validate()
        assertEquals(NativeAssets(), receivedAssets)
    }
}
