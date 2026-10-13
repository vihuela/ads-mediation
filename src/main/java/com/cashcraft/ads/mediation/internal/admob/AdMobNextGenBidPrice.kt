package com.cashcraft.ads.mediation.admob

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.cashcraft.ads.mediation.AdFormat
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Queue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Reads prices without consuming preload queues or registering loaded native ads for display. */
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
        readPreloadPrice(format, preloadId, version, repository.orEmpty())
    }.getOrNull()

    internal fun readPreloadPrice(
        format: AdFormat,
        preloadId: String,
        sdkVersion: String,
        configs: Map<String, ReflectionConfig>,
    ): Double? = runCatching {
        val config = configs[sdkVersion] ?: return null
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
        readPriceUsd(price, config)
    }.getOrNull()

    /** Loaded native ads use remax's versioned a -> b -> m path, not a preload queue. */
    fun nativePrice(ad: NativeAd): Double? = runCatching {
        val version = MobileAds.getVersion().let {
            "${it.majorVersion}.${it.minorVersion}.${it.microVersion}"
        }
        readNativePrice(ad, version, repository.orEmpty())
    }.getOrNull()

    internal fun readNativePrice(
        ad: Any,
        sdkVersion: String,
        configs: Map<String, ReflectionConfig>,
    ): Double? {
        // Never guess an obfuscated path on a different SDK version.
        val config = configs[sdkVersion] ?: return null
        return config.nativeAdPaths.firstNotNullOfOrNull { path ->
            runCatching {
                if (path.isEmpty() || path.any(String::isBlank)) return@runCatching null
                var leaf: Any = ad
                for (field in path) leaf = readField(leaf, field) ?: return@runCatching null
                readPriceUsd(leaf, config)
            }.getOrNull()
        }
    }

    /** A missing/invalid amount or currency is unknown, never a synthetic zero or assumed USD. */
    private fun readPriceUsd(price: Any, config: ReflectionConfig): Double? {
        val currencyField = config.priceCurrencyCodeField ?: return null
        val micros = readField(price, config.priceValueMicrosField) as? Long ?: return null
        val currency = readField(price, currencyField) as? String ?: return null
        return if (micros >= 0L && currency.trim().equals("USD", ignoreCase = true)) {
            micros / MICROS_PER_UNIT
        } else null
    }

    internal fun parse(json: String): Map<String, ReflectionConfig> {
        val versions = Json.parseToJsonElement(json).jsonObject.requiredObject("versions")
        return versions.mapValues { (_, versionElement) ->
            val version = versionElement.jsonObject
            val preloading = version.requiredObject("preloading")
            val sdkRoot = preloading.requiredObject("sdkRoot")
            val queuePath = preloading.requiredObject("queuePath")
            val priceFields = version.requiredObject("priceFields")
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
                priceCurrencyCodeField = priceFields["currencyCode"]?.jsonPrimitive?.content,
                nativeAdPaths = version["nativeAdPaths"]?.jsonArray
                    ?.map { it.jsonPrimitive.content.split("->") }.orEmpty(),
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
    val priceCurrencyCodeField: String? = null,
    val nativeAdPaths: List<List<String>> = emptyList(),
)
