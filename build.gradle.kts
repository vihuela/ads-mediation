import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

val publishedArtifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}
val githubPackagesUsername = providers.gradleProperty("github.packages.username")
    .orElse(providers.environmentVariable("GITHUB_PACKAGES_USERNAME"))
    .orElse(providers.provider { localProperties.getProperty("github.packages.username").orEmpty() })
val githubPackagesToken = providers.gradleProperty("github.packages.token")
    .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
    .orElse(providers.provider { localProperties.getProperty("github.packages.token").orEmpty() })

android {
    namespace = "com.cashcraft.ads.mediation"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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
}

configurations.configureEach {
    // GMA Next-Gen supplies the mediation API classes. Never package the legacy SDK beside it.
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}

dependencies {
    // AdMob 直连：GMA Next-Gen SDK；当前未接入 AdMob 聚合的第三方适配器。
    // 同时供下方 TopOn → AdMob 适配器使用，两条接入链路共用这一份 Google SDK。
    api(libs.ads.mobile.sdk)
    // GMA Next-Gen 1.2.1 still declares WorkManager 2.7.0/Room 2.2.5. With
    // current R8 those old Room rules can lose WorkDatabase_Impl's reflective
    // constructor and crash the host before Application.onCreate().
    implementation(libs.androidx.work.runtime)

    // TopOn TU 聚合核心。
    api(libs.topon.core)

    // TopOn → AdMob：GMA Next-Gen 适配器，底层 SDK 已在上方声明。
    implementation(libs.topon.gma.nextgen.adapter)

    // TopOn → Adx：适配器 + SmartDigimktTech SDK。
    implementation(libs.topon.adx.adapter)
    implementation(libs.topon.adx.sdk)

    // TopOn → Pangle：适配器 + Pangle SDK + 广告标识依赖。
    implementation(libs.topon.pangle.adapter)
    implementation(libs.pangle.sdk)
    implementation(libs.play.services.ads.identifier)

    // TopOn → Meta Audience Network：Facebook 适配器 + SDK + 注解依赖。
    implementation(libs.topon.facebook.adapter)
    implementation(libs.facebook.audience.network)
    implementation(libs.topon.androidx.annotation)

    // TopOn → Mintegral：适配器 + MBridge SDK + RecyclerView 依赖。
    implementation(libs.topon.mintegral.adapter)
    implementation(libs.mintegral.sdk)
    implementation(libs.androidx.recyclerview)

    // TopOn Tramini 插件（不是广告平台适配器）。
    implementation(libs.topon.tramini)

    // TopOn 集成保留的播放、网络支持库（不是额外的广告平台适配器）。
    implementation(libs.topon.media3.exoplayer)
    implementation(libs.topon.media3.ui)
    implementation(libs.topon.retrofit.kotlinx.converter)
    implementation(libs.topon.okhttp.logging)
    implementation(libs.okhttp)
    implementation(libs.topon.retrofit.scalars)
    implementation(libs.topon.retrofit.core)

    // TopOn TU 核心集成要求的 Android UI 支持库。
    implementation(libs.androidx.appcompat)
    implementation(libs.topon.androidx.browser)

    // 聚合层公共依赖：UMP 同意流程、Android 扩展、协程和 JSON 反射配置解析。
    implementation(libs.user.messaging.platform)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 单元测试。
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = publishedArtifactId
                version = project.version.toString()
                pom {
                    name.set("Ads Mediation")
                    description.set(
                        "Android mediation for AdMob Next-Gen, TopOn, and cached-price bidding.",
                    )
                    url.set("https://github.com/vihuela/ads-mediation")
                    licenses {
                        license {
                            name.set("Proprietary")
                            distribution.set("repo")
                        }
                    }
                    scm {
                        url.set("https://github.com/vihuela/ads-mediation")
                        connection.set("scm:git:https://github.com/vihuela/ads-mediation.git")
                        developerConnection.set("scm:git:ssh://git@github.com/vihuela/ads-mediation.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/vihuela/ads-mediation")
                credentials {
                    username = githubPackagesUsername.orNull
                    password = githubPackagesToken.orNull
                }
            }
        }
    }
}
