package org.gosuda.portal.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.TunnelPhase
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import org.gosuda.portal.sample.llm.DownloadPhase
import org.gosuda.portal.sample.llm.LlmPassword
import org.gosuda.portal.sample.llm.ModelDownloadState
import org.gosuda.portal.sample.llm.ModelSpec

data class SampleActions(
    val onStart: (WorkbenchRequest) -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit,
    val onAwaitReady: () -> Unit,
    val onUpdateMetadata: (PortalMetadata) -> Unit,
    val onAddRelay: (String) -> Unit,
    val onRemoveRelay: (String) -> Unit,
    val onDiagnostics: () -> Unit,
    val onDownload: (String) -> Unit,
    val onCancelDownload: () -> Unit,
    val onDeleteModel: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onGeneratePassword: () -> Unit,
)

@Composable
fun PortalSampleTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color.White else Color.Black
    val paper = if (dark) Color(0xFF101010) else Color.White
    val muted = if (dark) Color(0xFFB5B5B5) else Color(0xFF595959)
    val line = if (dark) Color(0xFF424242) else Color(0xFFDADADA)
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = scheme.copy(primary = ink, onPrimary = paper, primaryContainer = ink,
            onPrimaryContainer = paper, secondary = ink, onSecondary = paper,
            secondaryContainer = line, onSecondaryContainer = ink, tertiary = ink,
            onTertiary = paper, tertiaryContainer = line, onTertiaryContainer = ink,
            background = paper, onBackground = ink,
            surface = paper, onSurface = ink, surfaceVariant = paper,
            onSurfaceVariant = muted, outline = line, outlineVariant = line,
            error = ink, onError = paper, errorContainer = line, onErrorContainer = ink,
            inverseSurface = ink, inverseOnSurface = paper, inversePrimary = paper,
            surfaceTint = Color.Transparent, surfaceBright = paper, surfaceDim = paper,
            surfaceContainer = paper, surfaceContainerHigh = paper,
            surfaceContainerHighest = line, surfaceContainerLow = paper,
            surfaceContainerLowest = paper, scrim = Color.Black),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)),
    ) { Surface(Modifier.fillMaxSize()) { content() } }
}

@Composable
fun SampleScreen(
    snapshot: PortalSnapshot?, lastError: String?, eventLog: List<String>,
    diagnostics: PortalDiagnostics?, busy: Boolean, localConfig: PortalConfig?,
    demoActivity: LocalDemo.Activity, actions: SampleActions,
    models: List<ModelSpec>, downloadState: ModelDownloadState, cachedModels: Set<String>,
    llmPassword: String,
) {
    var modeName by rememberPersistedString("workbench.mode", WorkbenchMode.STATIC.name)
    var modelId by rememberPersistedString("workbench.modelId", models.firstOrNull()?.id.orEmpty())
    val selectedModel = models.firstOrNull { it.id == modelId } ?: models.firstOrNull()
    var advanced by remember { mutableStateOf(false) }
    var liveTools by remember { mutableStateOf(false) }
    val mode = WorkbenchMode.entries.firstOrNull { it.name == modeName } ?: WorkbenchMode.STATIC
    var builtIn by rememberPersistedBoolean("workbench.builtIn", true)
    var directory by rememberPersistedString("workbench.directory", "")
    var target by rememberPersistedString("workbench.target", "127.0.0.1:8080")
    var firstPrefix by rememberPersistedString("workbench.firstPrefix", "/api/")
    var firstUpstream by rememberPersistedString("workbench.firstUpstream", "http://127.0.0.1:8081")
    var secondPrefix by rememberPersistedString("workbench.secondPrefix", "/")
    var secondUpstream by rememberPersistedString("workbench.secondUpstream", "http://127.0.0.1:8080")
    var domainPrefix by rememberPersistedString("workbench.domainPrefix", "desktop-demo")
    var description by rememberPersistedString("workbench.description", "Portal SDK desktop workbench")
    var tags by rememberPersistedString("workbench.tags", "sdk,desktop")
    var relays by rememberPersistedString("workbench.relays", "")
    var discovery by rememberPersistedBoolean("workbench.discovery", true)
    var hide by rememberPersistedBoolean("workbench.hide", true)
    var banMitm by rememberPersistedBoolean("workbench.banMitm", false)
    var relay by remember { mutableStateOf("") }
    var liveDescription by remember { mutableStateOf("") }
    var liveTags by remember { mutableStateOf("") }
    var liveOwner by remember { mutableStateOf("") }
    var liveHide by remember { mutableStateOf(true) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var modeMenuOpen by remember { mutableStateOf(false) }
    val running = snapshot != null && !snapshot.isTerminal
    val editable = !running && !busy
    val operable = running && !busy && snapshot.phase != TunnelPhase.STOPPING
    val copy: (String) -> Unit = { value ->
        actionError = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        }.exceptionOrNull()?.message
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 1060.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("PORTAL  /  DESKTOP", style = MaterialTheme.typography.labelLarge)
        Text("Your machine.\nOn the web.", style = MaterialTheme.typography.displayMedium)
        Text("One workbench. Five transports. Private, device-local AI.", style = MaterialTheme.typography.titleMedium)
        Text("Publish a local experience without moving its work to the cloud. Closing the window stops owned sessions; failed cleanup stays available for retry.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Panel("01  /  Publish") {
            Button(enabled = !busy && (running || ((discovery || commaValues(relays).isNotEmpty()) &&
                (mode != WorkbenchMode.LOCAL_LLM ||
                    (selectedModel?.id in cachedModels && LlmPassword.isValid(llmPassword))))), onClick = {
                if (running) actions.onStop() else actions.onStart(WorkbenchRequest(
                    mode = mode, modelId = selectedModel?.id.orEmpty(), builtIn = builtIn, directory = directory.trim(), target = target,
                    firstPrefix = firstPrefix, firstUpstream = firstUpstream,
                    secondPrefix = secondPrefix, secondUpstream = secondUpstream,
                    domainPrefix = domainPrefix, description = description, tags = commaValues(tags),
                    relays = commaValues(relays), discovery = discovery, hide = hide, banMitm = banMitm))
            }) { Text(if (busy) "Working…" else if (snapshot?.phase == TunnelPhase.STOPPING) "Retry stop" else if (running) "Stop" else "Publish and await active") }
            Box {
                OutlinedButton(onClick = { modeMenuOpen = true }, enabled = editable) {
                    Text(mode.title)
                }
                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                    WorkbenchMode.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.title) }, onClick = {
                            modeName = option.name
                            modeMenuOpen = false
                        })
                    }
                }
            }
            Text(mode.help)
            if (mode == WorkbenchMode.LOCAL_LLM) {
                Text("Download once. Keep locally.", style = MaterialTheme.typography.titleMedium)
                Text("Selecting a model never downloads it. Weights remain in application data across restarts and stop/publish. CPU-only inference; speed and memory needs vary by computer.")
                models.forEach { model ->
                    ModelCard(model, model.id == selectedModel?.id, model.id in cachedModels,
                        editable, downloadState, { modelId = model.id },
                        { actions.onDownload(model.id) }, actions.onCancelDownload,
                        { actions.onDeleteModel(model.id) })
                }
                OutlinedTextField(value = llmPassword, onValueChange = actions.onPasswordChange,
                    label = { Text("Password") }, enabled = editable, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = !LlmPassword.isValid(llmPassword),
                    supportingText = {
                        Text(if (LlmPassword.isValid(llmPassword)) "12–128 Unicode characters. A generated password is recommended."
                            else "Enter 12–128 Unicode characters.")
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onGeneratePassword, enabled = editable) { Text("Generate password") }
                    OutlinedButton(onClick = { copy(llmPassword) },
                        enabled = LlmPassword.isValid(llmPassword)) { Text("Copy password") }
                }
                Text("The password stays in this window's memory, is reused on the next publish, and resets on app relaunch. Stop before editing or generating a new one. Copy it only to a trusted recipient or the webpage.")
                Text("A cached model is required before publishing. Share the base URL separately and enter this password on the webpage. Keep this app open and the computer awake.")
            } else if (mode == WorkbenchMode.STATIC) {
                Field("Static directory (blank = bundled page)", directory, editable) { directory = it }
            } else {
                Toggle("Use built-in loopback demo", builtIn, editable) { builtIn = it }
                if (!builtIn) {
                    if (mode == WorkbenchMode.ROUTES) {
                        Field("Route 1 prefix", firstPrefix, editable) { firstPrefix = it }
                        Field("Route 1 upstream URL", firstUpstream, editable) { firstUpstream = it }
                        Field("Route 2 prefix", secondPrefix, editable) { secondPrefix = it }
                        Field("Route 2 upstream URL", secondUpstream, editable) { secondUpstream = it }
                    } else Field("Loopback target host:port", target, editable) { target = it }
                    Text("Custom service must already be listening. Loopback targets only; remote upstreams are intentionally not enabled.")
                }
            }
            Field("Domain prefix (optional)", domainPrefix, editable) { domainPrefix = it }
            Text("Requests a short relay alias for this publication, not a permanent reservation. If another identity owns the prefix, the canonical URL remains available. Blank uses the stored identity name; identity keys are unchanged.")
            OutlinedButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced configuration" else "Advanced / relays & metadata")
            }
            if (advanced) {
            Field("Description", description, editable) { description = it }
            Field("Tags (comma-separated)", tags, editable) { tags = it }
            Field("Relay URLs (comma-separated; blank uses discovery)", relays, editable) { relays = it }
            Toggle("Relay discovery", discovery, editable) { discovery = it }
            Toggle("Hide from listings (not authentication)", hide, editable) { hide = it }
            Toggle("Reject suspected TLS interception", banMitm, editable) { banMitm = it }
            Text("The SDK stores identity in its platform application directory. Editing metadata never generates or overwrites identity keys.")
            }
        }
        if (lastError != null || actionError != null || snapshot?.lastFailure != null || snapshot?.hasSecurityWarning == true) {
            Panel("Needs attention") {
                if (snapshot?.hasSecurityWarning == true) Text("Security warning: relay TLS interception suspected.", color = MaterialTheme.colorScheme.error)
                lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                snapshot?.lastFailure?.let { Text("${it.code} [${it.operation}, retryable=${it.retryable}]: ${it.message}") }
            }
        }
        Panel("02  /  Session") {
            SelectionContainer {
                Text("Phase: ${snapshot?.phase ?: "IDLE"}\nRevision: ${snapshot?.revision ?: 0}\nRequested: ${snapshot?.requestedCapabilities.orEmpty()}\nReady: ${snapshot?.readyCapabilities.orEmpty()}\nDropped events: ${snapshot?.droppedEventCount ?: 0}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onRefresh, enabled = operable) { Text("Refresh") }
                OutlinedButton(onClick = actions.onAwaitReady, enabled = operable) { Text("Await active") }
            }
            val browserUrl = if (localConfig?.tcp == true || localConfig?.udp == true) null
                else snapshot?.primaryPublicUrl
            browserUrl?.let { url ->
                Text(if (snapshot?.phase == TunnelPhase.ACTIVE) "Preferred HTTPS URL" else "Canonical HTTPS URL (session not active)")
                SelectionContainer { Text(url) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(url) }) { Text("Copy URL") }
                    OutlinedButton(onClick = {
                        actionError = runCatching {
                            check(URI(url).scheme == "https" || URI(url).scheme == "http") { "Not a browser URL" }
                            Desktop.getDesktop().browse(URI(url))
                        }.exceptionOrNull()?.message
                    }) { Text("Open in browser") }
                }
                if (mode == WorkbenchMode.LOCAL_LLM && running) {
                    Text("Open or copy the base URL above, then enter the configured password on the webpage. Sharing the URL alone does not grant inference access.")
                }
            }
            if (localConfig?.tcp != true && localConfig?.udp != true) {
                snapshot?.publicUrls.orEmpty().filter { it != browserUrl }.forEach { canonical ->
                    Text("Canonical identity-bound HTTPS URL")
                    SelectionContainer { Text(canonical) }
                }
            }
            snapshot?.relays.orEmpty().forEach { item ->
                Text("${item.relayUrl}: ${item.state}${item.error?.let { " — $it" } ?: ""}")
                item.tcpAddr?.let { Endpoint("Raw TCP endpoint (not a browser URL)", it, copy) }
                item.udpAddr?.let { Endpoint("Raw UDP endpoint (not a browser URL)", it, copy) }
            }
        }
        if (mode != WorkbenchMode.LOCAL_LLM) Panel("03  /  Local activity") {
            localConfig?.let { config ->
                val rawTcp = config.tcp
                val rawUdp = config.udp
                Text("Local: ${config.staticDir ?: config.targetAddr ?: config.udpAddr ?: "prefix routes"}")
                config.httpRoutes.orEmpty().forEachIndexed { index, route -> Text("${index + 1}. ${route.prefix} → ${route.upstream}") }
                val url = snapshot?.primaryPublicUrl?.trimEnd('/') ?: "https://YOUR-PUBLIC-HOST"
                val command = when {
                    rawTcp -> "printf 'portal-echo\\n' | nc HOST TCP_PORT"
                    rawUdp -> "printf 'portal-echo\\n' | nc -u -w 2 HOST UDP_PORT"
                    config.staticDir != null -> "curl -f '$url/'"
                    else -> "curl -f '$url/api/status'\ncurl -f -X POST '$url/webhook' -d 'hello=portal'"
                }
                SelectionContainer { Text(command) }
                Text(when {
                    rawTcp || rawUdp -> "Replace HOST/PORT with the corresponding raw relay endpoint above. Raw transport availability is relay-dependent."
                    config.staticDir != null -> "Static files are served directly by the native engine; no local HTTP request counter is involved."
                    builtIn -> "Built-in HTTP: /api/* returns JSON; other paths accept webhooks. Bodies, query strings and headers are never recorded."
                    else -> "Custom upstreams define their own paths and protocol behavior. The sample does not monitor their traffic."
                })
            }
            Text("Received operations: ${demoActivity.count} (last 40 below; TCP counts echoed chunks, UDP counts datagrams)")
            SelectionContainer { Text(demoActivity.lines.joinToString("\n").ifBlank { "No local requests received." }) }
        }
        OutlinedButton(onClick = { liveTools = !liveTools }) {
            Text(if (liveTools) "Hide SDK tools & diagnostics" else "SDK tools & diagnostics")
        }
        if (liveTools) {
        Panel("Live SDK operations") {
            Field("New description", liveDescription, operable) { liveDescription = it }
            Field("New tags (comma-separated)", liveTags, operable) { liveTags = it }
            Field("Owner label", liveOwner, operable) { liveOwner = it }
            Toggle("Hide listing", liveHide, operable) { liveHide = it }
            OutlinedButton(enabled = operable, onClick = { actions.onUpdateMetadata(PortalMetadata(
                description = liveDescription, tags = commaValues(liveTags), owner = liveOwner, hide = liveHide)) }) { Text("Update metadata") }
            Field("Relay URL", relay, operable) { relay = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = operable && relay.isNotBlank(), onClick = { actions.onAddRelay(relay) }) { Text("Add relay") }
                OutlinedButton(enabled = operable && relay.isNotBlank(), onClick = { actions.onRemoveRelay(relay) }) { Text("Remove relay") }
            }
            OutlinedButton(enabled = !busy, onClick = actions.onDiagnostics) { Text("Load diagnostics") }
            diagnostics?.let { value -> SelectionContainer { Text(value.toString()) } }
        }
        Panel("SDK capabilities and limits") {
            Text("HTTP/static/routes expose HTTPS URLs. Raw TCP and UDP expose separate relay addresses and require matching clients. Snapshot readiness confirms the relay listener, not the health of your custom upstream. Metadata, listing privacy, relay membership, diagnostics, retryable stop and bounded events are SDK features demonstrated here. Hiding is not access control. Security self-probing depends on relay support. ECH, x402 and overlay are unsupported by this engine. No identity secrets are displayed.")
        }
        Panel("Auxiliary SDK events (last 40)") {
            Text("Use the snapshot above for authoritative state; events are bounded and may be dropped.")
            SelectionContainer { Text(eventLog.joinToString("\n").ifBlank { "No events yet." }) }
        }
        }
    }
    }
}

@Composable
private fun ModelCard(
    model: ModelSpec, selected: Boolean, cached: Boolean, editable: Boolean,
    state: ModelDownloadState, select: () -> Unit, download: () -> Unit,
    cancel: () -> Unit, delete: () -> Unit,
) {
    val downloading = state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.VERIFYING
    val active = state.modelId == model.id
    Card(Modifier.fillMaxWidth(), border = BorderStroke(if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleMedium)
            Text(model.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${model.parameters} parameters  /  ${model.quantization}  /  ${model.downloadBytes / 1_048_576} MiB")
            Text("${model.languages}  /  License: ${model.license}")
            SelectionContainer { Text(model.source, style = MaterialTheme.typography.bodySmall) }
            Text(if (cached) "●  Cached on this computer" else "○  Not downloaded")
            if (active) {
                when (state.phase) {
                    DownloadPhase.DOWNLOADING -> {
                        LinearProgressIndicator(progress = {
                            if (state.totalBytes > 0) (state.receivedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
                        }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading ${state.receivedBytes / 1_048_576} / ${state.totalBytes / 1_048_576} MiB")
                    }
                    DownloadPhase.VERIFYING -> Text("Verifying file size and SHA-256…")
                    DownloadPhase.CANCELLED -> Text("Download cancelled. Cached models were kept.")
                    DownloadPhase.FAILED -> Text("Download failed: ${state.error ?: "Try again."}")
                    DownloadPhase.READY -> Text("Verified and ready to publish.")
                    DownloadPhase.IDLE -> Unit
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = editable, onClick = select) { Text(if (selected) "Selected" else "Select") }
                if (!cached) Button(enabled = editable && !downloading, onClick = download) { Text("Download") }
                if (active && downloading) OutlinedButton(onClick = cancel) { Text("Cancel download") }
                if (cached) OutlinedButton(enabled = editable && !downloading, onClick = delete) { Text("Remove cache") }
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = value, onCheckedChange = change, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Endpoint(label: String, endpoint: String, copy: (String) -> Unit) {
    Text(label)
    SelectionContainer { Text(endpoint) }
    OutlinedButton(onClick = { copy(endpoint) }) { Text("Copy endpoint") }
}
