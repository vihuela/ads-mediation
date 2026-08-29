package com.cashcraft.ads.mediation.admob

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.cashcraft.ads.mediation.AdFormat
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Queue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads, but never consumes, the head price from the GMA Next-Gen 1.2.1 preload queue. */
internal object AdMobNextGenBidPrice {
    private const val CONFIG_ASSET = "google_next_gen_preload_reflection_paths.json"

    @Volatile
    private var repository: Map<String, ReflectionConfig>? = null

    fun initialize(context: Context) {
        if (repository != null) return
        synchronized(this) {
            if (repository != null) return
            repository = runCatching {
                val json = context.applicationContext.assets.open(CONFIG_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                parse(json)
            }.getOrDefault(emptyMap())
        }
    }

    fun peek(format: AdFormat, preloadId: String): Double? = runCatching {
        val version = MobileAds.getVersion().let {
            "${it.majorVersion}.${it.minorVersion}.${it.microVersion}"
        }
        val config = repository?.get(version) ?: return null
        val rootClass = Class.forName(config.sdkRootClass)
        val service = findNoArgMethod(rootClass, config.sdkRootMethod).invoke(null) ?: return null
        val provider = readField(service, config.providerField) ?: return null
        val registry = findNoArgMethod(provider.javaClass, config.providerGetMethod)
            .invoke(provider) ?: return null
        val mapField = config.formatMapFields[format.name] ?: return null
        val managers = readField(registry, mapField) as? Map<*, *> ?: return null
        val manager = managers[preloadId] ?: return null
        val queue = readField(manager, config.managerQueueField) as? Queue<*> ?: return null
        val queueItem = queue.peek() ?: return null
        val preloadResult = readField(queueItem, config.queueItemResultField) ?: return null
        val internalAd = readField(preloadResult, config.preloadResultAdField) ?: return null
        val adConfiguration = findNoArgMethod(
            internalAd.javaClass,
            config.internalAdConfigurationMethod,
        ).invoke(internalAd) ?: return null
        val price = readField(adConfiguration, config.configurationPriceField) ?: return null
        val valueMicros = (readField(price, config.priceValueMicrosField) as? Number)?.toLong()
            ?: return null
        valueMicros.coerceAtLeast(0L) / MICROS_PER_UNIT
    }.getOrNull()

    internal fun parse(json: String): Map<String, ReflectionConfig> {
        val versions = Json.parseToJsonElement(json).jsonObject.requiredObject("versions")
        return versions.mapValues { (_, versionElement) ->
            val preloading = versionElement.jsonObject.requiredObject("preloading")
            val sdkRoot = preloading.requiredObject("sdkRoot")
            val queuePath = preloading.requiredObject("queuePath")
            val priceFields = preloading.requiredObject("priceFields")
            ReflectionConfig(
                sdkRootClass = sdkRoot.requiredString("className"),
                sdkRootMethod = sdkRoot.requiredString("method"),
                providerField = sdkRoot.requiredString("providerField"),
                providerGetMethod = sdkRoot.requiredString("providerGetMethod"),
                formatMapFields = preloading.requiredObject("formatMapFields")
                    .mapValues { (_, value) -> value.jsonPrimitive.content },
                managerQueueField = queuePath.requiredString("managerQueueField"),
                queueItemResultField = queuePath.requiredString("queueItemResultField"),
                preloadResultAdField = queuePath.requiredString("preloadResultAdField"),
                internalAdConfigurationMethod =
                    queuePath.requiredString("internalAdConfigurationMethod"),
                configurationPriceField = queuePath.requiredString("configurationPriceField"),
                priceValueMicrosField = priceFields.requiredString("valueMicros"),
            )
        }
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        get(name)?.jsonObject ?: error("Missing object: $name")

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: error("Missing string: $name")

    private fun readField(target: Any, name: String): Any? = findField(target.javaClass, name).get(target)

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                return it.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}.$name")
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method {
        type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let {
            return it.apply { isAccessible = true }
        }
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}.$name()")
    }

    private const val MICROS_PER_UNIT = 1_000_000.0
}

internal data class ReflectionConfig(
    val sdkRootClass: String,
    val sdkRootMethod: String,
    val providerField: String,
    val providerGetMethod: String,
    val formatMapFields: Map<String, String>,
    val managerQueueField: String,
    val queueItemResultField: String,
    val preloadResultAdField: String,
    val internalAdConfigurationMethod: String,
    val configurationPriceField: String,
    val priceValueMicrosField: String,
)
