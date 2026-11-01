package org.gosuda.portal.sample

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalFailure
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.TunnelPhase
import org.gosuda.portal.sample.content.DemoActivity
import org.gosuda.portal.sample.content.PublishMode
import org.gosuda.portal.sample.content.PublishRequest

data class SampleActions(
    val onStart: (PublishRequest) -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit,
    val onAwaitReady: () -> Unit,
    val onUpdateMetadata: (PortalMetadata) -> Unit,
    val onAddRelay: (String) -> Unit,
    val onRemoveRelay: (String) -> Unit,
    val onDiagnostics: () -> Unit,
    val onKeepAlive: (Boolean) -> Unit,
)

@Composable
fun PortalSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

@Composable
fun SampleScreen(
    snapshot: PortalSnapshot?,
    lastError: String?,
    failure: PortalFailure?,
    eventLog: List<String>,
    diagnostics: PortalDiagnostics?,
    busy: Boolean,
    keepAlive: Boolean,
    demo: DemoActivity,
    localEndpoint: String?,
    actions: SampleActions,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var modeName by rememberPersistedString("workbench.mode", PublishMode.STATIC_SITE.name)
    val mode = PublishMode.entries.firstOrNull { it.name == modeName } ?: PublishMode.STATIC_SITE
    var builtIn by rememberPersistedBoolean("workbench.builtIn", true)
    var name by rememberPersistedString("workbench.name", "android-workbench")
    var description by rememberPersistedString("workbench.description", "Published from the Portal Android SDK workbench")
    var tags by rememberPersistedString("workbench.tags", "sdk,android")
    var relayUrls by rememberPersistedString("relays", "")
    var discovery by rememberPersistedBoolean("discovery", true)
    var banMitm by rememberPersistedBoolean("banMitm", false)
    var hide by rememberPersistedBoolean("hide", false)
    var httpTarget by rememberPersistedString("workbench.http", "127.0.0.1:8080")
    var tcpTarget by rememberPersistedString("workbench.tcp", "127.0.0.1:9090")
    var udpTarget by rememberPersistedString("workbench.udp", "127.0.0.1:9091")
    var firstPrefix by rememberPersistedString("workbench.route1.prefix", "/api")
    var firstUpstream by rememberPersistedString("workbench.route1.upstream", "http://127.0.0.1:8080")
    var secondPrefix by rememberPersistedString("workbench.route2.prefix", "/")
    var secondUpstream by rememberPersistedString("workbench.route2.upstream", "http://127.0.0.1:8081")
    var liveDescription by rememberSaveable { mutableStateOf("") }
    var liveTags by rememberSaveable { mutableStateOf("") }
    var liveOwner by rememberSaveable { mutableStateOf("") }
    var liveHide by rememberSaveable { mutableStateOf(false) }
    var relay by rememberSaveable { mutableStateOf("") }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    val running = snapshot != null && !snapshot.isTerminal
    val editable = !running && !busy
    val operable = running && !busy && snapshot.phase != TunnelPhase.STOPPING
    val missingRelay = !discovery && commaValues(relayUrls).isEmpty()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { value ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Portal endpoint", value))) }
    }
    val uriHandler = LocalUriHandler.current
    val publicHttpUrl = snapshot?.publicUrls?.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
    val target = when (mode) { PublishMode.TCP -> tcpTarget; PublishMode.UDP -> udpTarget; else -> httpTarget }

    Scaffold(
        bottomBar = {
            Column {
                Button(
                    onClick = {
                        if (running) actions.onStop()
                        else actions.onStart(PublishRequest(
                            mode = mode, builtIn = builtIn,
                            options = PortalConfig(
                                name = name.trim().ifBlank { null },
                                description = description.ifBlank { null }, tags = commaValues(tags),
                                relays = commaValues(relayUrls), discovery = discovery,
                                banMitm = banMitm, hide = hide,
                            ),
                            target = target, firstPrefix = firstPrefix, firstUpstream = firstUpstream,
                            secondPrefix = secondPrefix, secondUpstream = secondUpstream,
                        ))
                    },
                    enabled = !busy && (running || !missingRelay),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(when { busy -> "Working…"; snapshot?.phase == TunnelPhase.STOPPING -> "Retry stop"; running -> "Stop publishing"; else -> "Publish" })
                }
                NavigationBar {
                    listOf("Publish", "Session", "Capabilities").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("↗", "≋", "?")[index]) }, label = { Text(label) })
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Portal SDK workbench", style = MaterialTheme.typography.headlineMedium)
                Text("${snapshot?.phase ?: "IDLE"} · ${if (busy) "operation in progress" else "process-owned client"}")
            }
            if (lastError != null || failure != null || snapshot?.lastFailure != null || snapshot?.hasSecurityWarning == true || openError != null) {
                item {
                    Panel("Attention") {
                        if (snapshot?.hasSecurityWarning == true) Text("Sticky security warning: inspect relay trust before sharing.")
                        lastError?.let { Text(it) }
                        failure?.let { FailureDetails(it) }
                        if (snapshot?.lastFailure != failure) snapshot?.lastFailure?.let { FailureDetails(it) }
                        openError?.let { Text(it) }
                    }
                }
            }
            if (tab == 0) {
                item {
                    Panel("1 · Choose a capability") {
                        PublishMode.entries.forEach { candidate ->
                            OutlinedButton(onClick = { modeName = candidate.name }, enabled = editable,
                                modifier = Modifier.fillMaxWidth()) {
                                Text((if (candidate == mode) "✓ " else "") + candidate.title)
                            }
                        }
                        Text(mode.help)
                        if (mode != PublishMode.STATIC_SITE) {
                            Toggle("Use built-in loopback demo", builtIn, editable) { builtIn = it }
                            Text(if (builtIn) "Starts a real sample-only listener on an ephemeral loopback port. No installation or external server required."
                                else "Advanced: start your upstream on this Android device before publishing. Desktop localhost is not device localhost. Remote targets remain disabled.")
                        }
                        if (mode == PublishMode.ROUTES) {
                            Field("Route 1 prefix", firstPrefix, editable) { firstPrefix = it }
                            if (!builtIn) Field("Route 1 upstream URL", firstUpstream, editable) { firstUpstream = it }
                            Field("Route 2 prefix (fallback)", secondPrefix, editable) { secondPrefix = it }
                            if (!builtIn) Field("Route 2 upstream URL", secondUpstream, editable) { secondUpstream = it }
                        } else if (mode != PublishMode.STATIC_SITE && !builtIn) {
                            Field("Loopback host:port", target, editable) {
                                when (mode) { PublishMode.TCP -> tcpTarget = it; PublishMode.UDP -> udpTarget = it; else -> httpTarget = it }
                            }
                        }
                    }
                }
                item {
                    Panel("2 · Listing and relay configuration") {
                        Field("Display name", name, editable) { name = it }
                        Text("The SDK persists identity in app-private storage. Editing a display name never regenerates identity or guarantees a new public hostname.")
                        Field("Description", description, editable) { description = it }
                        Field("Tags (comma-separated)", tags, editable) { tags = it }
                        Toggle("Relay discovery", discovery, editable) { discovery = it }
                        Field("Relay URLs (comma-separated)", relayUrls, editable) { relayUrls = it }
                        if (missingRelay) Text("Choose discovery or supply at least one relay.")
                        Toggle("Reject suspected MITM relays", banMitm, editable) { banMitm = it }
                        Toggle("Hide from public listings", hide, editable) { hide = it }
                        Text("Hide is a listing preference, not authentication. Publishing exposes this content to anyone with its address.")
                    }
                }
                item {
                    Panel("Execution policy") {
                        Toggle("Optional foreground service", keepAlive, !busy, actions.onKeepAlive)
                        Text("Off by default. Opt in while the app is visible for notification-backed background execution. Android can still stop the process and limits data-sync service duration; this is not indefinite hosting. Rotation does not stop the session.")
                    }
                }
            }
            if (running || tab == 1) {
                item {
                    Panel("Live snapshot") {
                        Text("Session: ${snapshot?.sessionId ?: "none"}")
                        Text("Revision: ${snapshot?.revision ?: 0} · phase: ${snapshot?.phase ?: "IDLE"}")
                        Text("Requested: ${snapshot?.requestedCapabilities.orEmpty().joinToString()}")
                        Text("Ready: ${snapshot?.readyCapabilities.orEmpty().joinToString()}")
                        Text("Dropped auxiliary events: ${snapshot?.droppedEventCount ?: 0}")
                        if (snapshot?.phase == TunnelPhase.STOPPING) Text("Stop did not complete. Ownership and the built-in listener are retained. Retry stop; do not start a replacement session.")
                        snapshot?.publicUrls.orEmpty().forEach { url ->
                            Copyable(url) { copy(url) }
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                OutlinedButton(onClick = {
                                    try { uriHandler.openUri(url); openError = null }
                                    catch (e: RuntimeException) { openError = "No browser could open this address: ${e.message}" }
                                }) { Text("Open in browser") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = actions.onRefresh, enabled = operable) { Text("Refresh") }
                            OutlinedButton(onClick = actions.onAwaitReady, enabled = operable) { Text("Await active") }
                        }
                    }
                }
            }
            if (tab == 1) {
                item {
                    Panel("Demo activity · ${demo.count} requests / echo chunks") {
                        localEndpoint?.let { Text("Listening on $it (device loopback)") }
                        Text("Bodies, headers and query values are not retained. HTTP logs use known route labels only. History is bounded to 40 entries.")
                        val base = publicHttpUrl?.trimEnd('/') ?: "https://<public-host>"
                        val tcpAddress = snapshot?.relays?.firstOrNull { it.isReady && !it.tcpAddr.isNullOrBlank() }?.tcpAddr
                        val udpAddress = snapshot?.relays?.firstOrNull { it.isReady && !it.udpAddr.isNullOrBlank() }?.udpAddr
                        SelectionContainer {
                            Text(when (mode) {
                                PublishMode.STATIC_SITE -> "curl '$base/'"
                                PublishMode.HTTP, PublishMode.ROUTES -> "curl '$base/api'\ncurl -X POST '$base/webhook' -H 'Content-Type: text/plain' --data 'hello portal'"
                                PublishMode.TCP -> "printf 'hello portal\\n' | nc ${endpointArguments(tcpAddress)}"
                                PublishMode.UDP -> "printf 'hello portal\\n' | nc -u -w 2 ${endpointArguments(udpAddress)}"
                            }, fontFamily = FontFamily.Monospace)
                        }
                        demo.lines.takeLast(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item {
                    Panel("Relays and raw protocol endpoints") {
                        snapshot?.relays.orEmpty().forEach { status ->
                            Text("${status.relayUrl} · ${status.state}")
                            status.tcpAddr?.let { Copyable("TCP $it") { copy(it) } }
                            status.udpAddr?.let { Copyable("UDP $it") { copy(it) } }
                            status.failure?.let { Text("Failure: $it") }
                            status.error?.let { Text(it) }
                            OutlinedButton(onClick = { actions.onRemoveRelay(status.relayUrl) }, enabled = operable) { Text("Remove relay") }
                        }
                        Field("HTTPS relay URL", relay, operable) { relay = it }
                        OutlinedButton(onClick = { actions.onAddRelay(relay.trim()) }, enabled = operable && relay.isNotBlank()) { Text("Add relay") }
                    }
                }
                item {
                    Panel("Update live metadata") {
                        Field("Description", liveDescription, operable) { liveDescription = it }
                        Field("Tags (comma-separated)", liveTags, operable) { liveTags = it }
                        Field("Owner", liveOwner, operable) { liveOwner = it }
                        Toggle("Hide listing", liveHide, operable) { liveHide = it }
                        Button(onClick = { actions.onUpdateMetadata(PortalMetadata(description = liveDescription,
                            tags = commaValues(liveTags), owner = liveOwner, hide = liveHide)) }, enabled = operable) { Text("Apply metadata") }
                    }
                }
                item {
                    Panel("Diagnostics and bounded SDK event history") {
                        OutlinedButton(onClick = actions.onDiagnostics) { Text("Refresh diagnostics") }
                        diagnostics?.let { diag ->
                            Text("SDK ${diag.sdkVersion} · engine ${diag.engineVersion} · ABI ${diag.abiVersion} · wire ${diag.wireSchemaVersion}")
                            Text("Owned sessions ${diag.activeSessions} · orphan drops ${diag.droppedOrphanEvents} · aggregate drops ${diag.droppedAggregateEvents}")
                            diag.sessions.forEach { Text("${it.sessionId}: ${it.phase} rev=${it.revision} relay=${it.activeRelay ?: "none"}") }
                        }
                        Text("Events are auxiliary and may be dropped; the snapshot above is authoritative. Last 80 events survive Activity recreation.")
                        eventLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (tab == 2) {
                item {
                    Panel("What this SDK does") {
                        Text("Static site: serves app-private files directly, without an embedded web server.")
                        Text("HTTP: forwards a real loopback service. Built-in GET /api returns JSON; POST /webhook acknowledges a body without storing it (16 KiB maximum, Content-Length required).")
                        Text("Routes: the longest matching path prefix wins. Built-in /api selects the JSON API service and / selects a separate webhook receiver; custom mode supports two independent services.")
                        Text("TCP and UDP: forward raw traffic to a real listener. Built-in echo uses 4 KiB buffers; TCP limits each connection to 64 KiB with a 3-second idle timeout. Relay support is required; use raw relay endpoints.")
                        Text("publish waits for readiness. Application owns the PortalClient and listeners; screen observation never owns the tunnel. A failed stop remains retryable, including failed publish cleanup.")
                        Text("No global stop, identity editor, remote targets, game server or model installer. This UI covers the main modes, not every SDK option. Overlay is unsupported in v1; hide is not access control.")
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, enabled = enabled,
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
private fun Copyable(value: String, copy: () -> Unit) {
    SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) }
    OutlinedButton(onClick = copy) { Text("Copy address") }
}

@Composable
private fun FailureDetails(failure: PortalFailure) {
    Text("${failure.code} · operation=${failure.operation ?: "unknown"} · retryable=${failure.retryable}")
    Text(failure.message)
    failure.terminalPhase?.let { Text("Terminal phase: $it") }
    failure.readinessFailure?.let { Text("Original readiness failure: ${it.code} · ${it.message}") }
}

private fun commaValues(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)

private fun endpointArguments(address: String?): String {
    if (address == null) return "<relay-host> <port>"
    val host = address.substringBeforeLast(':').removeSurrounding("[", "]")
    return "$host ${address.substringAfterLast(':')}"
}
