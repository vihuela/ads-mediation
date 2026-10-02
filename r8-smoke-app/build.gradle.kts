import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val nativeSmoke = providers.gradleProperty("nativeSmoke")
    .map { it.toBoolean() }
    .orElse(false)
    .get()

val smokeTestBuildType = providers.gradleProperty("smokeTestBuildType").orElse("debug").get()
require(smokeTestBuildType in listOf("debug", "release")) { "smokeTestBuildType must be debug or release" }

val nativeTestProperties = Properties()
providers.gradleProperty("nativeTestConfig").orNull?.let { path ->
    val configFile = file(path)
    require(configFile.isFile) { "nativeTestConfig file not found: $path" }
    configFile.inputStream().use(nativeTestProperties::load)
}

fun nativeTestValue(key: String): String = nativeTestProperties.getProperty(key).orEmpty().trim()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""

android {
    namespace = "com.cashcraft.ads.mediation.smoke"
    compileSdk = 36
    testBuildType = smokeTestBuildType

    defaultConfig {
        applicationId = "com.cashcraft.ads.mediation.smoke"
        if (nativeSmoke) applicationIdSuffix = ".native"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = if (nativeSmoke) {
            "android.test.InstrumentationTestRunner"
        } else {
            "com.cashcraft.ads.mediation.smoke.BannerContractInstrumentation"
        }
        manifestPlaceholders["admobApplicationId"] =
            "ca-app-pub-3940256099942544~3347511713"

        buildConfigField("boolean", "NATIVE_SMOKE", nativeSmoke.toString())
        val nativePlatform = providers.gradleProperty("nativePlatform").orElse("admob").get()
        require(nativePlatform in listOf("admob", "topon", "bidding")) { "nativePlatform must be admob, topon or bidding" }
        buildConfigField("String", "NATIVE_PLATFORM", buildConfigString(nativePlatform))
        buildConfigField("String", "NATIVE_TEST_TOPON_APP_ID", buildConfigString(nativeTestValue("applicationId")))
        buildConfigField("String", "NATIVE_TEST_TOPON_APP_KEY", buildConfigString(nativeTestValue("applicationKey")))
        buildConfigField("String", "NATIVE_TEST_NATIVE_PLACEMENT", buildConfigString(nativeTestValue("nativePlacement")))
        buildConfigField("String", "NATIVE_TEST_TEMPLATE_RATIO", buildConfigString(nativeTestValue("templateRatio")))
        buildConfigField("String", "NATIVE_TEST_APP_OPEN_PLACEMENT", buildConfigString(nativeTestValue("appOpenPlacement")))
        buildConfigField("String", "NATIVE_TEST_INTERSTITIAL_PLACEMENT", buildConfigString(nativeTestValue("interstitialPlacement")))
        buildConfigField("String", "NATIVE_TEST_REWARDED_PLACEMENT", buildConfigString(nativeTestValue("rewardedPlacement")))
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "TOPON_APP_ID", providers.gradleProperty("probeTopOnAppId").orElse("").get().asBuildConfigString())
            buildConfigField("String", "TOPON_APP_KEY", providers.gradleProperty("probeTopOnAppKey").orElse("").get().asBuildConfigString())
            buildConfigField("String", "TOPON_BANNER_PLACEMENT", providers.gradleProperty("probeTopOnBannerPlacement").orElse("").get().asBuildConfigString())
            buildConfigField("String", "TOPON_TEST_DEVICE_GAID", providers.gradleProperty("probeTopOnTestDeviceGaid").orElse("").get().asBuildConfigString())
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            if (smokeTestBuildType == "release") {
                proguardFiles("native-probe-rules.pro")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    lint {
        // Mintegral's NotificationUtil is flagged even when this smoke app declares the permission.
        disable += "NotificationPermission"
    }
}

dependencies {
    compileOnly(libs.ads.mobile.sdk)
    androidTestCompileOnly(files(
        "${android.sdkDirectory}/platforms/android-${android.compileSdk}/optional/android.test.base.jar",
        "${android.sdkDirectory}/platforms/android-${android.compileSdk}/optional/android.test.runner.jar",
    ))
    implementation(project(":"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.serialization.json)
    // The probe calls the SDKs directly; the library's implementation dependencies are not a public API.
    debugImplementation(libs.ads.mobile.sdk)
    debugImplementation(libs.topon.core)
    debugImplementation(libs.topon.gma.nextgen.adapter)
}
