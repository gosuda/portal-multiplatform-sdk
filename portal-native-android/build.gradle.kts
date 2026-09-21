import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.gosuda"
version = libs.versions.portal.sdk.get()

val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val buildAndroidEngine = tasks.register<Exec>("buildAndroidEngine") {
    group = "build"
    description = "Builds Android JNI libraries from the repository's Go bridge."
    inputs.dir(rootProject.file("native/bridge"))
    inputs.file(rootProject.file("scripts/build-android-engine.sh"))
    inputs.file(rootProject.file("native/source-lock.json"))
    inputs.file(rootProject.file("native/include/portaltunnel.h"))
    inputs.files(
        rootProject.file("scripts/verify-android-engine.py"),
        rootProject.file("scripts/native-provenance.py"),
    )
    outputs.dir(generatedJniLibs)
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("scripts/build-android-engine.sh").absolutePath)
}

android {
    namespace = "org.gosuda.portal.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Only ABIs for which libportaltunnel.so is shipped.
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(generatedJniLibs.get().asFile))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val androidSdkDirectory = providers.provider {
    androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath
}
buildAndroidEngine.configure {
    environment("ANDROID_HOME", androidSdkDirectory)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach {
        dependsOn(buildAndroidEngine)
    }

tasks.matching { it.name.startsWith("bundle") && it.name.endsWith("Aar") }
    .configureEach {
        dependsOn(buildAndroidEngine)
    }



mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("SIGNING_KEY").isPresent
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "portal-native-android", version.toString())

    pom {
        name = "Portal Native Engine (Android)"
        description = "Reproducibly built libportaltunnel JNI binaries consumed by the Portal Multiplatform SDK Android target."
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
