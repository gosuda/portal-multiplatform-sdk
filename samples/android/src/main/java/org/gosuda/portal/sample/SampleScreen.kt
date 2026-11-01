package org.gosuda.portal.sample

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.gosuda.portal.sample.llm.DownloadPhase
import org.gosuda.portal.sample.llm.LlmPassword
import org.gosuda.portal.sample.llm.ModelDownloadState
import org.gosuda.portal.sample.llm.ModelSpec

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
    val onDownloadModel: (String) -> Unit,
    val onCancelDownload: () -> Unit,
    val onLlmPasswordChange: (String) -> Unit,
    val onGenerateLlmPassword: () -> Unit,
)

@Composable
fun PortalSampleTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color.White else Color(0xFF111111)
    val canvas = if (dark) Color(0xFF0B0B0B) else Color(0xFFFAFAFA)
    val paper = if (dark) Color(0xFF141414) else Color.White
    val neutral = if (dark) Color(0xFFB4B4B4) else Color(0xFF666666)
    val line = if (dark) Color(0xFF363636) else Color(0xFFDEDEDE)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = ink, onPrimary = canvas, primaryContainer = ink, onPrimaryContainer = canvas,
            secondary = ink, onSecondary = canvas, secondaryContainer = line, onSecondaryContainer = ink,
            tertiary = ink, onTertiary = canvas, tertiaryContainer = line, onTertiaryContainer = ink,
            background = canvas, onBackground = ink, surface = paper, onSurface = ink,
            surfaceVariant = paper, onSurfaceVariant = neutral, outline = line, outlineVariant = line,
            error = ink, onError = canvas, errorContainer = line, onErrorContainer = ink,
            inverseSurface = ink, inverseOnSurface = canvas, inversePrimary = canvas,
            surfaceTint = Color.Transparent, surfaceBright = paper, surfaceDim = canvas,
            surfaceContainer = paper, surfaceContainerLow = canvas, surfaceContainerLowest = canvas,
            surfaceContainerHigh = line, surfaceContainerHighest = line,
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)),
        typography = Typography(
            headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
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
    models: List<ModelSpec>,
    cachedModels: Set<String>,
    download: ModelDownloadState,
    activeModelId: String?,
    cleanupPending: Boolean,
    llmPassword: String,
    actions: SampleActions,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var modeName by rememberPersistedString("workbench.mode", PublishMode.STATIC_SITE.name)
    val mode = PublishMode.entries.firstOrNull { it.name == modeName } ?: PublishMode.STATIC_SITE
    var modelId by rememberPersistedString("workbench.model", models.firstOrNull()?.id.orEmpty())
    val selectedModel = models.firstOrNull { it.id == modelId } ?: models.firstOrNull()
    var modeMenu by rememberSaveable { mutableStateOf(false) }
    var builtIn by rememberPersistedBoolean("workbench.builtIn", true)
    var domainPrefix by rememberPersistedString("workbench.domainPrefix", "android-demo")
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
    val owned = running || cleanupPending
    val editable = !owned && !busy
    val operable = running && !busy && snapshot.phase != TunnelPhase.STOPPING
    val missingRelay = !discovery && commaValues(relayUrls).isEmpty()
    val validLlmPassword = LlmPassword.isValid(llmPassword)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { value ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Portal endpoint", value))) }
    }
    val copyPassword: () -> Unit = {
        scope.launch {
            val clip = ClipData.newPlainText("Portal AI password", llmPassword)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clipboard.setClipEntry(ClipEntry(clip))
        }
    }
    val uriHandler = LocalUriHandler.current
    val primaryPublicUrl = snapshot?.primaryPublicUrl
    val publicHttpUrl = primaryPublicUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val target = when (mode) { PublishMode.TCP -> tcpTarget; PublishMode.UDP -> udpTarget; else -> httpTarget }

    Scaffold(
        bottomBar = {
            Column {
                Button(
                    onClick = {
                        if (owned) actions.onStop()
                        else actions.onStart(PublishRequest(
                            mode = mode, builtIn = builtIn,
                            options = PortalConfig(
                                domainPrefix = domainPrefix.trim().ifBlank { null },
                                description = description.ifBlank { null }, tags = commaValues(tags),
                                relays = commaValues(relayUrls), discovery = discovery,
                                banMitm = banMitm, hide = hide,
                            ),
                            target = target, firstPrefix = firstPrefix, firstUpstream = firstUpstream,
                            secondPrefix = secondPrefix, secondUpstream = secondUpstream,
                            modelId = selectedModel?.id,
                        ))
                    },
                    enabled = !busy && (owned || (!missingRelay &&
                        (mode != PublishMode.LOCAL_LLM || (validLlmPassword &&
                            selectedModel?.id?.let { it in cachedModels } == true)))),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(when { busy -> "Working…"; cleanupPending || snapshot?.phase == TunnelPhase.STOPPING -> "Retry stop"; running -> "Stop publishing"; else -> "Publish ${if (mode == PublishMode.LOCAL_LLM) "local AI" else "to the web"}" })
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 900.dp).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("PORTAL / DEVICE WORKBENCH", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 2.sp)
                Text("Your device.\nOn the web.", style = MaterialTheme.typography.headlineLarge)
                Text("${snapshot?.phase ?: "READY TO CREATE"} · ${if (busy) "Working" else "Local services, real public links"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            item {
                Panel("YOUR ADDRESS / Domain prefix") {
                    Field("Domain prefix", domainPrefix, editable) { domainPrefix = it }
                    Text("A short label, such as my-arcade. Leave blank for your stored identity. The relay grants an available alias only for the active session; a collision falls back to the canonical URL.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tab == 0) {
                item {
                    Panel("What would you like to share?") {
                        Box {
                            OutlinedButton(onClick = { modeMenu = true }, enabled = editable,
                                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                                Text("${mode.title}  ▾")
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                PublishMode.entries.forEach { candidate ->
                                    DropdownMenuItem(text = { Text(candidate.title) }, onClick = {
                                        modeName = candidate.name
                                        modeMenu = false
                                    })
                                }
                            }
                        }
                        Text(mode.help)
                        if (mode != PublishMode.STATIC_SITE && mode != PublishMode.LOCAL_LLM) {
                            Toggle("Use built-in loopback demo", builtIn, editable) { builtIn = it }
                            Text(if (builtIn) "Starts a real sample-only listener on an ephemeral loopback port. No installation or external server required."
                                else "Advanced: start your upstream on this Android device before publishing. Desktop localhost is not device localhost. Remote targets remain disabled.")
                        }
                        if (mode == PublishMode.ROUTES) {
                            Field("Route 1 prefix", firstPrefix, editable) { firstPrefix = it }
                            if (!builtIn) Field("Route 1 upstream URL", firstUpstream, editable) { firstUpstream = it }
                            Field("Route 2 prefix (fallback)", secondPrefix, editable) { secondPrefix = it }
                            if (!builtIn) Field("Route 2 upstream URL", secondUpstream, editable) { secondUpstream = it }
                        } else if (mode != PublishMode.STATIC_SITE && mode != PublishMode.LOCAL_LLM && !builtIn) {
                            Field("Loopback host:port", target, editable) {
                                when (mode) { PublishMode.TCP -> tcpTarget = it; PublishMode.UDP -> udpTarget = it; else -> httpTarget = it }
                            }
                        }
                    }
                }
                if (mode == PublishMode.LOCAL_LLM) {
                    item {
                        ModelPanel(models, selectedModel, cachedModels, download, editable,
                            activeModelId, onSelect = { modelId = it }, actions = actions)
                    }
                    item {
                        Panel("LOCAL AI / Password") {
                            OutlinedTextField(
                                value = llmPassword, onValueChange = actions.onLlmPasswordChange,
                                label = { Text("Password") }, enabled = editable, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                isError = !validLlmPassword,
                                supportingText = {
                                    Text(if (validLlmPassword) "12–128 Unicode characters. A generated password is recommended."
                                        else "Enter 12–128 Unicode characters before publishing.")
                                },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = actions.onGenerateLlmPassword, enabled = editable) {
                                    Text("Generate password")
                                }
                                OutlinedButton(onClick = copyPassword, enabled = validLlmPassword) {
                                    Text("Copy password")
                                }
                            }
                            Text("Open the public URL, then enter this password on the webpage. Sharing the URL alone does not grant inference access.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Kept only in app memory through rotation and session stops; a fresh app process generates a new default. Editing and generation are locked until publishing and cleanup finish.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    AdvancedPanel("Relay & listing options") {
                        Field("Description", description, editable) { description = it }
                        Field("Tags (comma-separated)", tags, editable) { tags = it }
                        Toggle("Relay discovery", discovery, editable) { discovery = it }
                        Field("Relay URLs (comma-separated)", relayUrls, editable) { relayUrls = it }
                        if (missingRelay) Text("Choose discovery or supply at least one relay.")
                        Toggle("Reject suspected MITM relays", banMitm, editable) { banMitm = it }
                        Toggle("Hide from public listings", hide, editable) { hide = it }
                        Text("Hide is a listing preference, not authentication. Public addresses expose shared content; local AI inference additionally requires its password.")
                    }
                }
                item {
                    AdvancedPanel("Execution policy") {
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
                        if (cleanupPending) Text("Local cleanup is retained. Retry stop before starting another session.")
                        primaryPublicUrl?.let { url ->
                            Text("Primary public URL")
                            Copyable(url) { copy(url) }
                            if (publicHttpUrl != null) {
                                OutlinedButton(onClick = {
                                    try { uriHandler.openUri(url); openError = null }
                                    catch (e: RuntimeException) { openError = "No browser could open this address: ${e.message}" }
                                }) { Text("Open in browser") }
                            }
                        }
                        if (activeModelId != null && running && publicHttpUrl != null) {
                            Text("Password-protected local AI", style = MaterialTheme.typography.titleSmall)
                            Text("Open the public URL above and enter the password separately. The password stays out of the URL, SDK metadata and logs.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = copyPassword) { Text("Copy password") }
                        }
                        snapshot?.publicUrls.orEmpty().filter { it != primaryPublicUrl }.forEach { url ->
                            Text("Canonical identity-bound URL")
                            Copyable(url) { copy(url) }
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
                                PublishMode.LOCAL_LLM -> "Open the public URL above and enter the password from Copy password. The browser sends Basic authorization as portal; no prompts, passwords or outputs appear in this log."
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
                        Text("Device-local AI: explicit one-time verified model download, persistent app-private cache, native CPU inference, and a private single-turn webpage. No weights ship in the APK. Downloading and inference are sample-owned, not SDK features.")
                        Text("HTTP: forwards a real loopback service. Built-in GET /api returns JSON; POST /webhook acknowledges a body without storing it (16 KiB maximum, Content-Length required).")
                        Text("Routes: the longest matching path prefix wins. Built-in /api selects the JSON API service and / selects a separate webhook receiver; custom mode supports two independent services.")
                        Text("TCP and UDP: forward raw traffic to a real listener. Built-in echo uses 4 KiB buffers; TCP limits each connection to 64 KiB with a 3-second idle timeout. Relay support is required; use raw relay endpoints.")
                        Text("publish waits for readiness. Application owns the PortalClient and listeners; screen observation never owns the tunnel. A failed stop remains retryable, including failed publish cleanup.")
                        Text("Domain prefix requests a lease-scoped short HTTP URL without changing stored identity. The primary URL comes from snapshot.primaryPublicUrl: a ready relay-granted alias while ACTIVE, or the canonical fallback when the alias is unavailable. Raw TCP/UDP endpoints are unchanged.")
                        Text("No global stop, identity editor or remote targets. This UI covers the main modes, not every SDK option. Overlay is unsupported in v1; hide is not access control. Local AI additionally requires its configured password.")
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AdvancedPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel(title) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide settings −" else "Show settings +")
        }
        if (expanded) content()
    }
}

@Composable
private fun ModelPanel(
    models: List<ModelSpec>,
    selected: ModelSpec?,
    cached: Set<String>,
    progress: ModelDownloadState,
    editable: Boolean,
    activeModelId: String?,
    onSelect: (String) -> Unit,
    actions: SampleActions,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var sourceError by rememberSaveable { mutableStateOf(false) }
    val downloading = progress.phase == DownloadPhase.DOWNLOADING || progress.phase == DownloadPhase.VERIFYING
    Panel("MODEL LIBRARY / Download once, keep locally") {
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Text("${selected?.title ?: "No model available"}  ▾")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                models.forEach { candidate ->
                    DropdownMenuItem(text = {
                        Text("${candidate.title} · ${candidate.downloadBytes / 1_048_576} MiB${if (candidate.id in cached) " · Cached" else ""}")
                    }, onClick = { onSelect(candidate.id); menu = false; sourceError = false })
                }
            }
        }
        selected?.let { model ->
            Text(model.summary)
            Text("${model.parameters} · ${model.quantization} · ${model.downloadBytes / 1_048_576} MiB",
                style = MaterialTheme.typography.titleSmall)
            Text("Languages: ${model.languages}\nLicense: ${model.license}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(model.source, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = {
                try { uriHandler.openUri(model.source); sourceError = false }
                catch (_: RuntimeException) { sourceError = true }
            }) { Text("View model source & license") }
            if (sourceError) Text("No browser is available to open the model source.")
            val ready = model.id in cached
            Text(when {
                model.id == activeModelId -> "IN USE / Native CPU inference"
                ready -> "CACHED / Ready to publish offline weights"
                else -> "NOT DOWNLOADED / Publishing is disabled until verified"
            }, style = MaterialTheme.typography.labelLarge)
            Button(onClick = { actions.onDownloadModel(model.id) },
                enabled = editable && !downloading && !ready) {
                Text(if (ready) "Already downloaded" else "Download ${model.downloadBytes / 1_048_576} MiB")
            }
        }
        if (downloading) {
            Text("${models.firstOrNull { it.id == progress.modelId }?.title ?: "Model"} · ${if (progress.phase == DownloadPhase.VERIFYING) "Verifying SHA-256…" else "Downloading…"}")
            LinearProgressIndicator(progress = {
                if (progress.totalBytes > 0) (progress.receivedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f
            }, modifier = Modifier.fillMaxWidth())
            Text("${progress.receivedBytes / 1_048_576} / ${progress.totalBytes / 1_048_576} MiB",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.onCancelDownload) { Text("Cancel download") }
        } else if (progress.phase == DownloadPhase.FAILED) {
            Text("Download failed: ${progress.error ?: "Check your connection and available storage, then retry."}")
        } else if (progress.phase == DownloadPhase.CANCELLED) {
            Text("Download cancelled. No incomplete weights can be published.")
        }
        Text("Nothing downloads on launch, selection or Publish. Completed, size- and hash-verified weights persist in app files through restarts and session stops. No model is bundled in the APK. Smaller models reduce storage and memory needs; speed and answer quality depend on your device.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("The shared page runs independent single-turn requests on this device's CPU, not a cloud API. Keep this app alive while serving. Open the public URL and enter the separately configured password; do not share the password publicly.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, enabled = enabled,
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp))
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
