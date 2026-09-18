package org.gosuda.portal.sample

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
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDesktop
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalEvent
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.PortalTunnel
import org.gosuda.portal.sample.content.PublishableContent
import org.gosuda.portal.sample.content.SampleContents
import org.gosuda.portal.sample.content.SampleEnv
import org.gosuda.portal.sample.content.ondevice.OnDeviceModelContent
import java.io.File

/**
 * Desktop application state + actions — the analogue of Android's
 * `SampleApp`/`MainActivity` merged. Owns the process-wide [PortalClient],
 * the current tunnel, and every action the screen invokes. The tunnel
 * outlives the window: closing to the tray keeps it alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortalApp {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Process-wide client; created lazily so a missing engine doesn't
     *  crash the app before the user publishes. */
    val client: PortalClient by lazy {
        PortalDesktop.client("org.gosuda.portal.sample.desktop")
    }

    val tunnel = MutableStateFlow<PortalTunnel?>(null)
    val busy = MutableStateFlow(false)
    val keepAlive = MutableStateFlow(SampleSettings.getBoolean("keepAlive", false))
    val lastError = MutableStateFlow<String?>(null)
    val identity = MutableStateFlow<PortalIdentity?>(null)
    val eventLog = MutableStateFlow<List<String>>(emptyList())
    val diagnostics = MutableStateFlow<PortalDiagnostics?>(null)

    /** Content whose local payload is live for the current tunnel. */
    private var activeContent: PublishableContent? = null

    val snapshot: StateFlow<PortalSnapshot?> =
        tunnel.flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, tunnel.value?.state?.value)

    init {
        observeEvents()
    }

    // ---- actions -----------------------------------------------------------

    fun startTunnel(config: PortalConfig, contentId: String) {
        if (busy.value || tunnel.value?.state?.value?.isTerminal == false) return
        busy.value = true
        scope.launch {
            lastError.value = null
            val content = SampleContents.byId(contentId)
            try {
                content.start()
                val resolved = content.applyTo(config.copy(
                    identityPath = resolveIdentityFile(config)
                ))
                tunnel.value = client.open(resolved)
                activeContent = content
            } catch (e: kotlinx.coroutines.CancellationException) {
                content.stop()
                throw e
            } catch (e: Exception) {
                content.stop()
                lastError.value = "Could not start publishing: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun stopTunnel() {
        val t = tunnel.value ?: return
        if (busy.value) return
        busy.value = true
        scope.launch {
            try {
                t.stop()
                lastError.value = null
            } catch (e: PortalException) {
                lastError.value = "Could not stop. Try again: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun refresh() {
        val t = tunnel.value ?: return
        scope.launch {
            try {
                t.refresh()
            } catch (e: PortalException) {
                lastError.value = "refresh failed: ${e.code} ${e.message}"
            }
        }
    }

    fun awaitReady() {
        val t = tunnel.value ?: return
        scope.launch {
            try {
                t.awaitActive(timeoutMillis = 15_000)
            } catch (e: PortalException) {
                lastError.value = "awaitReady failed: ${e.code} ${e.message}"
            }
        }
    }

    fun generateIdentity() {
        scope.launch {
            try {
                identity.value = PortalIdentity.generate("kmp-sample")
            } catch (e: PortalException) {
                lastError.value = "identity failed: ${e.code} ${e.message}"
            }
        }
    }

    fun updateMetadata(metadata: PortalMetadata) {
        val t = tunnel.value ?: return
        scope.launch {
            try {
                t.updateMetadata(metadata)
            } catch (e: PortalException) {
                lastError.value = "metadata failed: ${e.code} ${e.message}"
            }
        }
    }

    fun addRelay(url: String) {
        val t = tunnel.value ?: return
        scope.launch {
            try {
                t.addRelay(url)
            } catch (e: PortalException) {
                lastError.value = "addRelay failed: ${e.code} ${e.message}"
            }
        }
    }

    fun removeRelay(url: String) {
        val t = tunnel.value ?: return
        scope.launch {
            try {
                t.removeRelay(url)
            } catch (e: PortalException) {
                lastError.value = "removeRelay failed: ${e.code} ${e.message}"
            }
        }
    }

    fun loadDiagnostics() {
        diagnostics.value = runCatching { client.diagnostics() }.getOrNull()
    }

    fun setKeepAlive(enabled: Boolean) {
        keepAlive.value = enabled
        SampleSettings.putBoolean("keepAlive", enabled)
    }

    fun onModelChanged() = OnDeviceModelContent.onModelChanged()

    // ---- plumbing ----------------------------------------------------------

    /**
     * The public URL is `<identity-name>.<relay-domain>`, so the name field
     * only takes effect when the saved identity's name differs — a fresh
     * identity is generated and written over the file. An empty name keeps
     * whatever is on disk (or lets the engine create one).
     */
    private suspend fun resolveIdentityFile(config: PortalConfig): String {
        val path = config.identityPath
            ?: File(SampleEnv.appDir, "identity.json").absolutePath
        val wanted = config.name?.trim().orEmpty()
        if (wanted.isEmpty()) return path
        return withContext(Dispatchers.Default) {
            val file = File(path)
            val current = runCatching {
                if (file.isFile) PortalIdentity.parse(file.readText()).name else null
            }.getOrNull()
            if (current != wanted) {
                val fresh = PortalIdentity.generate(wanted)
                file.parentFile?.mkdirs()
                file.writeText(fresh.document)
            }
            path
        }
    }

    private fun observeEvents() {
        scope.launch {
            tunnel.flatMapLatest { it?.events ?: flowOf(null) }
                .collect { event -> if (event != null) appendLog(describe(event)) }
        }
        // Stop the content's local payload when the session ends for any
        // reason (user stop, native failure, process teardown).
        scope.launch {
            tunnel.flatMapLatest { it?.state ?: flowOf(null) }
                .collect { snap ->
                    if (snap?.isTerminal == true) {
                        activeContent?.stop()
                        activeContent = null
                    }
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
        is PortalEvent.RelayAdded -> "RELAY_ADDED ${event.relayUrl}"
        is PortalEvent.RelayRemoved -> "RELAY_REMOVED ${event.relayUrl}"
        is PortalEvent.Error -> "ERROR ${event.message}"
        is PortalEvent.Unknown -> "UNKNOWN ${event.type}"
    }

    fun shutdown() {
        scope.cancel()
    }
}
