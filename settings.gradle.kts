pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "portal-multiplatform-sdk"
include(":portal-sdk", ":portal-native-android", ":portal-native-desktop", ":portal-android-lifecycle", ":samples:android", ":samples:desktop")
