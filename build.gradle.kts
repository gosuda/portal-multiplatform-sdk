plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

// Isolated Maven repository used to verify the same artifacts consumers get.
// Samples never depend on SDK projects directly; publish here first, then
// build with -Pportal.samples.repository=<this directory>.
val sampleMavenRepository = layout.buildDirectory.dir("sample-maven")

subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories {
                maven {
                    name = "sample"
                    url = uri(sampleMavenRepository)
                }
            }
        }
    }
}

tasks.register("publishDesktopSdkToSampleRepository") {
    group = "publishing"
    description = "Publishes the desktop SDK/runtime Maven graph for sample consumer builds."
    dependsOn(
        ":portal-native-desktop:publishMavenPublicationToSampleRepository",
        ":portal-sdk:publishDesktopPublicationToSampleRepository",
        ":portal-sdk:publishKotlinMultiplatformPublicationToSampleRepository",
    )
}

tasks.register("publishAndroidSdkToSampleRepository") {
    group = "publishing"
    description = "Publishes the Android SDK/runtime/lifecycle Maven graph for sample consumer builds."
    dependsOn(
        ":portal-native-android:publishMavenPublicationToSampleRepository",
        ":portal-sdk:publishAndroidPublicationToSampleRepository",
        ":portal-sdk:publishKotlinMultiplatformPublicationToSampleRepository",
        ":portal-android-lifecycle:publishMavenPublicationToSampleRepository",
    )
}
