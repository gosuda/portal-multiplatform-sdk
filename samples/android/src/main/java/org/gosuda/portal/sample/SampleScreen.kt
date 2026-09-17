package org.gosuda.portal.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun PortalSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Portal Sample") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ConfigCard(snapshot, actions.onStart) }
            item { SessionCard(snapshot, lastError, actions) }
            item { IdentityCard(identity, actions.onGenerateIdentity) }
            item { PublicUrlCard(snapshot) }
            item { MetadataCard(snapshot, actions.onUpdateMetadata) }
            item { RelayManagerCard(snapshot, actions) }
            item { EventLogCard(eventLog) }
            item { DiagnosticsCard(diagnostics, actions.onDiagnostics) }
        }
    }
}

// ---- config ----------------------------------------------------------------

@Composable
private fun ConfigCard(snapshot: PortalSnapshot?, onStart: (PortalConfig) -> Unit) {
    var name by remember { mutableStateOf("kmp-sample") }
    var discovery by remember { mutableStateOf(true) }
    var udp by remember { mutableStateOf(false) }
    var tcp by remember { mutableStateOf(false) }
    var ech by remember { mutableStateOf(false) }
    var banMitm by remember { mutableStateOf(false) }
    var hide by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("Portal KMP SDK sample") }
    var tags by remember { mutableStateOf("demo,kmp") }

    val editable = snapshot == null || snapshot.isTerminal

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Tunnel config", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("name") }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("description") }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = tags, onValueChange = { tags = it },
                label = { Text("tags (comma-separated)") }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            FlagRow("discovery", discovery, editable) { discovery = it }
            FlagRow("udp", udp, editable) { udp = it }
            FlagRow("tcp", tcp, editable) { tcp = it }
            FlagRow("ech", ech, editable) { ech = it }
            FlagRow("ban_mitm", banMitm, editable) { banMitm = it }
            FlagRow("hide", hide, editable) { hide = it }
            Spacer(Modifier.height(8.dp))
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
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start tunnel") }
        }
    }
}

@Composable
private fun FlagRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// ---- session ---------------------------------------------------------------

@Composable
private fun SessionCard(
    snapshot: PortalSnapshot?,
    lastError: String?,
    actions: SampleActions
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseChip(snapshot?.phase)
                Spacer(Modifier.weight(1f))
                snapshot?.let {
                    Text(
                        "rev ${it.revision} · dropped ${it.droppedEventCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (snapshot?.hasSecurityWarning == true) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "MITM suspected on a relay — treat endpoints as untrusted",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            snapshot?.lastFailure?.let { failure ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "${failure.code}: ${failure.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions.onStop,
                    enabled = snapshot != null && !snapshot.isTerminal
                ) { Text("Stop") }
                OutlinedButton(
                    onClick = actions.onRefresh,
                    enabled = snapshot != null && !snapshot.isTerminal
                ) { Text("Refresh") }
                OutlinedButton(
                    onClick = actions.onAwaitReady,
                    enabled = snapshot != null && !snapshot.isTerminal
                ) { Text("Await ready") }
            }
        }
    }
}

@Composable
private fun PhaseChip(phase: TunnelPhase?) {
    val (label, color) = when (phase) {
        null -> "idle" to MaterialTheme.colorScheme.surfaceVariant
        TunnelPhase.ACTIVE -> "active" to MaterialTheme.colorScheme.tertiaryContainer
        TunnelPhase.FAILED -> "failed" to MaterialTheme.colorScheme.errorContainer
        TunnelPhase.STOPPED -> "stopped" to MaterialTheme.colorScheme.surfaceVariant
        else -> phase.name.lowercase() to MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---- identity ---------------------------------------------------------------

@Composable
private fun IdentityCard(identity: PortalIdentity?, onGenerate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Identity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (identity == null) {
                Text(
                    "No identity generated. The tunnel creates one at identity_path on first start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    Text(
                        "name=${identity.name}\naddress=${identity.address}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onGenerate) { Text("Generate identity") }
        }
    }
}

// ---- public url --------------------------------------------------------------

@Composable
private fun PublicUrlCard(snapshot: PortalSnapshot?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Public URL", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    snapshot?.primaryPublicUrl ?: "no public url yet",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (snapshot != null && snapshot.publicUrls.size > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "+${snapshot.publicUrls.size - 1} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Metadata (live update)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("description") }, enabled = active,
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = tags, onValueChange = { tags = it },
                label = { Text("tags") }, enabled = active,
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = owner, onValueChange = { owner = it },
                label = { Text("owner") }, enabled = active,
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            FlagRow("hide", hide, active) { hide = it }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onUpdate(
                        PortalMetadata(
                            description = description.ifBlank { null },
                            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                .ifEmpty { null },
                            owner = owner.ifBlank { null },
                            hide = hide
                        )
                    )
                },
                enabled = active,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Update metadata") }
        }
    }
}

// ---- relays ------------------------------------------------------------------

@Composable
private fun RelayManagerCard(snapshot: PortalSnapshot?, actions: SampleActions) {
    var newRelay by remember { mutableStateOf("") }
    val active = snapshot != null && !snapshot.isTerminal

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Relays (${snapshot?.relays?.size ?: 0})",
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            snapshot?.relays.orEmpty().forEach { relay ->
                RelayRow(relay, active, actions.onRemoveRelay)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRelay, onValueChange = { newRelay = it },
                    label = { Text("relay url") }, enabled = active,
                    modifier = Modifier.weight(1f), singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { actions.onAddRelay(newRelay); newRelay = "" },
                    enabled = active && newRelay.isNotBlank()
                ) { Text("Add") }
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relay.relayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                RelayStateChip(relay)
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { onRemove(relay.relayUrl) },
                    enabled = active
                ) { Text("×") }
            }
            relay.publicUrl?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            relay.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RelayStateChip(relay: PortalRelayStatus) {
    val color = when {
        relay.isMitm -> MaterialTheme.colorScheme.errorContainer
        relay.isReady -> MaterialTheme.colorScheme.tertiaryContainer
        relay.isFailed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            if (relay.isMitm) "mitm" else relay.state,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// ---- events ------------------------------------------------------------------

@Composable
private fun EventLogCard(eventLog: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Events", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (eventLog.isEmpty()) {
                Text("no events yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    eventLog.forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ---- diagnostics --------------------------------------------------------------

@Composable
private fun DiagnosticsCard(diagnostics: PortalDiagnostics?, onLoad: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            diagnostics?.let { d ->
                Text(
                    "sdk=${d.sdkVersion} abi=${d.abiVersion} wire=${d.wireSchemaVersion}\n" +
                        "sessions=${d.activeSessions} orphanDrops=${d.droppedOrphanEvents}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLoad) { Text("Load diagnostics") }
        }
    }
}
