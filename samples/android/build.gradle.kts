plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class PrepareLocalLlmNotice : DefaultTask() {
    @get:InputFile abstract val notice: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.file("local-llm-NOTICE").get().asFile
        check(output.parentFile.isDirectory || output.parentFile.mkdirs())
        notice.get().asFile.copyTo(output, overwrite = true)
    }
}

val prepareLocalLlmNotice = tasks.register<PrepareLocalLlmNotice>("prepareLocalLlmNotice") {
    notice.set(rootProject.file("samples/local-llm/NOTICE"))
    outputDirectory.set(layout.buildDirectory.dir("generated/localLlmAssets"))
}

android {
    namespace = "org.gosuda.portal.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.gosuda.portal.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                targets += "portal_sample_llm"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].assets.directories.add(rootProject.file("samples/shared/common").absolutePath)
    sourceSets["main"].kotlin.directories.add(rootProject.file("samples/shared/jvm").absolutePath)
    externalNativeBuild {
        cmake {
            path = rootProject.file("samples/local-llm/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLocalLlmNotice) { it.outputDirectory }
}

dependencies {
    // Consume exactly what application developers get from Maven. Keeping
    // samples off project() dependencies makes publication metadata part of
    // every sample build.
    implementation(libs.portal.sdk)
    implementation(libs.portal.android.lifecycle)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
