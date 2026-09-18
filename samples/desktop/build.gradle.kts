import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

group = "org.gosuda.portal.sample"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":portal-sdk"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "org.gosuda.portal.sample.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PortalDesktopSample"
            packageVersion = "1.0.0"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Headless publish smoke: opens a real tunnel to a loopback server and
// fetches the public URL through the relay. `./gradlew :samples:desktop:smoke`.
tasks.register<JavaExec>("smoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.SmokeKt")
}

// Headless on-device content check: starts the Ollama/Markov loopback server
// and exercises its endpoints. `./gradlew :samples:desktop:ondeviceSmoke`.
tasks.register<JavaExec>("ondeviceSmoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.OnDeviceSmokeKt")
}
