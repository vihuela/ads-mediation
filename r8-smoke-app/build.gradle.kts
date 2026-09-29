plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.cashcraft.ads.mediation.smoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cashcraft.ads.mediation.smoke"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.cashcraft.ads.mediation.smoke.BannerContractInstrumentation"
        manifestPlaceholders["admobApplicationId"] =
            "ca-app-pub-3940256099942544~3347511713"
    }

    buildTypes {
        debug {
            buildConfigField("String", "TOPON_APP_ID", providers.gradleProperty("probeTopOnAppId").orElse("").get().asBuildConfigString())
            buildConfigField("String", "TOPON_APP_KEY", providers.gradleProperty("probeTopOnAppKey").orElse("").get().asBuildConfigString())
            buildConfigField("String", "TOPON_BANNER_PLACEMENT", providers.gradleProperty("probeTopOnBannerPlacement").orElse("").get().asBuildConfigString())
            buildConfigField("String", "TOPON_TEST_DEVICE_GAID", providers.gradleProperty("probeTopOnTestDeviceGaid").orElse("").get().asBuildConfigString())
        }
        release {
            // Installable R8 verification APK only; this smoke application is never published.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":"))
    implementation(project(":ads-mediation-compose"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment)
    // The probe calls the SDKs directly; the library's implementation dependencies are not a public API.
    debugImplementation(libs.ads.mobile.sdk)
    debugImplementation(libs.topon.core)
    debugImplementation(libs.topon.gma.nextgen.adapter)
}
