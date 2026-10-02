pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://artifact.bytedance.com/repository/pangle/")
        maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
        maven("https://jfrog.anythinktech.com/artifactory/overseas_sdk") {
            content {
                includeGroup("com.thinkup.sdk")
                includeGroup("com.smartdigimkttech.sdk")
                includeGroup("com.verbto.tools")
                includeGroup("com.hyperbid.tools")
            }
        }
    }
}

rootProject.name = "ads-mediation"
include(":r8-smoke-app")
