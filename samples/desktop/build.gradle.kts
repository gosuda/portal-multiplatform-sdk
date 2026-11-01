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
    implementation(libs.kotlinx.serialization.json)
}

kotlin.sourceSets["main"].kotlin.srcDir(rootProject.file("samples/shared/jvm"))

val hostOs = System.getProperty("os.name").lowercase().let {
    when { it.contains("mac") -> "macos"; it.contains("win") -> "windows"; else -> "linux" }
}
val hostArch = System.getProperty("os.arch").let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val llmLibrary = rootProject.layout.buildDirectory.file("sample-llm/desktop/" + System.mapLibraryName("portal_sample_llm"))
val llmNotice = rootProject.layout.buildDirectory.file("sample-llm/desktop/NOTICE")
val buildSampleLlm = tasks.register<Exec>("buildSampleLlm") {
    group = "application"
    description = "Build the sample-owned CPU inference library; never download model weights."
    commandLine("bash", rootProject.file("scripts/build-sample-llm.sh").absolutePath, "desktop")
    inputs.dir(rootProject.file("samples/local-llm"))
    inputs.file(rootProject.file("scripts/build-sample-llm.sh"))
    outputs.file(llmLibrary)
    outputs.file(llmNotice)
}
val generatedAppResources = layout.buildDirectory.dir("generated/appResources")
val prepareSampleResources = tasks.register<Sync>("prepareSampleResources") {
    dependsOn(buildSampleLlm)
    from(rootProject.file("samples/shared/common")) { into("common") }
    from(llmLibrary) { into("$hostOs-$hostArch/native") }
    from(llmNotice) { into("$hostOs-$hostArch/native") }
    into(generatedAppResources)
}
compose.desktop {
    application {
        mainClass = "org.gosuda.portal.sample.MainKt"
        providers.gradleProperty("portal.models.directory").orNull?.let {
            jvmArgs += listOf("-Dportal.sample.models.dir=$it")
        }
        javaHome = javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // jlink does not infer modules used by the app or its dependencies.
            // Preferences stores form settings; HttpClient and HttpServer run headless demos.
            modules("java.prefs", "java.net.http", "jdk.httpserver", "jdk.crypto.ec")
            appResourcesRootDir.set(generatedAppResources)
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

// Compose combines common resources with the current OS/architecture folder.
// Nothing generated (native code or model weights) is checked into resources.
tasks.configureEach {
    if (name == "run" || name == "prepareAppResources" || name == "createDistributable" ||
        name == "createReleaseDistributable" || name.startsWith("package")) {
        dependsOn(prepareSampleResources)
    }
}
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("portal.sample.llm.library", llmLibrary.get().asFile.absolutePath)
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
    providers.gradleProperty("portal.smoke.domainPrefix").orNull?.let {
        args("--domain-prefix=$it")
    }
    providers.gradleProperty("portal.smoke.model").orNull?.let { args("--model=$it") }
    if (providers.gradleProperty("portal.smoke.mode").orNull.equals("LOCAL_LLM", ignoreCase = true)) {
        dependsOn(buildSampleLlm)
    }
    systemProperty("portal.sample.llm.library", llmLibrary.get().asFile.absolutePath)
    providers.gradleProperty("portal.models.directory").orNull?.let { systemProperty("portal.sample.models.dir", it) }
    systemProperty("compose.application.resources.dir", rootProject.file("samples/shared/common").absolutePath)
}

// Deterministic configuration + real loopback services; no relay/native engine needed.
tasks.register<JavaExec>("workbenchSmoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.SmokeKt")
    systemProperty("compose.application.resources.dir", rootProject.file("samples/shared/common").absolutePath)
}

// Model acquisition is deliberately separate from launch, publish, packaging and ALL smoke.
tasks.register<JavaExec>("downloadModel") {
    group = "application"
    description = "Explicitly download and verify -Pportal.model.id=<catalog-id> into the desktop app cache."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.SmokeKt")
    doFirst {
        val id = providers.gradleProperty("portal.model.id").orNull
            ?: throw GradleException("Supply -Pportal.model.id=<catalog-id>; modelCatalog lists available IDs.")
        args("--download-model=$id")
    }
    systemProperty("compose.application.resources.dir", rootProject.file("samples/shared/common").absolutePath)
    providers.gradleProperty("portal.models.directory").orNull?.let { systemProperty("portal.sample.models.dir", it) }
}

tasks.register<JavaExec>("modelCatalog") {
    group = "application"
    description = "List catalog model IDs and cached status without downloading weights."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.gosuda.portal.sample.SmokeKt")
    args("--list-models")
    systemProperty("compose.application.resources.dir", rootProject.file("samples/shared/common").absolutePath)
    providers.gradleProperty("portal.models.directory").orNull?.let { systemProperty("portal.sample.models.dir", it) }
}
