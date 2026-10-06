package com.cashcraft.ads.mediation.internal

import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.internal.nativeads.NativeAdCache
import com.cashcraft.ads.mediation.internal.nativeads.NativeInventoryKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NativeInventorySourceTest {
    private val key = NativeInventoryKey(AdPlatform.TOPON, "source-regression-native")
    private val events = mutableListOf<AdEvent>()
    private val loads = ReflectionHelpers.getStaticField<MutableMap<NativeInventoryKey, AdLoadSession>>(
        NativeAdCache::class.java, "loadEvents")
    private val previousLoad = loads[key]

    @After fun restore() {
        loads.remove(key)
        previousLoad?.let { loads[key] = it }
    }

    @Test fun `inventory completion forwards source and response from metadata`() {
        complete(load("current")) { "Pangle" to "sdk-response" }

        val event = events.single()
        assertEquals(AdEventName.LOADED, event.name)
        assertEquals("Pangle", event.adSource)
        assertEquals("sdk-response", event.responseId)
        assertEquals("Pangle", event.analyticsParameters()["ad_source"])
        assertFalse(loads.containsKey(key))
    }

    @Test fun `missing or unreadable metadata keeps successful load without inventing source`() {
        val readers: List<() -> Pair<String?, String?>> = listOf(
            { null to null }, { error("SDK metadata unavailable") },
        )
        for (read in readers) {
            events.clear()
            complete(load("unknown"), metadata = read)
            val event = events.single()
            assertEquals(AdEventName.LOADED, event.name)
            assertNull(event.adSource)
            assertNull(event.responseId)
            assertFalse(event.analyticsParameters().containsKey("ad_source"))
        }
    }

    @Test fun `late and duplicate callbacks cannot read or consume replacement metadata`() {
        val old = load("old")
        val current = load("current")
        var reads = 0
        val read = { reads++; "Admob" to "current-response" }
        complete(old, metadata = read)
        assertTrue(events.isEmpty())
        assertSame(current, loads[key])
        assertEquals(0, reads)
        complete(current, metadata = read)
        complete(current, metadata = read)
        assertEquals(1, reads)
        assertEquals("current", events.single().requestId)
        assertEquals("current-response", events.single().responseId)
    }

    @Test fun `failed completion never reads successful inventory metadata`() {
        var reads = 0
        complete(load("failed"), success = false) { reads++; "Pangle" to "response" }
        val event = events.single()
        assertEquals(AdEventName.LOAD_FAIL, event.name)
        assertEquals("native_inventory_load_failed", event.reason)
        assertNull(event.adSource)
        assertEquals(0, reads)
    }

    private fun complete(load: AdLoadSession, success: Boolean = true, metadata: () -> Pair<String?, String?>) {
        ReflectionHelpers.callInstanceMethod<Unit>(NativeAdCache, "inventoryLoaded",
            from(NativeInventoryKey::class.java, key), from(AdLoadSession::class.java, load),
            from(Boolean::class.javaPrimitiveType!!, success), from(Function0::class.java, metadata))
    }

    private fun load(id: String) = AdLoadSession(
        listener = AdEventListener { events += it }, platform = AdPlatform.TOPON,
        mediationMode = AdMediationMode.TOPON, format = AdFormat.NATIVE,
        position = "native_inventory", adUnitId = key.id, sessionId = id, requestId = id,
        number = 1L, bufferSize = 1, startedAtMillis = 0L, clock = AdLoadClock { 10L },
    ).also { loads[key] = it }
}
