package org.gosuda.portal.sample

import java.awt.EventQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalDesktop
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalEvent
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalTunnel

/** One desktop window owns one client. Closing only exits after owned sessions are stopped. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortalApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clientDelegate: Lazy<PortalClient> = lazy { PortalDesktop.client("org.gosuda.portal.sample.desktop") }
    private val client: PortalClient by clientDelegate
    val tunnel = MutableStateFlow<PortalTunnel?>(null)
    val busy = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val eventLog = MutableStateFlow<List<String>>(emptyList())
    val diagnostics = MutableStateFlow<PortalDiagnostics?>(null)
    private val payload = MutableStateFlow<LocalDemo?>(null)
    val snapshot = tunnel.flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val demoActivity = payload.flatMapLatest { it?.activity ?: flowOf(LocalDemo.Activity()) }
        .stateIn(scope, SharingStarted.Eagerly, LocalDemo.Activity())
    val localConfig = MutableStateFlow<org.gosuda.portal.PortalConfig?>(null)

    init {
        scope.launch {
            tunnel.flatMapLatest { it?.events ?: flowOf(null) }.collect { event ->
                if (event != null) eventLog.value = (eventLog.value + describe(event)).takeLast(40)
            }
        }
        scope.launch {
            tunnel.flatMapLatest { it?.state ?: flowOf(null) }.collect { state ->
                if (state?.isTerminal == true && !busy.value) releasePayload()
            }
        }
    }

    fun startTunnel(request: WorkbenchRequest) {
        if (busy.value || tunnel.value?.state?.value?.isTerminal == false) return
        busy.value = true
        scope.launch {
            try {
                lastError.value = null
                releasePayload()
                check(payload.value == null) { "Previous local demo cleanup failed; close the window to retry." }
                val prepared = withContext(Dispatchers.IO) {
                    LocalDemo.prepare(request).also { payload.value = it }
                }
                localConfig.value = prepared.config
                tunnel.value = client.publish(prepared.config)
            } catch (failure: CancellationException) {
                withContext(NonCancellable) { recoverPublish() }
                throw failure
            } catch (failure: Exception) {
                recoverPublish()
                showFailure("Publish", failure)
            } finally {
                if (tunnel.value?.state?.value?.isTerminal == true) releasePayload()
                busy.value = false
            }
        }
    }

    private fun recoverPublish() {
        val retained = if (clientDelegate.isInitialized())
            client.sessions.value.firstOrNull { !it.state.value.isTerminal } else null
        tunnel.value = retained
        // publish_cleanup retains ownership and the local service until a successful stop retry.
        if (retained == null) releasePayload()
    }

    @Synchronized
    private fun releasePayload() {
        val owned = payload.value ?: return
        try {
            owned.close()
            payload.value = null
        } catch (failure: Exception) { showFailure("Local demo cleanup", failure) }
    }

    fun stopTunnel() = operate("Stop (retry is safe)") {
        tunnel.value?.stop()
        releasePayload()
    }
    fun refresh() = operate("Refresh") { tunnel.value?.refresh() }
    fun awaitReady() = operate("Await active") { tunnel.value?.awaitActive(15_000) }
    fun updateMetadata(metadata: PortalMetadata) = operate("Metadata") { tunnel.value?.updateMetadata(metadata) }
    fun addRelay(url: String) = operate("Add relay") { tunnel.value?.addRelay(url.trim()) }
    fun removeRelay(url: String) = operate("Remove relay") { tunnel.value?.removeRelay(url.trim()) }
    fun loadDiagnostics() = operate("Diagnostics") { diagnostics.value = client.diagnostics() }

    private fun operate(label: String, operation: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        scope.launch {
            try { lastError.value = null; operation() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { showFailure(label, failure) }
            finally {
                if (tunnel.value?.state?.value?.isTerminal == true) releasePayload()
                busy.value = false
            }
        }
    }

    private fun showFailure(label: String, failure: Exception) {
        lastError.value = if (failure is PortalException) {
            val detail = failure.failure
            "$label: ${detail.code} [${detail.operation ?: "SDK"}, retryable=${detail.retryable}] ${detail.message}" +
                (detail.readinessFailure?.let { "\nReadiness: ${it.code}: ${it.message}" } ?: "")
        } else "$label: ${failure.message ?: failure.javaClass.simpleName}"
    }

    private fun describe(event: PortalEvent): String = when (event) {
        is PortalEvent.Started -> "STARTED ${event.name}"
        is PortalEvent.Stopped -> "STOPPED"
        is PortalEvent.StatusChanged -> "STATUS_CHANGED active=${event.status.active} relays=${event.status.relays.size}"
        is PortalEvent.MitmSuspected -> "MITM_SUSPECTED ${event.relayUrl}"
        is PortalEvent.RelayAdded -> "RELAY_ADDED ${event.relayUrl}"
        is PortalEvent.RelayRemoved -> "RELAY_REMOVED ${event.relayUrl}"
        is PortalEvent.Error -> "ERROR ${event.message}"
        is PortalEvent.Unknown -> "UNKNOWN ${event.type}"
    }

    fun shutdown(onClosed: () -> Unit) = operate("Close (retry window close or Stop)") {
        if (clientDelegate.isInitialized()) client.close()
        releasePayload()
        if (payload.value == null) {
            EventQueue.invokeLater(onClosed)
            scope.cancel()
        }
    }
}
