import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.kimmandoo"
version = "0.1.0"

android {
    namespace = "org.gosuda.portal.lifecycle"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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

dependencies {
    api(project(":portal-sdk"))
    implementation(libs.kotlinx.coroutines.android)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("SIGNING_KEY").isPresent
    ) {
        signAllPublications()
    }

    coordinates(group.toString(), "portal-android-lifecycle", version.toString())

    pom {
        name = "Portal Android Lifecycle"
        description = "Process-scoped PortalClient holder and foreground-service base so tunnels survive Activity recreation."
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
