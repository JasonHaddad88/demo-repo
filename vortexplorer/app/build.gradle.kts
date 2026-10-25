import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI numbers each build so a newer APK always installs over an older one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.vortex.explorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vortex.explorer"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // A convenience key, committed on purpose so every build — local or
        // CI — carries the same signature and installs as an update. It is
        // not a secret; see README before distributing beyond your own phone.
        create("vortex") {
            storeFile = file("vortexplorer.jks")
            storeType = "PKCS12"
            storePassword = "vortexplorer"
            keyAlias = "vortexplorer"
            keyPassword = "vortexplorer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vortex")
        }
        debug {
            signingConfig = signingConfigs.getByName("vortex")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Multi-release and licence files from the JVM libraries (smbj,
            // Bouncy Castle, slf4j) that Android neither needs nor tolerates twice.
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.EC",
                "META-INF/OSGI-INF/**",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
