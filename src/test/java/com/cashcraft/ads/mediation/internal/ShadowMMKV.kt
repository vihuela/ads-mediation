package com.cashcraft.ads.mediation.internal

import android.content.Context
import com.tencent.mmkv.MMKV
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow

/** 仅替代 JNI 存储；真实 MMKV 的持久化由设备测试覆盖。 */
@Implements(MMKV::class, isInAndroidSdk = false)
class ShadowMMKV {
    @Implementation fun containsKey(key: String): Boolean = values.containsKey(key)
    @Implementation fun decodeLong(key: String, fallback: Long): Long = values[key] as? Long ?: fallback
    @Implementation fun decodeString(key: String, fallback: String?): String? = values[key] as? String ?: fallback
    @Implementation fun encode(key: String, value: Long): Boolean { values[key] = value; return true }
    @Implementation fun encode(key: String, value: String?): Boolean { if (value == null) values.remove(key) else values[key] = value; return true }
    @Implementation fun clearAll() { values.clear() }

    companion object {
        val values = mutableMapOf<String, Any>()
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
        @JvmStatic @Implementation fun getRootDir(): String = "test-mmkv"
        @JvmStatic @Implementation fun initialize(context: Context): String = "test-mmkv"
        @JvmStatic @Implementation fun mmkvWithID(id: String): MMKV = Shadow.newInstanceOf(MMKV::class.java)
        @JvmStatic @Resetter fun reset() { values.clear() }
    }
}
