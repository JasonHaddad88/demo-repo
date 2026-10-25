import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Android always uses UTF-8 file names. Without this, a JVM started under
    // the C locale mangles "café.jpg" into "caf?.jpg" before our code sees it.
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
    // Integration tests against real SMB/WebDAV servers run only when the
    // servers are described in the environment; see core/README notes.
    listOf(
        "VORTEX_WEBDAV_URL", "VORTEX_WEBDAV_USER", "VORTEX_WEBDAV_PASSWORD",
        "VORTEX_SMB_HOST", "VORTEX_SMB_PORT", "VORTEX_SMB_SHARE",
        "VORTEX_SMB_USER", "VORTEX_SMB_PASSWORD",
    ).forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
