package org.gosuda.portal.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.gosuda.portal.Capability
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalEvent
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.PortalTunnel
import java.io.File

/**
 * Feature-rich Portal sample: config editor, identity management, session
 * controls (start/stop/refresh/awaitReady), live metadata + relay updates,
 * an event log, and diagnostics — all over the authoritative snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {

    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = PortalClient()

    private val tunnel = MutableStateFlow<PortalTunnel?>(null)

    val snapshot: StateFlow<PortalSnapshot?> =
        tunnel.flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(ownerScope, SharingStarted.Eagerly, null)

    val lastError = MutableStateFlow<String?>(null)
    val identity = MutableStateFlow<PortalIdentity?>(null)
    val eventLog = MutableStateFlow<List<String>>(emptyList())
    val diagnostics = MutableStateFlow<PortalDiagnostics?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeEvents()
        setContent {
            PortalSampleTheme {
                val snap by snapshot.collectAsState()
                val error by lastError.collectAsState()
                val id by identity.collectAsState()
                val log by eventLog.collectAsState()
                val diag by diagnostics.collectAsState()
                SampleScreen(
                    snapshot = snap,
                    lastError = error,
                    identity = id,
                    eventLog = log,
                    diagnostics = diag,
                    actions = actions()
                )
            }
        }
    }

    private fun actions() = SampleActions(
        onStart = ::startTunnel,
        onStop = ::stopTunnel,
        onRefresh = ::refresh,
        onAwaitReady = ::awaitReady,
        onGenerateIdentity = ::generateIdentity,
        onUpdateMetadata = ::updateMetadata,
        onAddRelay = ::addRelay,
        onRemoveRelay = ::removeRelay,
        onDiagnostics = ::loadDiagnostics,
    )

    // ---- actions -----------------------------------------------------------

    private fun startTunnel(config: PortalConfig) {
        ownerScope.launch {
            lastError.value = null
            try {
                val siteDir = extractSite()
                // The engine loads or creates the identity at this path; the
                // process CWD is read-only on Android, so use filesDir.
                val resolved = config.copy(
                    identityPath = config.identityPath
                        ?: File(filesDir, "identity.json").absolutePath,
                    staticDir = config.staticDir ?: siteDir.absolutePath,
                    staticIndex = config.staticIndex ?: "index.html"
                )
                tunnel.value = client.open(resolved)
            } catch (e: PortalException) {
                lastError.value = "start failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun stopTunnel() {
        val t = tunnel.value ?: return
        ownerScope.launch {
            try {
                t.stop()
            } catch (e: PortalException) {
                lastError.value = "stop failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun refresh() {
        val t = tunnel.value ?: return
        ownerScope.launch {
            try {
                t.refresh()
            } catch (e: PortalException) {
                lastError.value = "refresh failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun awaitReady() {
        val t = tunnel.value ?: return
        ownerScope.launch {
            try {
                t.awaitReady(Capability.STATIC_SITE, timeoutMillis = 15_000)
            } catch (e: PortalException) {
                lastError.value = "awaitReady failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun generateIdentity() {
        ownerScope.launch {
            try {
                identity.value = PortalIdentity.generate("kmp-sample")
            } catch (e: PortalException) {
                lastError.value = "identity failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun updateMetadata(metadata: PortalMetadata) {
        val t = tunnel.value ?: return
        ownerScope.launch {
            try {
                t.updateMetadata(metadata)
            } catch (e: PortalException) {
                lastError.value = "metadata failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun addRelay(url: String) {
        val t = tunnel.value ?: return
        ownerScope.launch {
            try {
                t.addRelay(url)
            } catch (e: PortalException) {
                lastError.value = "addRelay failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun removeRelay(url: String) {
        val t = tunnel.value ?: return
        ownerScope.launch {
            try {
                t.removeRelay(url)
            } catch (e: PortalException) {
                lastError.value = "removeRelay failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun loadDiagnostics() {
        diagnostics.value = client.diagnostics()
    }

    // ---- plumbing ----------------------------------------------------------

    private fun observeEvents() {
        ownerScope.launch {
            tunnel.flatMapLatest { it?.events ?: flowOf(null) }
                .collect { event ->
                    if (event != null) appendLog(describe(event))
                }
        }
    }

    private fun appendLog(line: String) {
        eventLog.value = (eventLog.value + line).takeLast(50)
    }

    private fun describe(event: PortalEvent): String = when (event) {
        is PortalEvent.Started -> "STARTED ${event.name}"
        is PortalEvent.Stopped -> "STOPPED"
        is PortalEvent.StatusChanged ->
            "STATUS_CHANGED active=${event.status.active} relays=${event.status.relays.size}"
        is PortalEvent.MitmSuspected -> "MITM_SUSPECTED ${event.relayUrl}"
        is PortalEvent.Error -> "ERROR ${event.message}"
        is PortalEvent.Unknown -> "UNKNOWN ${event.type}"
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
        if (isFinishing) {
            ownerScope.launch {
                tunnel.value?.stop()
                client.close()
                ownerScope.cancel()
            }
        }
    }
}


