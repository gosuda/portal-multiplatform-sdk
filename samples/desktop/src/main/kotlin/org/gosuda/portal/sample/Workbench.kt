package org.gosuda.portal.sample

import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalHTTPRoute

/** The same intent configuration is used by the UI and headless smoke. */
enum class WorkbenchMode(val title: String, val help: String) {
    STATIC("Web games / static site", "Publish Snake, memory pairs, reaction testing and sloshing water fill. The native engine serves static files; no game server or downloads. Leave the directory blank for the bundled arcade."),
    HTTP("HTTP upstream", "HTTPS URL forwarding HTTP. Built-in mode serves a JSON API and webhook receiver; custom mode needs a running loopback HTTP server (host:port)."),
    ROUTES("HTTP routes", "HTTPS URL with prefix/upstream rules: the longest matching prefix wins. Built-in /api/ selects the JSON API; / selects a separate webhook receiver. Custom upstream URLs preserve request paths."),
    TCP("TCP forward", "Raw TCP endpoint. Built-in mode echoes bytes; custom mode needs a running loopback TCP listener. Use a TCP client, not a browser. Relay TCP support is required."),
    UDP("UDP forward", "Raw UDP endpoint. Built-in mode echoes datagrams (up to 4096 bytes); custom mode needs a running loopback UDP listener. Use a UDP client, not a browser. Relay UDP support is required.")
}

data class WorkbenchRequest(
    val mode: WorkbenchMode,
    val builtIn: Boolean = true,
    val directory: String = "",
    val target: String = "127.0.0.1:8080",
    val firstPrefix: String = "/api/",
    val firstUpstream: String = "http://127.0.0.1:8081",
    val secondPrefix: String = "/",
    val secondUpstream: String = "http://127.0.0.1:8080",
    val name: String = "desktop-workbench",
    val description: String = "Portal SDK desktop workbench",
    val tags: List<String> = listOf("sdk", "desktop"),
    val relays: List<String> = emptyList(),
    val discovery: Boolean = true,
    val hide: Boolean = true,
    val banMitm: Boolean = false,
) {
    fun config(): PortalConfig {
        val displayName = name.trim().ifBlank { null }
        val base = when (mode) {
            WorkbenchMode.STATIC -> PortalConfig.staticSite(directory, name = displayName)
            WorkbenchMode.HTTP -> PortalConfig.http(target.trim(), displayName)
            WorkbenchMode.ROUTES -> PortalConfig.routes(listOf(
                PortalHTTPRoute(firstPrefix.trim(), upstream = firstUpstream.trim()),
                PortalHTTPRoute(secondPrefix.trim(), upstream = secondUpstream.trim()),
            ), displayName)
            WorkbenchMode.TCP -> PortalConfig.tcp(target.trim(), displayName)
            WorkbenchMode.UDP -> PortalConfig.udp(target.trim(), displayName)
        }
        return base.copy(description = description.ifBlank { null }, tags = tags,
            relays = relays, discovery = discovery, hide = hide, banMitm = banMitm)
    }
}

fun commaValues(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)
