import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

android {
    namespace = "com.cashcraft.ads.mediation.compose"
    compileSdk = 36

    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar() } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

dependencies {
    api(project(":"))
    api(libs.androidx.compose.ui)
    api(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "ads-mediation-compose"
                version = project.version.toString()
                pom {
                    name.set("Ads Mediation Compose")
                    description.set("Optional Compose wrapper for Ads Mediation Banner views.")
                    url.set("https://github.com/vihuela/ads-mediation")
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/vihuela/ads-mediation")
                credentials {
                    username = providers.gradleProperty("github.packages.username")
                        .orElse(providers.environmentVariable("GITHUB_PACKAGES_USERNAME"))
                        .orElse(providers.provider { localProperties.getProperty("github.packages.username").orEmpty() })
                        .orNull
                    password = providers.gradleProperty("github.packages.token")
                        .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                        .orElse(providers.provider { localProperties.getProperty("github.packages.token").orEmpty() })
                        .orNull
                }
            }
        }
    }
}
