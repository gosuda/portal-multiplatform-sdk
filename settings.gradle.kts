pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Keep staged Portal artifacts isolated from Central/Maven Local:
        // a missing publication must fail rather than mix release graphs.
        val sampleRepository = providers.gradleProperty("portal.samples.repository").orNull
        val useMavenLocal = providers.gradleProperty("portal.samples.useMavenLocal").orNull.toBoolean()
        if (sampleRepository != null || useMavenLocal) {
            exclusiveContent {
                forRepository {
                    if (sampleRepository != null) {
                        maven {
                            name = "portalSamples"
                            url = uri(sampleRepository)
                        }
                    } else {
                        mavenLocal()
                    }
                }
                filter {
                    includeGroup("io.github.gosuda")
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "portal-multiplatform-sdk"
include(":portal-sdk", ":portal-native-android", ":portal-native-desktop", ":portal-android-lifecycle", ":samples:android", ":samples:desktop")
