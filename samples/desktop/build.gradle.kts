import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

group = "org.gosuda.portal.sample"
version = "0.1.0"


dependencies {
    // Deliberately consume the published KMP coordinate. This prevents the
    // sample from hiding broken Maven metadata/native-runtime dependencies
    // behind a same-build project dependency.
    implementation(libs.portal.sdk)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}
compose.desktop {
    application {
        mainClass = "org.gosuda.portal.sample.MainKt"
        javaHome = javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // jlink does not infer modules used by the app or its dependencies.
            // Preferences stores form settings; HttpClient and HttpServer run headless demos.
            modules("java.prefs", "java.net.http", "jdk.httpserver", "jdk.crypto.ec")
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("samples/shared"))
            packageName = "PortalDesktopSample"
            packageVersion = "1.0.0"
            linux {
                iconFile.set(project.file("packaging/icon.png"))
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
            }
            macOS {
                iconFile.set(project.file("packaging/icon.icns"))
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Optional real relay round-trip (HTTP by default; -Pportal.smoke.mode=STATIC/ROUTES/TCP/UDP).
tasks.register<JavaExec>("smoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.SmokeKt")
    args("--relay", "--mode=" + providers.gradleProperty("portal.smoke.mode").getOrElse("HTTP"))
    if (!providers.gradleProperty("portal.smoke.discovery").getOrElse("true").toBooleanStrict()) {
        args("--no-discovery")
    }
    systemProperty("compose.application.resources.dir", rootProject.file("samples/shared/common").absolutePath)
}

// Deterministic configuration + real loopback services; no relay/native engine needed.
tasks.register<JavaExec>("workbenchSmoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.SmokeKt")
    systemProperty("compose.application.resources.dir", rootProject.file("samples/shared/common").absolutePath)
}
