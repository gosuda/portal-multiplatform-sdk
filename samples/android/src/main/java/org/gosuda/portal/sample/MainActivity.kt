package org.gosuda.portal.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalRelayStatus
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.PortalTunnel
import org.gosuda.portal.TunnelPhase
import java.io.File

/**
 * Portal sample: extracts a static site from assets, exposes it through a
 * tunnel owned by a client that outlives the screen, and renders the
 * authoritative [PortalSnapshot] with Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {

    // The owner scope outlives individual UI collectors; the client owns the
    // native session, not the screen.
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = PortalClient()

    private val tunnel = MutableStateFlow<PortalTunnel?>(null)

    // Snapshot of the active tunnel, or null while idle. flatMapLatest
    // re-collects whenever a new handle is installed.
    private val snapshot: StateFlow<PortalSnapshot?> =
        tunnel.flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(ownerScope, SharingStarted.Eagerly, null)

    private val lastError = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalSampleTheme {
                val snap by snapshot.collectAsState()
                val error by lastError.collectAsState()
                SampleScreen(
                    snapshot = snap,
                    lastError = error,
                    onStart = ::startTunnel,
                    onStop = ::stopTunnel
                )
            }
        }
    }

    private fun startTunnel() {
        ownerScope.launch {
            lastError.value = null
            try {
                val siteDir = extractSite()
                // The engine loads or creates the identity at this path; the
                // process CWD is read-only on Android, so use filesDir.
                val config = PortalConfig(
                    name = "kmp-sample",
                    identityPath = File(filesDir, "identity.json").absolutePath,
                    staticDir = siteDir.absolutePath,
                    staticIndex = "index.html",
                    discovery = true,
                    description = "Portal KMP SDK sample"
                )
                tunnel.value = client.open(config)
            } catch (e: PortalException) {
                lastError.value = "start failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun stopTunnel() {
        val t = tunnel.value ?: return
        // Stop from the owner scope, not a UI-bound scope that may be cancelled.
        ownerScope.launch {
            try {
                t.stop()
            } catch (e: PortalException) {
                lastError.value = "stop failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun extractSite(): File {
        val out = File(filesDir, "portal-public/site")
        out.mkdirs()
        assets.list("site")?.forEach { name ->
            assets.open("site/$name").use { input ->
                File(out, name).outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        // Observation ends with the screen; the tunnel keeps running until the
        // user stops it or the process dies. For a long-running tunnel, move
        // the client into a foreground service owner.
        if (isFinishing) {
            ownerScope.launch {
                tunnel.value?.stop()
                client.close()
                ownerScope.cancel()
            }
        }
    }
}

@Composable
private fun PortalSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen(
    snapshot: PortalSnapshot?,
    lastError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ControlsCard(snapshot, lastError, onStart, onStop) }
            item { PublicUrlCard(snapshot) }
            item {
                Text(
                    "Relays (${snapshot?.relays?.size ?: 0})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(snapshot?.relays.orEmpty()) { relay -> RelayCard(relay) }
        }
    }
}

@Composable
private fun ControlsCard(
    snapshot: PortalSnapshot?,
    lastError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseChip(snapshot?.phase)
                Spacer(Modifier.weight(1f))
                snapshot?.let {
                    Text(
                        "rev ${it.revision}",
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
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStart,
                    enabled = snapshot == null || snapshot.isTerminal
                ) { Text("Start tunnel") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = snapshot != null && !snapshot.isTerminal
                ) { Text("Stop tunnel") }
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
        }
    }
}

@Composable
private fun RelayCard(relay: PortalRelayStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relay.relayUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                RelayStateChip(relay)
            }
            relay.publicUrl?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            relay.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
