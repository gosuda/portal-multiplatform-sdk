package org.gosuda.portal.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalRelayStatus
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.TunnelPhase
import org.gosuda.portal.sample.content.PublishableContent
import org.gosuda.portal.sample.content.SampleContents
import org.gosuda.portal.sample.content.ondevice.OllamaModels
import org.gosuda.portal.sample.content.ondevice.OllamaSetup
import org.gosuda.portal.sample.content.ondevice.OnDeviceModelContent

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/** Action callbacks wired by [PortalApp]. */
data class SampleActions(
    val onStart: (PortalConfig, String) -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit,
    val onAwaitReady: () -> Unit,
    val onGenerateIdentity: () -> Unit,
    val onUpdateMetadata: (PortalMetadata) -> Unit,
    val onAddRelay: (String) -> Unit,
    val onRemoveRelay: (String) -> Unit,
    val onDiagnostics: () -> Unit,
    val onKeepAlive: (Boolean) -> Unit,
    val onModelChanged: () -> Unit,
)

private val Bg = Color(0xFF080F1D)
private val Surface1 = Color(0xFF111E30)
private val Surface2 = Color(0xFF1B2D42)
private val Cyan = Color(0xFF64DCEC)
private val Mint = Color(0xFFA0F0CC)
private val TextPrimary = Color(0xFFF0F5FC)
private val TextSecondary = Color(0xFFA7B8CE)
private val Danger = Color(0xFFFFA29B)
private val PortalColors = darkColorScheme(
    primary = Cyan,
    secondary = Mint,
    background = Bg,
    surface = Surface1,
    surfaceVariant = Surface2,
    onPrimary = Bg,
    onSecondary = Bg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    errorContainer = Color(0xFF39232C),
    onErrorContainer = Danger,
)

@Composable
fun PortalSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PortalColors, content = content)
}

private val DESTINATIONS = listOf("Publish", "Settings", "Activity")

@Composable
fun SampleScreen(
    snapshot: PortalSnapshot?,
    lastError: String?,
    identity: PortalIdentity?,
    eventLog: List<String>,
    diagnostics: PortalDiagnostics?,
    busy: Boolean,
    keepAlive: Boolean,
    actions: SampleActions
) {
    var destination by remember { mutableStateOf(0) }
    var name by rememberPersistedString("name", "snake-game")
    var description by rememberPersistedString("description", "Snake game served from this device")
    var tags by rememberPersistedString("tags", "game,snake")
    var relays by rememberPersistedString("relays", "")
    var discovery by rememberPersistedBoolean("discovery", true)
    var udp by rememberPersistedBoolean("udp", false)
    var tcp by rememberPersistedBoolean("tcp", false)
    var ech by rememberPersistedBoolean("ech", false)
    var banMitm by rememberPersistedBoolean("banMitm", false)
    var hide by rememberPersistedBoolean("hide", false)
    var liveDescription by rememberPersistedString("liveDescription", "")
    var liveTags by rememberPersistedString("liveTags", "")
    var liveOwner by rememberPersistedString("liveOwner", "")
    var liveHide by rememberPersistedBoolean("liveHide", false)
    var contentId by rememberPersistedString("contentId", "snake")
    var newRelay by remember { mutableStateOf("") }
    var activityPanel by remember { mutableStateOf("session") }
    val publishScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val activityScroll = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onDeviceEngineStatus by OnDeviceModelContent.engineStatus.collectAsState()
    val modelState by OllamaModels.state.collectAsState()
    val selectedModel by OllamaModels.selected.collectAsState()
    val daemonRunning by OllamaModels.daemonRunning.collectAsState()
    val ollamaSetup by OllamaSetup.state.collectAsState()
    var installedModels by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        OllamaModels.loadSelection()
        OllamaSetup.ensureRunning()
        OllamaModels.refresh()
        installedModels = OllamaModels.MODELS
            .filter { OllamaModels.isInstalled(it) }
            .map { it.id }.toSet()
    }

    val running = snapshot != null && !snapshot.isTerminal
    val editable = !running && !busy
    val operable = running && !busy && snapshot?.phase != TunnelPhase.STOPPING
    val missingRelay = !discovery && relays.split(',').none { it.isNotBlank() }
    val urls = if (running) snapshot?.publicUrls.orEmpty() else emptyList()
    val notify: (String) -> Unit = { message ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // ---- Sidebar -----------------------------------------------------
            Sidebar(
                destination = destination,
                onSelect = { destination = it },
                phase = snapshot?.phase,
                running = running,
                busy = busy,
                missingRelay = missingRelay,
                onPublishToggle = {
                    if (running) actions.onStop()
                    else actions.onStart(PortalConfig(
                        name = name.trim().ifBlank { null },
                        description = description.ifBlank { null },
                        tags = commaValues(tags), relays = commaValues(relays),
                        discovery = discovery, udp = udp, tcp = tcp,
                        ech = ech, banMitm = banMitm, hide = hide,
                    ), contentId)
                },
                stopping = snapshot?.phase == TunnelPhase.STOPPING
            )

            // ---- Content -----------------------------------------------------
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = when (destination) { 1 -> settingsScroll; 2 -> activityScroll; else -> publishScroll },
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PORTAL / from this device to the web", color = Cyan,
                                style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
                            Text(when (destination) { 1 -> "Settings"; 2 -> "Activity"; else -> "Publish" },
                                modifier = Modifier.padding(top = 6.dp), fontSize = 26.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (lastError != null || snapshot?.lastFailure != null || snapshot?.hasSecurityWarning == true) {
                    item {
                        Panel("Needs attention") {
                            if (snapshot?.hasSecurityWarning == true) {
                                Text("TLS interception suspected on a relay. Do not trust that endpoint; check the connection.", color = Danger)
                            }
                            snapshot?.lastFailure?.let { Text("${it.code}: ${it.message}", color = Danger) }
                            lastError?.let { Text(it, color = Danger) }
                        }
                    }
                }
                when (destination) {
                    0 -> publishContent(
                        snapshot, name, running, urls, contentId, editable,
                        modelState, selectedModel, onDeviceEngineStatus, daemonRunning,
                        keepAlive, notify,
                        onContentSelect = { contentId = it },
                        onPullModel = { OllamaModels.pull() },
                        onGoSettings = { destination = 1 }
                    )
                    1 -> settingsContent(
                        name, { name = it }, description, { description = it },
                        tags, { tags = it }, relays, { relays = it },
                        discovery, { discovery = it }, udp, { udp = it },
                        tcp, { tcp = it }, ech, { ech = it },
                        banMitm, { banMitm = it }, hide, { hide = it },
                        keepAlive, editable, missingRelay, running,
                        modelState, selectedModel, daemonRunning, ollamaSetup, installedModels,
                        actions, scope,
                        onInstalledChange = { installedModels = it }
                    )
                    2 -> activityContent(
                        snapshot, running, operable, editable, busy,
                        liveDescription, { liveDescription = it },
                        liveTags, { liveTags = it },
                        liveOwner, { liveOwner = it },
                        liveHide, { liveHide = it },
                        newRelay, { newRelay = it },
                        activityPanel, { activityPanel = it },
                        identity, eventLog, diagnostics, actions
                    )
                }
            }
        }
    }
}

// ---- Sidebar ------------------------------------------------------------------

@Composable
private fun Sidebar(
    destination: Int,
    onSelect: (Int) -> Unit,
    phase: TunnelPhase?,
    running: Boolean,
    busy: Boolean,
    missingRelay: Boolean,
    stopping: Boolean,
    onPublishToggle: () -> Unit,
) {
    Surface(
        color = Surface1,
        modifier = Modifier.width(232.dp).fillMaxHeight(),
        shadowElevation = 4.dp
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("PORTAL", color = Cyan, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, letterSpacing = 2.sp)
            Text("desktop sample", color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 20.dp))

            DESTINATIONS.forEachIndexed { index, label ->
                val selected = destination == index
                Surface(
                    color = if (selected) Cyan.copy(alpha = 0.14f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .selectable(selected = selected, onClick = { onSelect(index) }, role = Role.Tab)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DestinationIcon(index, selected)
                        Text(label, color = if (selected) Cyan else TextPrimary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Status + primary action pinned to the sidebar bottom.
            PhaseBadge(phase)
            Spacer(Modifier.height(10.dp))
            if (missingRelay && !running) {
                Text("Enable auto-discovery or enter a relay address.",
                    color = Danger, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Button(
                onClick = onPublishToggle,
                enabled = !busy && (running || !missingRelay),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Surface2 else Cyan,
                    contentColor = if (running) TextPrimary else Bg,
                ),
            ) {
                Text(when {
                    busy -> "Processing…"
                    stopping -> "Retry stop"
                    running -> "Stop publishing"
                    else -> "Publish"
                }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---- Destination content --------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.publishContent(
    snapshot: PortalSnapshot?,
    name: String,
    running: Boolean,
    urls: List<String>,
    contentId: String,
    editable: Boolean,
    modelState: OllamaModels.State,
    selectedModel: OllamaModels.ModelSpec,
    engineStatus: OnDeviceModelContent.EngineStatus,
    daemonRunning: Boolean,
    keepAlive: Boolean,
    notify: (String) -> Unit,
    onContentSelect: (String) -> Unit,
    onPullModel: () -> Unit,
    onGoSettings: () -> Unit,
) {
    item { PublishHero(snapshot?.nativeStatus?.name ?: name, snapshot?.phase, running) }
    item {
        Panel("What to publish") {
            SampleContents.all.forEach { content ->
                ContentChoice(content, contentId, editable,
                    modelState = if (content.id == "ondevice") modelState else null,
                    modelLabel = if (content.id == "ondevice") selectedModel.sizeLabel else null,
                    engineStatus = if (content.id == "ondevice") engineStatus else null,
                    daemonRunning = if (content.id == "ondevice") daemonRunning else null,
                    onDownloadModel = if (content.id == "ondevice") onPullModel else null
                ) { onContentSelect(it) }
            }
        }
    }
    item {
        Panel(if (urls.isEmpty()) "Public link" else "Share this link") {
            if (urls.isEmpty()) {
                Text(if (running) "The relay will send an address here. No link to share yet."
                    else "Publish to get a public address you can share.",
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("The address can change as relays join/leave. Share the current link.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                urls.forEachIndexed { index, url ->
                    Surface(color = Bg, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Public address ${index + 1}", color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(url, color = Mint,
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton("Copy link", onClick = {
                                    Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(StringSelection(url), null)
                                    notify("Copied public address ${index + 1}.")
                                }, modifier = Modifier.semantics { contentDescription = "Copy public address ${index + 1}" })
                                ActionButton("Open", onClick = {
                                    runCatching { Desktop.getDesktop().browse(URI(url)) }
                                        .onFailure { notify("Cannot open the address. Copy the link and check it in a browser.") }
                                }, modifier = Modifier.semantics { contentDescription = "Open public address ${index + 1} in browser" })
                            }
                        }
                    }
                }
            }
        }
    }
    item {
        Panel("Before you publish") {
            Text("This device serves the content. The app and network must stay connected for visitors to reach it.", color = TextSecondary)
            Text(if (keepAlive) "Keep-alive on · runs in the system tray."
                else "Keep-alive off · change it in Settings.",
                color = Mint, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onGoSettings) { Text("Review publish settings →") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.settingsContent(
    name: String, onName: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
    tags: String, onTags: (String) -> Unit,
    relays: String, onRelays: (String) -> Unit,
    discovery: Boolean, onDiscovery: (Boolean) -> Unit,
    udp: Boolean, onUdp: (Boolean) -> Unit,
    tcp: Boolean, onTcp: (Boolean) -> Unit,
    ech: Boolean, onEch: (Boolean) -> Unit,
    banMitm: Boolean, onBanMitm: (Boolean) -> Unit,
    hide: Boolean, onHide: (Boolean) -> Unit,
    keepAlive: Boolean,
    editable: Boolean,
    missingRelay: Boolean,
    running: Boolean,
    modelState: OllamaModels.State,
    selectedModel: OllamaModels.ModelSpec,
    daemonRunning: Boolean,
    ollamaSetup: OllamaSetup.State,
    installedModels: Set<String>,
    actions: SampleActions,
    scope: kotlinx.coroutines.CoroutineScope,
    onInstalledChange: (Set<String>) -> Unit,
) {
    item {
        Text(if (running) "Cannot edit start settings while publishing. Stop publishing, then edit. Live info can be changed in Activity."
            else "Configure what to publish, then use the Publish button in the sidebar.", color = TextSecondary)
    }
    item {
        Panel("Basics") {
            PortalField(name, onName, "Public name", "Becomes the address prefix (<name>.<relay>). Changing it creates a new identity on next publish.", editable)
            PortalField(description, onDescription, "Description", "A sentence describing the content. May appear in the public directory.", editable)
            PortalField(tags, onTags, "Tags", "Comma-separated. e.g. game, snake", editable)
            SettingRow("Keep alive in system tray", "Keeps the tunnel running when the window closes. Quit from the tray icon to stop.",
                keepAlive, editable, actions.onKeepAlive)
        }
    }
    item {
        Panel("Relay connection") {
            Text("Relays carry traffic between this device and visitors.", color = TextSecondary)
            SettingRow("Auto-discover relays", "Finds relays to use. Does not list your content in the public directory.",
                discovery, editable, onDiscovery)
            PortalField(relays, onRelays, "Relay address (manual)", "Comma-separated. At least one required when auto-discovery is off.", editable, missingRelay)
        }
    }
    item {
        Panel("Visibility & security") {
            SettingRow("Hide from public directory", "Reduces listing exposure. Not authentication or access control — anyone with the URL can connect.",
                hide, editable, onHide)
            SettingRow("Use ECH", "Requests TLS ClientHello protection. Depends on relay support; does not guarantee full traffic anonymity.",
                ech, editable, onEch)
            SettingRow("Block MITM relays", "Rejects relays where TLS interception is detected. Does not guarantee all connections are safe.",
                banMitm, editable, onBanMitm)
        }
    }
    item {
        Panel("Protocols") {
            Text("The built-in content is served over the web. Enable extra protocols only if you need other traffic.", color = TextSecondary)
            SettingRow("UDP relay", "Requests UDP relay for games, QUIC, etc.", udp, editable, onUdp)
            SettingRow("TCP relay", "Requests raw TCP relay beyond web publishing.", tcp, editable, onTcp)
        }
    }
    item {
        Panel("On-device model") {
            Text("Applies to the 'On-device model' content. Ollama runs locally and manages GPU + model bytes; pull a tag to upgrade from the built-in fallback.", color = TextSecondary)
            when (val s = ollamaSetup) {
                is OllamaSetup.State.Running ->
                    Text("Ollama daemon: running", color = Mint,
                        style = MaterialTheme.typography.bodySmall)
                is OllamaSetup.State.Downloading ->
                    Text("Downloading Ollama… ${s.detail}${if (s.progress >= 0) " (${(s.progress * 100).toInt()}%)" else ""}",
                        color = Cyan, style = MaterialTheme.typography.bodySmall)
                is OllamaSetup.State.Installing ->
                    Text("Installing Ollama…", color = Cyan,
                        style = MaterialTheme.typography.bodySmall)
                is OllamaSetup.State.Failed -> {
                    Text("Ollama setup failed: ${s.message}", color = Danger,
                        style = MaterialTheme.typography.bodySmall)
                    ActionButton("Retry install", { OllamaSetup.install() }, editable)
                }
                is OllamaSetup.State.NotInstalled -> {
                    Text("Ollama daemon: not detected — the app can install and run it for you.",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    ActionButton("Install Ollama", { OllamaSetup.install() },
                        editable && OllamaSetup.isSupported)
                    if (!OllamaSetup.isSupported) {
                        Text("Auto-install isn't supported on this OS/arch — install from ollama.com.",
                            color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            OllamaModels.MODELS.forEach { spec ->
                ModelRow(
                    spec = spec,
                    selected = spec.id == selectedModel.id,
                    installed = spec.id in installedModels,
                    enabled = editable && modelState !is OllamaModels.State.Pulling,
                    onClick = {
                        OllamaModels.select(spec)
                        actions.onModelChanged()
                        scope.launch {
                            onInstalledChange(OllamaModels.MODELS
                                .filter { OllamaModels.isInstalled(it) }
                                .map { it.id }.toSet())
                        }
                    },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.activityContent(
    snapshot: PortalSnapshot?,
    running: Boolean,
    operable: Boolean,
    editable: Boolean,
    busy: Boolean,
    liveDescription: String, onLiveDescription: (String) -> Unit,
    liveTags: String, onLiveTags: (String) -> Unit,
    liveOwner: String, onLiveOwner: (String) -> Unit,
    liveHide: Boolean, onLiveHide: (Boolean) -> Unit,
    newRelay: String, onNewRelay: (String) -> Unit,
    activityPanel: String, onActivityPanel: (String) -> Unit,
    identity: PortalIdentity?,
    eventLog: List<String>,
    diagnostics: PortalDiagnostics?,
    actions: SampleActions,
) {
    item { Text("Inspect the connection and manage live publish info.", color = TextSecondary) }
    item {
        ActivityPanel("session", "Connection status", activityPanel, onActivityPanel) {
            PhaseBadge(snapshot?.phase)
            snapshot?.let {
                Text("Revision ${it.revision} · dropped events ${it.droppedEventCount}", color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (running) {
                ActionButton("Refresh status", actions.onRefresh, operable)
                ActionButton("Await ready", actions.onAwaitReady, operable)
            } else Text("No active publish. Start publishing to see connection status.", color = TextSecondary)
        }
    }
    item {
        ActivityPanel("metadata", "Edit public info", activityPanel, onActivityPanel) {
            Text(if (running) "Changes metadata for the currently running publish. Separate from next-start settings."
                else "Change description, tags, owner, and listing visibility live after publishing.", color = TextSecondary)
            PortalField(liveDescription, onLiveDescription, "New description", "Description shown in the public directory. Empty values are excluded from the update.", operable)
            PortalField(liveTags, onLiveTags, "New tags", "Comma-separated. Empty values are excluded from the update.", operable)
            PortalField(liveOwner, onLiveOwner, "Owner", "Name of who runs this publish. Empty values are excluded from the update.", operable)
            SettingRow("Hide from public directory", "Does not block URL access or add authentication.", liveHide, operable, onLiveHide)
            if (running) ActionButton("Apply public info", {
                actions.onUpdateMetadata(PortalMetadata(
                    description = liveDescription.ifBlank { null }, tags = commaValues(liveTags),
                    owner = liveOwner.ifBlank { null }, hide = liveHide,
                ))
            }, operable)
        }
    }
    item {
        ActivityPanel("relays", "Relay management · ${snapshot?.relays?.size ?: 0}", activityPanel, onActivityPanel) {
            Text("Add or remove relays while running. Public addresses may change as connections shift.", color = TextSecondary)
            snapshot?.relays.orEmpty().forEach { relay -> RelayRow(relay, running, operable, actions.onRemoveRelay) }
            if (running) {
                PortalField(newRelay, onNewRelay, "Relay address to add", "Enter one relay address to connect.", operable)
                ActionButton("Add relay", { actions.onAddRelay(newRelay.trim()) },
                    operable && newRelay.isNotBlank())
            } else Text("Set start relays in Settings.", color = Mint)
        }
    }
    item {
        ActivityPanel("identity", "Device identity", activityPanel, onActivityPanel) {
            if (identity == null) Text("No identity to show yet. One is created on first start or a saved identity is used.", color = TextSecondary)
            else SelectionContainer {
                Text("Name: ${identity.name}\nAddress: ${identity.address}", color = Mint,
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("Generate a new identity to inspect. Does not change the running publish's identity.", color = TextSecondary)
            ActionButton("Generate new identity", actions.onGenerateIdentity, editable)
        }
    }
    item {
        ActivityPanel("events", "Event history · ${eventLog.size}", activityPanel, onActivityPanel) {
            if (eventLog.isEmpty()) Text("No history yet. Connection activity will appear here.", color = TextSecondary)
            else SelectionContainer {
                Text(eventLog.joinToString("\n"), color = TextSecondary,
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item {
        ActivityPanel("diagnostics", "Diagnostics", activityPanel, onActivityPanel) {
            diagnostics?.let { d ->
                SelectionContainer {
                    Text("SDK ${d.sdkVersion} · ABI ${d.abiVersion} · wire schema ${d.wireSchemaVersion}\n" +
                        "Active sessions ${d.activeSessions} · orphan drops ${d.droppedOrphanEvents}",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            } ?: Text("Load SDK and session info when troubleshooting.", color = TextSecondary)
            ActionButton("Load diagnostics", actions.onDiagnostics, !busy)
        }
    }
}

private fun commaValues(value: String): List<String>? =
    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }

@Composable
private fun PublishHero(name: String, phase: TunnelPhase?, compact: Boolean) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF19394A), Surface1)))) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PhaseBadge(phase)
                Text(if (compact) "Publishing from this device" else "This device is the content's start.",
                    fontSize = if (compact) 22.sp else 26.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Text(name.ifBlank { "My Snake game" }, color = Mint,
                    style = MaterialTheme.typography.titleMedium)
            }
            if (!compact) {
                GameIllustration(Modifier.width(220.dp))
            }
        }
    }
}

@Composable
private fun GameIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.height(120.dp).semantics {
        contentDescription = "Snake game preview: mint snake and food on a dark board"
    }) {
        drawRoundRect(Bg.copy(alpha = 0.75f), cornerRadius = CornerRadius(14.dp.toPx()))
        val cell = 15.dp.toPx()
        val left = (size.width - cell * 11) / 2
        val top = (size.height - cell * 6) / 2
        for (x in 0..10) for (y in 0..5) {
            drawCircle(TextSecondary.copy(alpha = 0.17f), 1.dp.toPx(), Offset(left + x * cell, top + y * cell))
        }
        val snake = listOf(1 to 4, 2 to 4, 3 to 4, 3 to 3, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 2)
        snake.forEachIndexed { index, (x, y) ->
            drawRoundRect(if (index == snake.lastIndex) Mint else Cyan.copy(alpha = 0.45f + index * 0.05f),
                topLeft = Offset(left + x * cell - cell * 0.43f, top + y * cell - cell * 0.43f),
                size = Size(cell * 0.86f, cell * 0.86f), cornerRadius = CornerRadius(4.dp.toPx()))
        }
        drawCircle(Bg, 1.7.dp.toPx(), Offset(left + 7.18f * cell, top + 1.83f * cell))
        drawCircle(Danger.copy(alpha = 0.12f), 15.dp.toPx(), Offset(left + 9 * cell, top + 2 * cell))
        drawCircle(Danger, 5.dp.toPx(), Offset(left + 9 * cell, top + 2 * cell))
    }
}

@Composable
private fun DestinationIcon(index: Int, selected: Boolean) {
    val color = if (selected) Cyan else TextSecondary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()
        when (index) {
            0 -> {
                drawRoundRect(color, Offset(size.width * 0.12f, size.height * 0.19f),
                    Size(size.width * 0.76f, size.height * 0.62f), CornerRadius(4.dp.toPx()), style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.28f, size.height * 0.5f), Offset(size.width * 0.5f, size.height * 0.5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.39f, size.height * 0.39f), Offset(size.width * 0.39f, size.height * 0.61f), stroke, StrokeCap.Round)
                drawCircle(color, 1.4.dp.toPx(), Offset(size.width * 0.7f, size.height * 0.46f))
            }
            1 -> for (i in 0..2) {
                val y = size.height * (0.25f + i * 0.25f)
                drawLine(color, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), stroke, StrokeCap.Round)
                drawCircle(Bg, 2.6.dp.toPx(), Offset(size.width * (if (i == 1) 0.65f else 0.35f), y))
                drawCircle(color, 2.6.dp.toPx(), Offset(size.width * (if (i == 1) 0.65f else 0.35f), y), style = Stroke(stroke))
            }
            else -> {
                for (i in 0..2) {
                    val x = size.width * (0.25f + i * 0.25f)
                    drawLine(color, Offset(x, size.height * 0.8f),
                        Offset(x, size.height * (if (i == 1) 0.2f else 0.45f)), stroke * 2, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun PhaseBadge(phase: TunnelPhase?) {
    val label = when (phase) {
        null, TunnelPhase.IDLE -> "Ready to publish"
        TunnelPhase.STARTING -> "Starting"
        TunnelPhase.CONNECTING -> "Connecting to relay"
        TunnelPhase.ACTIVE -> "Publishing"
        TunnelPhase.STOPPING -> "Stopping"
        TunnelPhase.STOPPED -> "Stopped"
        TunnelPhase.FAILED -> "Failed"
    }
    val color = when (phase) { TunnelPhase.ACTIVE -> Mint; TunnelPhase.FAILED -> Danger; else -> Cyan }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(30.dp)) {
        Text(label, color = color, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ActivityPanel(key: String, title: String, selected: String, onSelect: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)) {
        TextButton(onClick = { onSelect(if (selected == key) "" else key) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(if (selected == key) "Collapse" else "Expand", color = Cyan)
        }
        if (selected == key) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PortalField(value: String, onChange: (String) -> Unit, label: String, hint: String,
    enabled: Boolean, isError: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
            enabled = enabled, isError = isError, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan,
                unfocusedBorderColor = Surface2, disabledTextColor = TextSecondary,
                disabledLabelColor = TextSecondary, disabledBorderColor = Surface2))
        Text(hint, color = if (isError) Danger else TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingRow(label: String, hint: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.Medium, color = TextPrimary, fontSize = 14.sp)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Bg, checkedTrackColor = Cyan))
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(10.dp)) {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun RelayRow(relay: PortalRelayStatus, running: Boolean, operable: Boolean, onRemove: (String) -> Unit) {
    Surface(color = Bg, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when {
                !running -> "Ended"
                relay.isMitm -> "MITM suspected"
                relay.isFailed -> "Failed"
                relay.isReady -> "Ready"
                else -> "State: ${relay.state}"
            }, color = if (relay.isMitm || relay.isFailed) Danger else TextSecondary,
                style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(relay.relayUrl, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall, color = TextPrimary) }
            relay.error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
            if (running) ActionButton("Remove this relay", { onRemove(relay.relayUrl) }, operable,
                Modifier.semantics { contentDescription = "Remove relay ${relay.relayUrl}" })
        }
    }
}

@Composable
private fun ContentChoice(
    content: PublishableContent,
    selected: String,
    enabled: Boolean,
    modelState: OllamaModels.State? = null,
    modelLabel: String? = null,
    engineStatus: OnDeviceModelContent.EngineStatus? = null,
    daemonRunning: Boolean? = null,
    onDownloadModel: (() -> Unit)? = null,
    onSelect: (String) -> Unit
) {
    val active = selected == content.id
    Surface(
        color = if (active) Cyan.copy(alpha = 0.12f) else Bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().toggleable(
            value = active,
            enabled = enabled,
            role = Role.RadioButton,
            onValueChange = { onSelect(content.id) }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(content.title,
                color = if (active) Cyan else TextPrimary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge)
            Text(content.summary, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            content.detail?.let {
                Text(it, color = Mint, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (daemonRunning == false) {
                Text("Ollama not detected — install from ollama.com to enable real inference.",
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            if (modelState != null) {
                when (modelState) {
                    is OllamaModels.State.NotInstalled -> {
                        if (daemonRunning == true) {
                            Text("Model not pulled — a tiny built-in model answers until one lands.",
                                color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                            if (onDownloadModel != null) {
                                ActionButton("Pull model (${modelLabel ?: "~100 MB"})", onDownloadModel,
                                    enabled = enabled)
                            }
                        }
                    }
                    is OllamaModels.State.Pulling -> {
                        Text("Pulling model… ${(modelState.progress * 100).toInt()}%",
                            color = Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                    is OllamaModels.State.Ready -> {
                        Text("Model ready — real LLM inference on this device.",
                            color = Mint, style = MaterialTheme.typography.labelSmall)
                    }
                    is OllamaModels.State.Failed -> {
                        Text("Pull failed: ${modelState.message}",
                            color = Danger, style = MaterialTheme.typography.labelSmall)
                        if (onDownloadModel != null) {
                            ActionButton("Retry pull", onDownloadModel,
                                enabled = enabled)
                        }
                    }
                }
            }
            if (engineStatus != null) {
                when (val s = engineStatus) {
                    is OnDeviceModelContent.EngineStatus.Loading ->
                        Text("Connecting to ${s.backend}…",
                            color = Cyan, style = MaterialTheme.typography.labelSmall)
                    is OnDeviceModelContent.EngineStatus.Ready ->
                        Text("Engine ready: ${s.backend}",
                            color = Mint, style = MaterialTheme.typography.labelSmall)
                    OnDeviceModelContent.EngineStatus.Unavailable ->
                        Text("Ollama/model unavailable — using built-in fallback model.",
                            color = Danger, style = MaterialTheme.typography.labelSmall)
                    OnDeviceModelContent.EngineStatus.Failed ->
                        Text("Engine failed — using built-in fallback model.",
                            color = Danger, style = MaterialTheme.typography.labelSmall)
                    OnDeviceModelContent.EngineStatus.Idle -> {}
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: OllamaModels.ModelSpec,
    selected: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) Cyan.copy(alpha = 0.12f) else Bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().toggleable(
            value = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onValueChange = { onClick() }
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(spec.title,
                    color = if (selected) Cyan else TextPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp)
                Text(spec.blurb, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(spec.sizeLabel, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                if (installed) Text("pulled", color = Mint, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
