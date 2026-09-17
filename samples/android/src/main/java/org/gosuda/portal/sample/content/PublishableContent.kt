package org.gosuda.portal.sample.content

import android.content.Context
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.explainer.ExplainerContent
import org.gosuda.portal.sample.content.ondevice.OnDeviceModelContent
import org.gosuda.portal.sample.content.snake.SnakeGameContent
import org.gosuda.portal.sample.content.minecraft.MinecraftContent

/**
 * One publishable thing the sample can serve through a tunnel.
 *
 * A content owns two concerns:
 * - a local payload (bundled assets or a loopback server it starts/stops
 *   with the tunnel), and
 * - the [PortalConfig] fields that expose it (`static_dir` for sites,
 *   `target_addr`/`tcp` for local servers).
 *
 * Everything else — relays, identity, metadata — stays user-controlled.
 */
interface PublishableContent {
    /** Stable id persisted across rotation. */
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
    suspend fun start(context: Context)

    /** Releases the local payload. Idempotent. */
    fun stop()

    /** Merges this content's exposure fields into [config]. */
    fun applyTo(config: PortalConfig, context: Context): PortalConfig
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
