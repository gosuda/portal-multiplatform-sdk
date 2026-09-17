package org.gosuda.portal.sample

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.gosuda.portal.Capability
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalEvent
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.PortalTunnel
import java.io.File
import org.gosuda.portal.lifecycle.PortalClientHolder
import org.gosuda.portal.sample.content.PublishableContent
import org.gosuda.portal.sample.content.SampleContents

/**
 * Feature-rich Portal sample: config editor, identity management, session
 * controls (start/stop/refresh/awaitReady), live metadata + relay updates,
 * an event log, and diagnostics — all over the authoritative snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {

    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client get() = PortalClientHolder.client

    private val app get() = application as SampleApp
    private val tunnel get() = app.tunnel

    val snapshot: StateFlow<PortalSnapshot?> by lazy {
        tunnel.flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(ownerScope, SharingStarted.Eagerly, tunnel.value?.state?.value)
    }

    val lastError get() = app.lastError
    val identity = MutableStateFlow<PortalIdentity?>(null)
    val eventLog = MutableStateFlow<List<String>>(emptyList())
    val diagnostics = MutableStateFlow<PortalDiagnostics?>(null)
    /** Content whose local payload is live for the current tunnel. */
    private var activeContent: PublishableContent? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) lastError.value = "Notification permission denied. Manage connection and stop from the app."
    }

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
                val busy by app.busy.collectAsState()
                val keepAlive by app.keepAlive.collectAsState()
                SampleScreen(
                    snapshot = snap,
                    lastError = error,
                    identity = id,
                    eventLog = log,
                    diagnostics = diag,
                    busy = busy,
                    keepAlive = keepAlive,
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
        onKeepAlive = ::setKeepAlive,
    )

    private fun setKeepAlive(enabled: Boolean) {
        if (app.busy.value) return
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            if (enabled && tunnel.value?.state?.value?.isTerminal == false) {
                startForegroundService(Intent(this, KeepAliveService::class.java))
            } else if (!enabled) {
                stopService(Intent(this, KeepAliveService::class.java))
            }
            app.keepAlive.value = enabled
        } catch (e: RuntimeException) {
            lastError.value = "Could not start background execution: ${e.message}"
            app.keepAlive.value = false
        }
    }

    // ---- actions -----------------------------------------------------------

    private fun startTunnel(config: PortalConfig, contentId: String) {
        if (app.busy.value || tunnel.value?.state?.value?.isTerminal == false) return
        app.busy.value = true
        app.scope.launch {
            lastError.value = null
            val content = SampleContents.byId(contentId)
            try {
                if (app.keepAlive.value) {
                    startForegroundService(Intent(this@MainActivity, KeepAliveService::class.java))
                }
                content.start(this@MainActivity)
                val resolved = content.applyTo(config.copy(
                    identityPath = config.identityPath ?: File(filesDir, "identity.json").absolutePath
                ), this@MainActivity)
                tunnel.value = client.open(resolved)
                activeContent = content
            } catch (e: CancellationException) {
                content.stop()
                throw e
            } catch (e: Exception) {
                content.stop()
                lastError.value = "Could not start publishing: ${e.message}"
                stopService(Intent(this@MainActivity, KeepAliveService::class.java))
            } finally {
                app.busy.value = false
            }
        }
    }

    private fun stopTunnel() {
        val t = tunnel.value ?: return
        if (app.busy.value) return
        app.busy.value = true
        app.scope.launch {
            try {
                t.stop()
                stopService(Intent(this@MainActivity, KeepAliveService::class.java))
                lastError.value = null
            } catch (e: PortalException) {
                lastError.value = "Could not stop. Try again: ${e.message}"
            } finally {
                app.busy.value = false
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
        // Stop the content's local payload when the session ends for any
        // reason (user stop, native failure, process teardown).
        ownerScope.launch {
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


    override fun onDestroy() {
        super.onDestroy()
        ownerScope.cancel()
    }
}


