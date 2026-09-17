package org.gosuda.portal.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalRelayStatus
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.TunnelPhase

/** Action callbacks wired by [MainActivity]. */
data class SampleActions(
    val onStart: (PortalConfig) -> Unit,
    val onStop: () -> Unit,
    val onRefresh: () -> Unit,
    val onAwaitReady: () -> Unit,
    val onGenerateIdentity: () -> Unit,
    val onUpdateMetadata: (PortalMetadata) -> Unit,
    val onAddRelay: (String) -> Unit,
    val onRemoveRelay: (String) -> Unit,
    val onDiagnostics: () -> Unit,
)

// ---- palette ---------------------------------------------------------------

private val Bg = Color(0xFF0B1020)
private val Surface1 = Color(0xFF141A33)
private val Surface2 = Color(0xFF1B2342)
private val Violet = Color(0xFF7C4DFF)
private val Blue = Color(0xFF448AFF)
private val Mint = Color(0xFF69F0AE)
private val TextPrimary = Color(0xFFE8ECF8)
private val TextSecondary = Color(0xFF8A93B8)
private val Danger = Color(0xFFFF6B6B)

private val PortalColors = darkColorScheme(
    primary = Violet,
    secondary = Blue,
    tertiary = Mint,
    background = Bg,
    surface = Surface1,
    surfaceVariant = Surface2,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    errorContainer = Color(0xFF3A1A24),
    onErrorContainer = Color(0xFFFFB4B4),
    primaryContainer = Surface2,
    secondaryContainer = Surface2,
    tertiaryContainer = Color(0xFF14352A),
)

@Composable
fun PortalSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PortalColors, content = content)
}

// ---- screen ----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleScreen(
    snapshot: PortalSnapshot?,
    lastError: String?,
    identity: PortalIdentity?,
    eventLog: List<String>,
    diagnostics: PortalDiagnostics?,
    actions: SampleActions
) {
    Scaffold(containerColor = Bg) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeroHeader(snapshot) }
            item { SessionCard(snapshot, lastError, actions) }
            item { PublicUrlCard(snapshot) }
            item { ConfigCard(snapshot, actions.onStart) }
            item { IdentityCard(identity, actions.onGenerateIdentity) }
            item { MetadataCard(snapshot, actions.onUpdateMetadata) }
            item { RelayManagerCard(snapshot, actions) }
            item { EventLogCard(eventLog) }
            item { DiagnosticsCard(diagnostics, actions.onDiagnostics) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---- hero ------------------------------------------------------------------

@Composable
private fun HeroHeader(snapshot: PortalSnapshot?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Violet, Blue)))
            .padding(24.dp)
    ) {
        Column {
            Text(
                "PORTAL",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (snapshot?.phase) {
                    null -> "Ready to tunnel"
                    TunnelPhase.ACTIVE -> "Live on the network"
                    TunnelPhase.FAILED -> "Tunnel failed"
                    TunnelPhase.STOPPED -> "Tunnel stopped"
                    else -> "Working…"
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                snapshot?.primaryPublicUrl ?: "Serve a site or game from this device",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ---- session ---------------------------------------------------------------

@Composable
private fun SessionCard(
    snapshot: PortalSnapshot?,
    lastError: String?,
    actions: SampleActions
) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseChip(snapshot?.phase)
                Spacer(Modifier.weight(1f))
                snapshot?.let {
                    Text(
                        "rev ${it.revision} · dropped ${it.droppedEventCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            if (snapshot?.hasSecurityWarning == true) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "MITM suspected on a relay — treat endpoints as untrusted",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            snapshot?.lastFailure?.let { failure ->
                Spacer(Modifier.height(8.dp))
                Text("${failure.code}: ${failure.message}",
                    style = MaterialTheme.typography.bodySmall, color = Danger)
            }
            lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Danger)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Stop", actions.onStop,
                    enabled = snapshot != null && !snapshot.isTerminal, modifier = Modifier.weight(1f))
                ActionButton("Refresh", actions.onRefresh,
                    enabled = snapshot != null && !snapshot.isTerminal, modifier = Modifier.weight(1f))
                ActionButton("Await", actions.onAwaitReady,
                    enabled = snapshot != null && !snapshot.isTerminal, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PhaseChip(phase: TunnelPhase?) {
    val (label, color, textColor) = when (phase) {
        null -> Triple("idle", Surface2, TextSecondary)
        TunnelPhase.ACTIVE -> Triple("active", Mint, Color(0xFF0B1020))
        TunnelPhase.FAILED -> Triple("failed", Danger, Color.White)
        TunnelPhase.STOPPED -> Triple("stopped", Surface2, TextSecondary)
        else -> Triple(phase.name.lowercase(), Blue, Color.White)
    }
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextPrimary,
            disabledContentColor = TextSecondary.copy(alpha = 0.4f)
        )
    ) { Text(label, fontSize = 13.sp) }
}

// ---- config ----------------------------------------------------------------

@Composable
private fun ConfigCard(snapshot: PortalSnapshot?, onStart: (PortalConfig) -> Unit) {
    var name by remember { mutableStateOf("snake-game") }
    var discovery by remember { mutableStateOf(true) }
    var udp by remember { mutableStateOf(false) }
    var tcp by remember { mutableStateOf(false) }
    var ech by remember { mutableStateOf(false) }
    var banMitm by remember { mutableStateOf(false) }
    var hide by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("Snake served from this device") }
    var tags by remember { mutableStateOf("game,snake") }

    val editable = snapshot == null || snapshot.isTerminal

    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("Tunnel config")
            PortalField(name, { name = it }, "name", editable)
            PortalField(description, { description = it }, "description", editable)
            PortalField(tags, { tags = it }, "tags (comma-separated)", editable)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                FlagChip("discovery", discovery, editable, Modifier.weight(1f)) { discovery = it }
                FlagChip("udp", udp, editable, Modifier.weight(1f)) { udp = it }
                FlagChip("tcp", tcp, editable, Modifier.weight(1f)) { tcp = it }
            }
            Row(Modifier.fillMaxWidth()) {
                FlagChip("ech", ech, editable, Modifier.weight(1f)) { ech = it }
                FlagChip("ban_mitm", banMitm, editable, Modifier.weight(1f)) { banMitm = it }
                FlagChip("hide", hide, editable, Modifier.weight(1f)) { hide = it }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onStart(
                        PortalConfig(
                            name = name.ifBlank { null },
                            discovery = discovery,
                            udp = udp, tcp = tcp, ech = ech,
                            banMitm = banMitm, hide = hide,
                            description = description.ifBlank { null },
                            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                .ifEmpty { null }
                        )
                    )
                },
                enabled = editable,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Violet,
                    contentColor = Color.White,
                    disabledContainerColor = Surface2,
                    disabledContentColor = TextSecondary
                )
            ) { Text("Start tunnel", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun PortalField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextSecondary) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Violet,
            unfocusedBorderColor = Surface2,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextSecondary,
            disabledBorderColor = Surface2
        )
    )
}

@Composable
private fun FlagChip(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = Violet,
                uncheckedColor = TextSecondary,
                checkmarkColor = Color.White
            )
        )
        Text(label, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = TextPrimary)
    }
}

// ---- identity ---------------------------------------------------------------

@Composable
private fun IdentityCard(identity: PortalIdentity?, onGenerate: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("Identity")
            if (identity == null) {
                Text(
                    "No identity generated. The tunnel creates one at identity_path on first start.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary
                )
            } else {
                SelectionContainer {
                    Text(
                        "name=${identity.name}\naddress=${identity.address}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Mint
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("Generate identity", onGenerate, enabled = true)
        }
    }
}

// ---- public url --------------------------------------------------------------

@Composable
private fun PublicUrlCard(snapshot: PortalSnapshot?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface2),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("PUBLIC URL", style = MaterialTheme.typography.labelMedium,
                color = TextSecondary, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    snapshot?.primaryPublicUrl ?: "no public url yet",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (snapshot?.primaryPublicUrl != null) Mint else TextSecondary
                )
            }
            if (snapshot != null && snapshot.publicUrls.size > 1) {
                Spacer(Modifier.height(4.dp))
                Text("+${snapshot.publicUrls.size - 1} more",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

// ---- metadata ----------------------------------------------------------------

@Composable
private fun MetadataCard(snapshot: PortalSnapshot?, onUpdate: (PortalMetadata) -> Unit) {
    var description by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var hide by remember { mutableStateOf(false) }
    val active = snapshot != null && !snapshot.isTerminal

    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("Metadata (live update)")
            PortalField(description, { description = it }, "description", active)
            PortalField(tags, { tags = it }, "tags", active)
            PortalField(owner, { owner = it }, "owner", active)
            FlagChip("hide", hide, active) { hide = it }
            Spacer(Modifier.height(8.dp))
            ActionButton("Update metadata", enabled = active, onClick = {
                onUpdate(
                    PortalMetadata(
                        description = description.ifBlank { null },
                        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .ifEmpty { null },
                        owner = owner.ifBlank { null },
                        hide = hide
                    )
                )
            })
        }
    }
}

// ---- relays ------------------------------------------------------------------

@Composable
private fun RelayManagerCard(snapshot: PortalSnapshot?, actions: SampleActions) {
    var newRelay by remember { mutableStateOf("") }
    val active = snapshot != null && !snapshot.isTerminal

    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("Relays (${snapshot?.relays?.size ?: 0})")
            snapshot?.relays.orEmpty().forEach { relay ->
                RelayRow(relay, active, actions.onRemoveRelay)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRelay, onValueChange = { newRelay = it },
                    label = { Text("relay url", color = TextSecondary) },
                    enabled = active,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Violet,
                        unfocusedBorderColor = Surface2,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.width(8.dp))
                ActionButton("Add", enabled = active && newRelay.isNotBlank(),
                    onClick = { actions.onAddRelay(newRelay); newRelay = "" })
            }
        }
    }
}

@Composable
private fun RelayRow(
    relay: PortalRelayStatus,
    active: Boolean,
    onRemove: (String) -> Unit
) {
    Surface(color = Surface2, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relay.relayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                RelayStateChip(relay)
                Spacer(Modifier.width(6.dp))
                ActionButton("×", enabled = active, onClick = { onRemove(relay.relayUrl) })
            }
            relay.publicUrl?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            relay.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Danger)
            }
        }
    }
}

@Composable
private fun RelayStateChip(relay: PortalRelayStatus) {
    val (label, color) = when {
        relay.isMitm -> "mitm" to Danger
        relay.isReady -> "ready" to Mint
        relay.isFailed -> "failed" to Danger
        else -> relay.state to Surface2
    }
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (color == Mint || color == Danger) Color(0xFF0B1020) else TextSecondary
        )
    }
}

// ---- events ------------------------------------------------------------------

@Composable
private fun EventLogCard(eventLog: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("Events")
            if (eventLog.isEmpty()) {
                Text("no events yet", style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0A0E1E))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    eventLog.forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = Mint)
                    }
                }
            }
        }
    }
}

// ---- diagnostics --------------------------------------------------------------

@Composable
private fun DiagnosticsCard(diagnostics: PortalDiagnostics?, onLoad: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("Diagnostics")
            diagnostics?.let { d ->
                Text(
                    "sdk=${d.sdkVersion} abi=${d.abiVersion} wire=${d.wireSchemaVersion}\n" +
                        "sessions=${d.activeSessions} orphanDrops=${d.droppedOrphanEvents}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
            }
            ActionButton("Load diagnostics", onLoad, enabled = true)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}
