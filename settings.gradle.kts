pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Samples consume released Maven coordinates, never Gradle project()
        // dependencies. CI/local release verification can point them at an
        // isolated staging repository with:
        // -Pportal.samples.repository=/absolute/path/or/file-uri
        providers.gradleProperty("portal.samples.repository").orNull?.let { repository ->
            maven {
                name = "portalSamples"
                url = uri(repository)
            }
        }
        if (providers.gradleProperty("portal.samples.useMavenLocal").orNull.toBoolean()) {
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "portal-multiplatform-sdk"
include(":portal-sdk", ":portal-native-android", ":portal-native-desktop", ":portal-android-lifecycle", ":samples:android", ":samples:desktop")
