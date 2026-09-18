import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.gosuda"
version = "0.1.0"

kotlin {
    explicitApi()
    android {
        namespace = "org.gosuda.portal"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    val xcf = XCFramework("PortalSDK")
    val appleTargets = listOf(iosArm64(), iosSimulatorArm64(), iosX64())
    appleTargets.forEach { target ->
        target.binaries.framework {
            baseName = "PortalSDK"
            isStatic = true
            xcf.add(this)
            // The Go bridge archive is built per target by
            // scripts/build-ios-engine.sh into native/ios/<target>/.
            val engineDir = rootProject.file("native/ios/${target.name}")
            if (engineDir.isDirectory) {
                linkerOpts("-L${engineDir.absolutePath}", "-lportaltunnel", "-framework", "Security")
            }
        }
        // Test binaries link the same archive so ios*Test exercises the
        // real cinterop path end-to-end.
        val engineDir = rootProject.file("native/ios/${target.name}")
        if (engineDir.isDirectory) {
            target.binaries.getTest(NativeBuildType.DEBUG).linkerOpts(
                "-L${engineDir.absolutePath}", "-lportaltunnel", "-framework", "Security"
            )
            // The archive is produced outside Gradle; declare it so relinks
            // happen when scripts/build-ios-engine.sh rebuilds it.
            tasks.matching {
                it.name == "linkDebugTest${target.name.replaceFirstChar(Char::uppercaseChar)}"
            }.configureEach {
                inputs.file(engineDir.resolve("libportaltunnel.a"))
            }
        }
        target.compilations.getByName("main").cinterops.create("portaltunnel") {
            defFile("src/nativeInterop/cinterop/portaltunnel.def")
            includeDirs(rootProject.file("native/include"))
        }
    }

    linuxX64 {
        compilations.getByName("main").cinterops.create("portaltunnel") {
            defFile("src/nativeInterop/cinterop/portaltunnel.def")
            includeDirs(rootProject.file("native/include"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(project(":portal-native-android"))
        }
        named("desktopMain") {
            dependencies {
                implementation(libs.jna)
                implementation(project(":portal-native-desktop"))
            }
        }
    }
}

// The linuxX64 test binary links a C stub implementing the portaltunnel ABI so
// the cinterop adapter is exercised end-to-end without the real Go engine.
val portalStubDir = layout.buildDirectory.dir("portalStub")
val buildPortalStub = tasks.register("buildPortalStub", Exec::class.java) {
    val outDir = portalStubDir.get().asFile
    inputs.file(rootProject.file("native/stub/portaltunnel_stub.c"))
    inputs.dir(rootProject.file("native/include"))
    outputs.dir(outDir)
    doFirst { outDir.mkdirs() }
    commandLine(
        "cc", "-fPIC", "-O2",
        "-I", rootProject.file("native/include").absolutePath,
        "-c", rootProject.file("native/stub/portaltunnel_stub.c").absolutePath,
        "-o", File(outDir, "portaltunnel_stub.o").absolutePath
    )
}

kotlin.targets.named(
    "linuxX64",
    org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java
).configure {
    binaries.getTest(NativeBuildType.DEBUG).apply {
        linkerOpts.add(File(portalStubDir.get().asFile, "portaltunnel_stub.o").absolutePath)
    }
}

// The desktop test binary loads a C stub implementing the portaltunnel ABI
// through JNA so the adapter is exercised end-to-end without the Go engine.
val desktopStubDir = layout.buildDirectory.dir("desktopStub")
val buildDesktopStub = tasks.register("buildDesktopStub", Exec::class.java) {
    val outDir = desktopStubDir.get().asFile
    inputs.file(rootProject.file("native/stub/portaltunnel_stub.c"))
    inputs.dir(rootProject.file("native/include"))
    outputs.dir(outDir)
    doFirst { outDir.mkdirs() }
    commandLine(
        "cc", "-fPIC", "-shared", "-O2",
        "-I", rootProject.file("native/include").absolutePath,
        rootProject.file("native/stub/portaltunnel_stub.c").absolutePath,
        "-o", File(outDir, "libportaltunnel_stub.so").absolutePath
    )
}

tasks.matching {
    it.name == "desktopTest" || it.name == "compileTestKotlinDesktop"
}.configureEach {
    dependsOn(buildDesktopStub)
}

// Pass the stub path to the desktop test JVM.
tasks.named("desktopTest", Test::class.java) {
    systemProperty(
        "portal.test.stubLibrary",
        File(desktopStubDir.get().asFile, "libportaltunnel_stub.so").absolutePath
    )
}

tasks.matching {
    it.name == "linkDebugTestLinuxX64" || it.name == "linuxX64Test"
}.configureEach {
    dependsOn(buildPortalStub)
}

// iOS test binaries link the Go bridge archive when it exists
// (scripts/build-ios-engine.sh → native/ios/<target>/). Without the
// archives the link/run tasks stay disabled; compileTestKotlinIos* still
// type-checks the test sources.
val iosEngineAvailable = listOf("iosArm64", "iosSimulatorArm64", "iosX64").all {
    rootProject.file("native/ios/$it/libportaltunnel.a").isFile
}
if (!iosEngineAvailable) {
    tasks.matching {
        it.name.startsWith("linkDebugTestIos") || it.name.startsWith("linkReleaseTestIos") ||
            (it.name.startsWith("ios") && it.name.endsWith("Test"))
    }.configureEach {
        enabled = false
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Sign only when release keys are configured; local Maven publishes skip it.
    if (providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("SIGNING_KEY").isPresent
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "portal-sdk", version.toString())

    pom {
        name = "Portal Multiplatform SDK"
        description = "Kotlin Multiplatform SDK for Portal Tunnel: expose app-local HTTP, TCP, UDP, and static content from Android, iOS, and desktop through Portal relays."
        inceptionYear = "2026"
        url = "https://github.com/gosuda/portal-multiplatform-sdk"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "gosuda"
                name = "gosuda"
                url = "https://github.com/gosuda"
            }
        }
        scm {
            url = "https://github.com/gosuda/portal-multiplatform-sdk"
            connection = "scm:git:git://github.com/gosuda/portal-multiplatform-sdk.git"
            developerConnection = "scm:git:ssh://git@github.com/gosuda/portal-multiplatform-sdk.git"
        }
    }
}
