import java.security.MessageDigest

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.gosuda"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Verified engine binaries are produced by scripts/build-desktop-engine.sh
// into native/desktop/<target>/ and checked by scripts/verify-desktop-engine.sh.
// The task fails when a required artifact is missing so a published JAR can
// never silently ship an incomplete runtime matrix.
abstract class GenerateNativeIndex : DefaultTask() {

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeDesktopDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** When true, every matrix binary must exist (release/CI gate). */
    @get:Input
    abstract val requireComplete: Property<Boolean>

    private val requiredTargets = listOf(
        "linux-x64" to "libportaltunnel.so",
        "windows-x64" to "portaltunnel.dll",
        "macos-universal" to "libportaltunnel.dylib"
    )

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val index = StringBuilder("{\n  \"abi_version\": 1,\n  \"libraries\": {\n")
        var first = true
        val missing = mutableListOf<String>()
        for ((target, fileName) in requiredTargets) {
            val bin = nativeDesktopDir.get().dir(target).file(fileName).asFile
            if (!bin.isFile) {
                missing += bin.path
                continue
            }
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(bin.readBytes())
                .joinToString("") { "%02x".format(it) }
            val resDir = File(out, target)
            resDir.mkdirs()
            bin.copyTo(File(resDir, fileName), overwrite = true)
            if (!first) index.append(",\n")
            first = false
            index.append(
                "    \"$target\": {\"file\": \"$fileName\", \"sha256\": \"$sha\", \"size\": ${bin.length()}}"
            )
        }
        index.append("\n  }\n}\n")
        File(out, "index.json").writeText(index.toString())
        if (missing.isNotEmpty()) {
            val msg = "desktop engine binaries not packaged: ${missing.joinToString()}"
            if (requireComplete.get()) {
                throw GradleException("$msg (run scripts/build-desktop-engine.sh)")
            }
            logger.warn("$msg — packaging partial matrix (dev build)")
        }
    }
}

val generateNativeIndex = tasks.register<GenerateNativeIndex>("generateNativeIndex") {
    nativeDesktopDir.set(rootProject.layout.projectDirectory.dir("native/desktop"))
    outputDir.set(layout.buildDirectory.dir("generated/portal-native"))
    val publishingToCentral = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("publishToMavenCentral", ignoreCase = true) ||
            taskName.contains("MavenCentralRepository", ignoreCase = true)
    }
    requireComplete.set(
        providers.gradleProperty("portal.native.requireComplete")
            .map(String::toBoolean)
            .orElse(publishingToCentral)
    )
}

tasks.named("processResources", ProcessResources::class.java) {
    dependsOn(generateNativeIndex)
    from(layout.buildDirectory.dir("generated/portal-native")) {
        into("META-INF/portal-native")
    }
}

// Reproducible JAR: stable ordering and timestamps.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("SIGNING_KEY").isPresent
    ) {
        signAllPublications()
    }
    coordinates(group.toString(), "portal-native-desktop", version.toString())
    pom {
        name = "Portal Native Desktop Runtime"
        description = "Verified libportaltunnel native binaries for the Portal SDK desktop target."
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
