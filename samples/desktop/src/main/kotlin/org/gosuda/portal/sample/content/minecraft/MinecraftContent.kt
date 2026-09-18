package org.gosuda.portal.sample.content.minecraft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent

/**
 * Playable Minecraft server content, exposed through `tcp=true` +
 * `target_addr`. The relay hands out a public TCP address
 * (`relay.tcpAddr`); a real Minecraft 1.21/1.21.1 client can add it,
 * join, and play on a flat creative platform.
 *
 * The loopback server ([MinecraftServer]) implements the full protocol
 * join path — status ping, login, configuration, play — plus movement,
 * creative block edits, chat, and player entities. The same port also
 * answers plain HTTP GETs with a status page.
 */
object MinecraftContent : PublishableContent {
    override val id = "minecraft"
    override val title = "Minecraft server"
    override val summary = "A real playable MC ${MinecraftProtocol.GAME_VERSION} server"
    override val detail = "tcp → 127.0.0.1:${MinecraftServer.PORT} (also answers HTTP)"

    private var scope: CoroutineScope? = null

    override suspend fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        MinecraftServer.start(s)
    }

    override fun stop() {
        MinecraftServer.stop()
        scope?.cancel()
        scope = null
    }

    override fun baseConfig(): PortalConfig =
        PortalConfig.tcp().copy(targetAddr = "127.0.0.1:${MinecraftServer.PORT}")
}
