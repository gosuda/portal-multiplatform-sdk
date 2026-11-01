package org.gosuda.portal.sample.content

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalHTTPRoute

/** Each mode maps directly to an SDK intent factory; upstreams are user-owned. */
enum class PublishMode(val title: String, val help: String) {
    STATIC_SITE("Web games / static site", "Publish Snake, memory pairs, reaction testing and sloshing water fill with staticSite. Open the HTTPS URL to play; no game server or external downloads."),
    LOCAL_LLM("Device-local AI", "Download a compatible model once, then publish a private single-turn chat page. Inference runs on this device's CPU; no cloud or external daemon."),
    HTTP("HTTP upstream", "Built-in HTTP API and webhook receiver, or your own loopback HTTP server. Try GET /api and POST /webhook."),
    ROUTES("HTTP prefix routes", "Two built-in HTTP services or custom upstreams. The longest matching path prefix wins; / is the fallback."),
    TCP("TCP forward", "Built-in TCP echo listener or your own loopback listener. Use the relay TCP address, not the HTTPS URL; relay support is required."),
    UDP("UDP forward", "Built-in UDP echo listener or your own loopback listener. Use the relay UDP address; relay support is required.");
}

data class PublishRequest(
    val mode: PublishMode,
    val builtIn: Boolean,
    val options: PortalConfig,
    val target: String,
    val firstPrefix: String,
    val firstUpstream: String,
    val secondPrefix: String,
    val secondUpstream: String,
    val modelId: String? = null,
) {
    suspend fun config(context: Context, demoAddress: String?, secondDemoAddress: String? = null): PortalConfig {
        val address = demoAddress ?: target.trim()
        val base = when (mode) {
            PublishMode.STATIC_SITE -> {
                // Persistent app-private payload stays available even when publish rollback
                // fails. Never remove it while a client-owned STOPPING handle can use it.
                val directory = withContext(Dispatchers.IO) {
                    val dir = File(context.filesDir, "workbench-site")
                    check(dir.isDirectory || dir.mkdirs()) { "Cannot create the static-site directory" }
                    checkNotNull(context.assets.list("site")) { "Bundled arcade assets are missing" }.forEach { name ->
                        context.assets.open("site/$name").use { input ->
                            File(dir, name).outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    dir
                }
                PortalConfig.staticSite(directory.absolutePath)
            }
            PublishMode.HTTP, PublishMode.LOCAL_LLM -> PortalConfig.http(address)
            PublishMode.ROUTES -> PortalConfig.routes(listOf(
                PortalHTTPRoute(prefix = firstPrefix.trim(), upstream = demoAddress?.let { "http://$it" } ?: firstUpstream.trim()),
                PortalHTTPRoute(prefix = secondPrefix.trim(), upstream = if (demoAddress != null)
                    "http://${checkNotNull(secondDemoAddress)}" else secondUpstream.trim()),
            ))
            PublishMode.TCP -> PortalConfig.tcp(address)
            PublishMode.UDP -> PortalConfig.udp(address)
        }
        // Do not supply identity fields: PortalClient(context) persists its platform
        // default identity. A domain prefix changes only the requested relay alias.
        return base.copy(
            domainPrefix = options.domainPrefix,
            description = options.description,
            tags = options.tags,
            relays = options.relays,
            discovery = options.discovery,
            banMitm = options.banMitm,
            hide = options.hide,
        )
    }
}
