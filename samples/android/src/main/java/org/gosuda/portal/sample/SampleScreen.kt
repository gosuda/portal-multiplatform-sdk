package org.gosuda.portal.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import org.gosuda.portal.sample.content.ondevice.ModelDownload
import org.gosuda.portal.sample.content.ondevice.OnDeviceModelContent

/** Action callbacks wired by [MainActivity]. */
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
    // Drafts belong to the screen, not a destination. Saveable state also survives rotation.
    var destination by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf("snake-game") }
    var description by rememberSaveable { mutableStateOf("Snake game served from this device") }
    var tags by rememberSaveable { mutableStateOf("game,snake") }
    var relays by rememberSaveable { mutableStateOf("") }
    var discovery by rememberSaveable { mutableStateOf(true) }
    var udp by rememberSaveable { mutableStateOf(false) }
    var tcp by rememberSaveable { mutableStateOf(false) }
    var ech by rememberSaveable { mutableStateOf(false) }
    var banMitm by rememberSaveable { mutableStateOf(false) }
    var hide by rememberSaveable { mutableStateOf(false) }
    var liveDescription by rememberSaveable { mutableStateOf("") }
    var liveTags by rememberSaveable { mutableStateOf("") }
    var liveOwner by rememberSaveable { mutableStateOf("") }
    var liveHide by rememberSaveable { mutableStateOf(false) }
    var contentId by rememberSaveable { mutableStateOf("snake") }
    var newRelay by rememberSaveable { mutableStateOf("") }
    var activityPanel by rememberSaveable { mutableStateOf("session") }
    val publishScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val activityScroll = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val onDeviceEngineStatus by OnDeviceModelContent.engineStatus.collectAsState()
    val context = LocalContext.current
    val modelDownloadState by ModelDownload.state.collectAsState()
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
        bottomBar = {
            Surface(color = Bg, shadowElevation = 12.dp) {
                Column(Modifier.imePadding()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        if (missingRelay && !running) {
                            Text("Enable auto-discovery or enter a relay address.",
                                color = Danger, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Button(
                            onClick = {
                                if (running) actions.onStop()
                                else actions.onStart(PortalConfig(
                                    name = name.trim().ifBlank { null },
                                    description = description.ifBlank { null },
                                    tags = commaValues(tags), relays = commaValues(relays),
                                    discovery = discovery, udp = udp, tcp = tcp,
                                    ech = ech, banMitm = banMitm, hide = hide,
                                ), contentId)
                            },
                            enabled = !busy && (running || !missingRelay),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (running) Surface2 else Cyan,
                                contentColor = if (running) TextPrimary else Bg,
                            ),
                        ) {
                            Text(when {
                                busy -> "Processing…"
                                snapshot?.phase == TunnelPhase.STOPPING -> "Retry stop"
                                running -> "Stop publishing"
                                else -> "Publish game"
                            }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
                        listOf("Publish", "Settings", "Activity").forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = destination == index,
                                onClick = { destination = index },
                                icon = { DestinationIcon(index, destination == index) },
                                label = { Text(label, fontWeight = FontWeight.SemiBold) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Cyan, selectedTextColor = Cyan,
                                    indicatorColor = Surface2,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = when (destination) { 1 -> settingsScroll; 2 -> activityScroll; else -> publishScroll },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PORTAL / from this device to the web", color = Cyan,
                            style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
                        Text(when (destination) { 1 -> "Your settings"; 2 -> "Publishing activity"; else -> "Small game, wide world" },
                            modifier = Modifier.padding(top = 8.dp), fontSize = 27.sp,
                            lineHeight = 34.sp, fontWeight = FontWeight.Bold)
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
                0 -> {
                    item { PublishHero(snapshot?.nativeStatus?.name ?: name, snapshot?.phase, running) }
                    item {
                        Panel("What to publish") {
                            SampleContents.all.forEach { content ->
                                ContentChoice(content, contentId, editable,
                                    modelState = if (content.id == "ondevice") modelDownloadState else null,
                                    engineStatus = if (content.id == "ondevice") onDeviceEngineStatus else null,
                                    onDownloadModel = if (content.id == "ondevice") ({ ModelDownload.start(context) }) else null
                                ) { contentId = it }
                            }
                        }
                    }
                    item {
                        Panel(if (urls.isEmpty()) "Waiting for a public link" else "Share this link now") {
                            if (urls.isEmpty()) {
                                Text(if (running) "The relay will send an address here. No link to share yet."
                                    else "Tap 'Publish game' below to publish the built-in Snake game from this device. Share the address you receive after the relay connects.",
                                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("The address can change as relays join/leave. Share the current link.",
                                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                urls.forEachIndexed { index, url ->
                                    Surface(color = Bg, shape = RoundedCornerShape(16.dp)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Public address ${index + 1}", color = TextSecondary,
                                                style = MaterialTheme.typography.labelMedium)
                                            SelectionContainer { Text(url, color = Mint,
                                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                ActionButton("Copy link", onClick = {
                                                    clipboard.setText(AnnotatedString(url))
                                                    notify("Copied public address ${index + 1}.")
                                                }, modifier = Modifier.weight(1f).semantics { contentDescription = "Copy public address ${index + 1}" })
                                                ActionButton("Open", onClick = {
                                                    try { uriHandler.openUri(url) }
                                                    catch (_: Exception) { notify("Cannot open the address. Copy the link and check it in a browser.") }
                                                }, modifier = Modifier.weight(1f).semantics { contentDescription = "Open public address ${index + 1} in browser" })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Panel("Before you publish") {
                            Text("This device serves the game. The app and network must stay connected for visitors to reach it.", color = TextSecondary)
                            Text(if (keepAlive) "Keep-alive on · runs with a notification."
                                else "Keep-alive off · change it in Settings.",
                                color = Mint, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { destination = 1 }) { Text("Review publish settings →") }
                        }
                    }
                }
                1 -> {
                    item {
                        Text(if (running) "Cannot edit start settings while publishing. Stop publishing below, then edit. Live info can be changed in Activity."
                            else "The game is ready. Change what you need, then publish with the button below.", color = TextSecondary)
                    }
                    item {
                        Panel("01 / Basics") {
                            PortalField(name, { name = it }, "Public name", "Name for a new identity. A saved identity may keep its existing name.", editable)
                            PortalField(description, { description = it }, "Description", "A sentence describing the game. May appear in the public directory.", editable)
                            PortalField(tags, { tags = it }, "Tags", "Comma-separated. e.g. game, snake", editable)
                            SettingRow("Keep alive in background", "Keeps running via a foreground service and notification. Subject to the device's power-saving policy.",
                                keepAlive, editable, actions.onKeepAlive)
                        }
                    }
                    item {
                        Panel("02 / Relay connection") {
                            Text("Relays carry traffic between this device and visitors.", color = TextSecondary)
                            SettingRow("Auto-discover relays", "Finds relays to use. Does not list your game in the public directory.",
                                discovery, editable) { discovery = it }
                            PortalField(relays, { relays = it }, "Relay address (manual)", "Comma-separated. At least one required when auto-discovery is off.", editable, missingRelay)
                        }
                    }
                    item {
                        Panel("03 / Visibility & security") {
                            SettingRow("Hide from public directory", "Reduces listing exposure. Not authentication or access control — anyone with the URL can connect.",
                                hide, editable) { hide = it }
                            SettingRow("Use ECH", "Requests TLS ClientHello protection. Depends on relay support; does not guarantee full traffic anonymity.",
                                ech, editable) { ech = it }
                            SettingRow("Block MITM relays", "Rejects relays where TLS interception is detected. Does not guarantee all connections are safe.",
                                banMitm, editable) { banMitm = it }
                        }
                    }
                    item {
                        Panel("04 / Protocols") {
                            Text("The built-in game is served over the web. Enable extra protocols only if you need other traffic.", color = TextSecondary)
                            SettingRow("UDP relay", "Requests UDP relay for games, QUIC, etc.", udp, editable) { udp = it }
                            SettingRow("TCP relay", "Requests raw TCP relay beyond web publishing.", tcp, editable) { tcp = it }
                        }
                    }
                }
                2 -> {
                    item { Text("Inspect the connection and manage live publish info.", color = TextSecondary) }
                    item {
                        ActivityPanel("session", "Connection status", activityPanel, { activityPanel = it }) {
                            PhaseBadge(snapshot?.phase)
                            snapshot?.let {
                                Text("Revision ${it.revision} · dropped events ${it.droppedEventCount}", color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (running) {
                                ActionButton("Refresh status", actions.onRefresh, operable, Modifier.fillMaxWidth())
                                ActionButton("Await ready", actions.onAwaitReady, operable, Modifier.fillMaxWidth())
                            } else Text("No active publish. Start publishing to see connection status.", color = TextSecondary)
                        }
                    }
                    item {
                        ActivityPanel("metadata", "Edit public info", activityPanel, { activityPanel = it }) {
                            Text(if (running) "Changes metadata for the currently running publish. Separate from next-start settings."
                                else "Change description, tags, owner, and listing visibility live after publishing.", color = TextSecondary)
                            PortalField(liveDescription, { liveDescription = it }, "New description", "Description shown in the public directory. Empty values are excluded from the update.", operable)
                            PortalField(liveTags, { liveTags = it }, "New tags", "Comma-separated. Empty values are excluded from the update.", operable)
                            PortalField(liveOwner, { liveOwner = it }, "Owner", "Name of who runs this publish. Empty values are excluded from the update.", operable)
                            SettingRow("Hide from public directory", "Does not block URL access or add authentication.", liveHide, operable) { liveHide = it }
                            if (running) ActionButton("Apply public info", {
                                actions.onUpdateMetadata(PortalMetadata(
                                    description = liveDescription.ifBlank { null }, tags = commaValues(liveTags),
                                    owner = liveOwner.ifBlank { null }, hide = liveHide,
                                ))
                            }, operable, Modifier.fillMaxWidth())
                        }
                    }
                    item {
                        ActivityPanel("relays", "Relay management · ${snapshot?.relays?.size ?: 0}", activityPanel, { activityPanel = it }) {
                            Text("Add or remove relays while running. Public addresses may change as connections shift.", color = TextSecondary)
                            snapshot?.relays.orEmpty().forEach { relay -> RelayRow(relay, running, operable, actions.onRemoveRelay) }
                            if (running) {
                                PortalField(newRelay, { newRelay = it }, "Relay address to add", "Enter one relay address to connect.", operable)
                                ActionButton("Add relay", { actions.onAddRelay(newRelay.trim()) },
                                    operable && newRelay.isNotBlank(), Modifier.fillMaxWidth())
                            } else Text("Set start relays in Settings.", color = Mint)
                        }
                    }
                    item {
                        ActivityPanel("identity", "Device identity", activityPanel, { activityPanel = it }) {
                            if (identity == null) Text("No identity to show yet. One is created on first start or a saved identity is used.", color = TextSecondary)
                            else SelectionContainer {
                                Text("Name: ${identity.name}\nAddress: ${identity.address}", color = Mint,
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Generate a new identity to inspect. Does not change the running publish's identity.", color = TextSecondary)
                            ActionButton("Generate new identity", actions.onGenerateIdentity, editable, Modifier.fillMaxWidth())
                        }
                    }
                    item {
                        ActivityPanel("events", "Event history · ${eventLog.size}", activityPanel, { activityPanel = it }) {
                            if (eventLog.isEmpty()) Text("No history yet. Connection activity will appear here.", color = TextSecondary)
                            else SelectionContainer {
                                Text(eventLog.joinToString("\n"), color = TextSecondary,
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        ActivityPanel("diagnostics", "Diagnostics", activityPanel, { activityPanel = it }) {
                            diagnostics?.let { d ->
                                SelectionContainer {
                                    Text("SDK ${d.sdkVersion} · ABI ${d.abiVersion} · wire schema ${d.wireSchemaVersion}\n" +
                                        "Active sessions ${d.activeSessions} · orphan drops ${d.droppedOrphanEvents}",
                                        color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace)
                                }
                            } ?: Text("Load SDK and session info when troubleshooting.", color = TextSecondary)
                            ActionButton("Load diagnostics", actions.onDiagnostics, !busy, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

private fun commaValues(value: String): List<String>? =
    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }

@Composable
private fun PublishHero(name: String, phase: TunnelPhase?, compact: Boolean) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF19394A), Surface1)))) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhaseBadge(phase)
            Text(if (compact) "Publishing from this device" else "This device is\nthe game's start.",
                fontSize = if (compact) 24.sp else 32.sp, lineHeight = 39.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text(name.ifBlank { "My Snake game" }, color = Mint,
                style = MaterialTheme.typography.titleMedium)
            if (!compact) {
                GameIllustration()
                Text("Built-in Snake game · preview", color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun GameIllustration() {
    Canvas(Modifier.fillMaxWidth().height(146.dp).semantics {
        contentDescription = "Snake game preview: mint snake and food on a dark board"
    }) {
        drawRoundRect(Bg.copy(alpha = 0.75f), cornerRadius = CornerRadius(18.dp.toPx()))
        val cell = 17.dp.toPx()
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
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        when (index) {
            0 -> {
                drawRoundRect(color, Offset(size.width * 0.12f, size.height * 0.19f),
                    Size(size.width * 0.76f, size.height * 0.62f), CornerRadius(5.dp.toPx()), style = Stroke(stroke))
                drawLine(color, Offset(size.width * 0.28f, size.height * 0.5f), Offset(size.width * 0.5f, size.height * 0.5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.39f, size.height * 0.39f), Offset(size.width * 0.39f, size.height * 0.61f), stroke, StrokeCap.Round)
                drawCircle(color, 1.5.dp.toPx(), Offset(size.width * 0.7f, size.height * 0.46f))
            }
            1 -> for (i in 0..2) {
                val y = size.height * (0.25f + i * 0.25f)
                drawLine(color, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), stroke, StrokeCap.Round)
                drawCircle(Bg, 3.dp.toPx(), Offset(size.width * (if (i == 1) 0.65f else 0.35f), y))
                drawCircle(color, 3.dp.toPx(), Offset(size.width * (if (i == 1) 0.65f else 0.35f), y), style = Stroke(stroke))
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
        Text(label, color = color, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ActivityPanel(key: String, title: String, selected: String, onSelect: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)) {
        TextButton(onClick = { onSelect(if (selected == key) "" else key) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(if (selected == key) "Collapse" else "Expand", color = Cyan)
        }
        if (selected == key) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun PortalField(value: String, onChange: (String) -> Unit, label: String, hint: String,
    enabled: Boolean, isError: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
            enabled = enabled, isError = isError, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan,
                unfocusedBorderColor = Surface2, disabledTextColor = TextSecondary,
                disabledLabelColor = TextSecondary, disabledBorderColor = Surface2))
        Text(hint, color = if (isError) Danger else TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingRow(label: String, hint: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, fontWeight = FontWeight.Medium, color = TextPrimary)
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
        modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RelayRow(relay: PortalRelayStatus, running: Boolean, operable: Boolean, onRemove: (String) -> Unit) {
    Surface(color = Bg, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    modelState: ModelDownload.State? = null,
    engineStatus: OnDeviceModelContent.EngineStatus? = null,
    onDownloadModel: (() -> Unit)? = null,
    onSelect: (String) -> Unit
) {
    val active = selected == content.id
    Surface(
        color = if (active) Cyan.copy(alpha = 0.12f) else Bg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().toggleable(
            value = active,
            enabled = enabled,
            role = Role.RadioButton,
            onValueChange = { onSelect(content.id) }
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(content.title,
                color = if (active) Cyan else TextPrimary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge)
            Text(content.summary, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            content.detail?.let {
                Text(it, color = Mint, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (modelState != null) {
                when (modelState) {
                    is ModelDownload.State.NotDownloaded -> {
                        Text("No model installed — a tiny built-in model answers until one lands.",
                            color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        if (onDownloadModel != null) {
                            ActionButton("Download model (~140 MB)", onDownloadModel,
                                enabled = enabled, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is ModelDownload.State.Downloading -> {
                        Text("Downloading model… ${(modelState.progress * 100).toInt()}%",
                            color = Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                    is ModelDownload.State.Ready -> {
                        Text("Model ready — real LLM inference on this device.",
                            color = Mint, style = MaterialTheme.typography.labelSmall)
                    }
                    is ModelDownload.State.Failed -> {
                        Text("Download failed: ${modelState.message}",
                            color = Danger, style = MaterialTheme.typography.labelSmall)
                        if (onDownloadModel != null) {
                            ActionButton("Retry download", onDownloadModel,
                                enabled = enabled, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (engineStatus != null) {
                when (val s = engineStatus) {
                    is OnDeviceModelContent.EngineStatus.Loading ->
                        Text("Loading model on ${s.backend}… (first load can take a minute)",
                            color = Cyan, style = MaterialTheme.typography.labelSmall)
                    is OnDeviceModelContent.EngineStatus.Ready ->
                        Text("Engine ready: ${s.backend}",
                            color = Mint, style = MaterialTheme.typography.labelSmall)
                    OnDeviceModelContent.EngineStatus.LowMemory ->
                        Text("Not enough free memory — using built-in fallback model.",
                            color = Danger, style = MaterialTheme.typography.labelSmall)
                    OnDeviceModelContent.EngineStatus.Failed ->
                        Text("Model failed to load — using built-in fallback model.",
                            color = Danger, style = MaterialTheme.typography.labelSmall)
                    OnDeviceModelContent.EngineStatus.Idle -> {}
                }
            }
        }
    }
}
