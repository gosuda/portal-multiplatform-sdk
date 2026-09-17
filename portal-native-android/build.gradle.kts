import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.kimmandoo"
version = "0.1.0"

android {
    namespace = "org.gosuda.portal.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}



mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "portal-native-android", version.toString())

    pom {
        name = "Portal Native Engine (Android)"
        description = "Prebuilt libportaltunnel binaries and the JNI bridge consumed by the Portal Multiplatform SDK Android target."
        inceptionYear = "2026"
        url = "https://github.com/kimmandoo/portal-multiplatform-sdk"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "kimmandoo"
                name = "kimmandoo"
                url = "https://github.com/kimmandoo"
            }
        }
        scm {
            url = "https://github.com/kimmandoo/portal-multiplatform-sdk"
            connection = "scm:git:git://github.com/kimmandoo/portal-multiplatform-sdk.git"
            developerConnection = "scm:git:ssh://git@github.com/kimmandoo/portal-multiplatform-sdk.git"
        }
    }
}
