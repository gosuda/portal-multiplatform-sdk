package org.gosuda.portal.sample.content

import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.explainer.ExplainerContent
import org.gosuda.portal.sample.content.minecraft.MinecraftContent
import org.gosuda.portal.sample.content.ondevice.OnDeviceModelContent
import org.gosuda.portal.sample.content.snake.SnakeGameContent
import java.io.File
import java.io.InputStream

/**
 * One publishable thing the sample can serve through a tunnel — the desktop
 * analogue of the Android `PublishableContent`. A content owns a local
 * payload (bundled assets or a loopback server) and the [PortalConfig]
 * fields that expose it (`static_dir` for sites, `target_addr`/`tcp` for
 * local servers). Everything else — relays, identity, metadata — stays
 * user-controlled.
 */
interface PublishableContent {
    /** Stable id persisted across restarts. */
    val id: String

    /** Picker label. */
    val title: String

    /** One-line picker description. */
    val summary: String

    /** Extra line shown under the summary (e.g. the loopback endpoint). */
    val detail: String? get() = null

    /**
     * Starts the local payload. Static sites extract assets; servers bind
     * their loopback port. Called before the tunnel opens; [stop] is always
     * paired with it — on start failure and on session end.
     */
    suspend fun start()

    /** Releases the local payload. Idempotent. */
    fun stop()

    /**
     * The intent-factory config that exposes this content's payload
     * (`PortalConfig.staticSite` for sites, `http`/`tcp` for servers).
     * Called after [start]; editor fields are merged on top by the caller.
     */
    fun baseConfig(): PortalConfig
}

/** Every content the sample can publish, in picker order. */
object SampleContents {
    val all: List<PublishableContent> = listOf(
        SnakeGameContent,
        ExplainerContent,
        OnDeviceModelContent,
        MinecraftContent
    )

    fun byId(id: String): PublishableContent = all.first { it.id == id }
}

/**
 * Per-app working directory for extracted assets and the identity file —
 * the desktop analogue of `Context.filesDir`. Lives under the same per-OS
 * application directory the SDK uses for identity.
 */
object SampleEnv {
    val appDir: File by lazy {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        val home = System.getProperty("user.home") ?: "."
        val base = when {
            os.startsWith("windows") -> System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"
            os.startsWith("mac") || os.startsWith("darwin") -> "$home/Library/Application Support"
            else -> System.getenv("XDG_STATE_HOME") ?: "$home/.local/state"
        }
        File(base, "org.gosuda.portal.sample.desktop").apply { mkdirs() }
    }

    /** Extracts a classpath resource directory into [appDir]/portal-public/<name>. */
    fun extractResources(resourceDir: String, outName: String = resourceDir): File {
        val out = File(appDir, "portal-public/$outName")
        out.mkdirs()
        // Classpath resources can't be listed as a directory; enumerate the
        // known files. Each content declares its own file list.
        return out
    }

    /** Copies one classpath resource into [outDir]. */
    fun copyResource(resourcePath: String, outDir: File, name: String) {
        val stream: InputStream? = SampleEnv::class.java.getResourceAsStream(resourcePath)
        requireNotNull(stream) { "missing classpath resource: $resourcePath" }
        stream.use { input -> File(outDir, name).outputStream().use { input.copyTo(it) } }
    }
}
