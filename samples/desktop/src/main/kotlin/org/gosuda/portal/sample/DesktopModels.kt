package org.gosuda.portal.sample

import java.io.File
import org.gosuda.portal.sample.llm.ModelStore
import org.gosuda.portal.sample.llm.NativeLlm

/** UI and explicit CLI commands use the same persistent, user-owned cache. */
object DesktopModels {
    val resourceDirectory: File
        get() = File(checkNotNull(System.getProperty("compose.application.resources.dir")) {
            "Run with Gradle or the packaged launcher to locate sample resources."
        })

    val cacheDirectory: File by lazy {
        val override = System.getProperty("portal.sample.models.dir")
        if (!override.isNullOrBlank()) File(override) else {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            val root = when {
                os.contains("mac") -> File(home, "Library/Application Support")
                os.contains("win") -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
                else -> System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let(::File)
                    ?: File(home, ".local/share")
            }
            File(root, "org.gosuda.portal.sample.desktop/models")
        }
    }

    fun store() = ModelStore(cacheDirectory, File(resourceDirectory, "models.json").readText())

    fun asset(path: String): ByteArray {
        require(path in setOf("llm/index.html", "llm/llm.css", "llm/llm.js"))
        return File(resourceDirectory, path).readBytes()
    }

    fun loadNative() {
        val configured = System.getProperty("portal.sample.llm.library")?.takeIf(String::isNotBlank)
        val bundled = File(resourceDirectory, "native/${System.mapLibraryName("portal_sample_llm")}")
        NativeLlm.load(configured ?: bundled.takeIf(File::isFile)?.absolutePath)
    }
}
