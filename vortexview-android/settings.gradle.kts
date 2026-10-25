// VortexView for Android
//
// :core is plain Kotlin/JVM (the folder you're sorting, moving and undo, the
// zoom arithmetic) and builds anywhere. :app is the Android application and
// needs the Android SDK and Google's Maven repository, so it is only included
// where an SDK exists (CI, Android Studio). Everywhere else :core still builds
// and tests.

pluginManagement {
    // pluginManagement is evaluated on its own, so it repeats this check.
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        java.io.File(settingsDir, "local.properties").exists()
    repositories {
        if (hasAndroidSdk) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.11.1"
        id("org.jetbrains.kotlin.android") version "2.1.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
    }
}

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (hasAndroidSdk) {
            google()
        }
        mavenCentral()
    }
}

rootProject.name = "VortexView"
include(":core")
if (hasAndroidSdk) {
    include(":app")
}
