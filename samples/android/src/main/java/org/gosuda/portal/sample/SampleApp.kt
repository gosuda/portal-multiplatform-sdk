package org.gosuda.portal.sample

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalDiagnostics
import org.gosuda.portal.PortalEvent
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import org.gosuda.portal.PortalMetadata
import org.gosuda.portal.PortalTunnel
import org.gosuda.portal.lifecycle.PortalClientHolder
import org.gosuda.portal.sample.content.DemoActivity
import org.gosuda.portal.sample.content.DemoPayload
import org.gosuda.portal.sample.content.PublishMode
import org.gosuda.portal.sample.content.PublishRequest

/** Process owns sessions and observations; Activity recreation never stops a tunnel. */
@OptIn(ExperimentalCoroutinesApi::class)
class SampleApp : Application() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val tunnel = MutableStateFlow<PortalTunnel?>(null)
    internal val busy = MutableStateFlow(false)
    internal val keepAlive = MutableStateFlow(false)
    internal val lastError = MutableStateFlow<String?>(null)
    internal val failure = MutableStateFlow<PortalFailure?>(null)
    internal val eventLog = MutableStateFlow<List<String>>(emptyList())
    internal val diagnostics = MutableStateFlow<PortalDiagnostics?>(null)
    internal val demoActivity = MutableStateFlow(DemoActivity())
    internal val localEndpoint = MutableStateFlow<String?>(null)
    private var activePayload: DemoPayload? = null
    private val client get() = PortalClientHolder.client

    override fun onCreate() {
        super.onCreate()
        PortalClientHolder.init(this)
        keepAlive.value = SampleSettings.getBoolean(this, "keepAlive", false)
        scope.launch {
            client.sessions.collect { owned ->
                // publish can throw after native stop fails. Its handle is still owned
                // here, including STOPPING; expose it rather than losing retry access.
                owned.lastOrNull()?.let { tunnel.value = it }
            }
        }
        scope.launch {
            tunnel.flatMapLatest { it?.state ?: flowOf(null) }.collect { snapshot ->
                if (snapshot?.isTerminal == true && !busy.value) releasePayload()
            }
        }
        scope.launch {
            tunnel.flatMapLatest { it?.events ?: flowOf(null) }.collect { event ->
                if (event != null) {
                    eventLog.value = (eventLog.value + describe(event)).takeLast(80)
                }
            }
        }
    }

    internal fun publish(request: PublishRequest) {
        if (busy.value || tunnel.value?.state?.value?.isTerminal == false) return
        busy.value = true
        scope.launch {
            clearFailure()
            demoActivity.value = DemoActivity()
            try {
                if (keepAlive.value) startForegroundService(Intent(this@SampleApp, KeepAliveService::class.java))
                val demoAddress = if (request.builtIn && request.mode != PublishMode.STATIC_SITE) {
                    val payload = DemoPayload(demoActivity)
                    activePayload = payload
                    withContext(Dispatchers.IO) { payload.start(request.mode) }
                } else null
                localEndpoint.value = demoAddress
                tunnel.value = client.publish(request.config(this@SampleApp, demoAddress, activePayload?.secondaryAddress))
            } catch (e: CancellationException) {
                recoverOwnedSession()
                throw e
            } catch (e: PortalException) {
                failure.value = e.failure
                recoverOwnedSession()
            } catch (e: Exception) {
                lastError.value = "Could not prepare publishing: ${e.message}"
                recoverOwnedSession()
            } finally {
                // Only release after actual stop; failed publish cleanup leaves a
                // client-owned STOPPING handle and its local listener available.
                if (client.sessions.value.none { !it.state.value.isTerminal }) releasePayload()
                busy.value = false
            }
        }
    }

    private fun recoverOwnedSession() {
        client.sessions.value.lastOrNull { !it.state.value.isTerminal }?.let { tunnel.value = it }
    }

    internal fun stop() {
        val current = tunnel.value ?: return
        if (busy.value) return
        busy.value = true
        scope.launch {
            clearFailure()
            try {
                current.stop()
                releasePayload()
            } catch (e: PortalException) {
                failure.value = e.failure
                // Keep both handle and foreground owner alive for an explicit retry.
            } finally {
                busy.value = false
            }
        }
    }

    internal fun refresh() = operate { it.refresh() }
    internal fun awaitReady() = operate { it.awaitActive(timeoutMillis = 15_000) }
    internal fun updateMetadata(metadata: PortalMetadata) = operate { it.updateMetadata(metadata) }
    internal fun addRelay(url: String) = operate { it.addRelay(url) }
    internal fun removeRelay(url: String) = operate { it.removeRelay(url) }

    private fun operate(action: suspend (PortalTunnel) -> Unit) {
        val current = tunnel.value ?: return
        if (busy.value || current.state.value.isTerminal) return
        busy.value = true
        scope.launch {
            clearFailure()
            try {
                action(current)
            } catch (e: PortalException) {
                failure.value = e.failure
            } finally {
                if (current.state.value.isTerminal) releasePayload()
                busy.value = false
            }
        }
    }

    internal fun loadDiagnostics() {
        diagnostics.value = client.diagnostics()
    }

    internal fun setKeepAlive(enabled: Boolean) {
        if (busy.value) return
        try {
            if (enabled && tunnel.value?.state?.value?.isTerminal == false) {
                startForegroundService(Intent(this, KeepAliveService::class.java))
            } else if (!enabled) {
                stopKeepAliveService()
            }
            keepAlive.value = enabled
            SampleSettings.putBoolean(this, "keepAlive", enabled)
        } catch (e: RuntimeException) {
            lastError.value = "Foreground execution unavailable: ${e.message}"
        }
    }

    private fun releasePayload() {
        activePayload?.stop()
        activePayload = null
        localEndpoint.value = null
        stopKeepAliveService()
    }

    private fun stopKeepAliveService() {
        stopService(Intent(this, KeepAliveService::class.java))
    }

    private fun clearFailure() {
        lastError.value = null
        failure.value = null
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
}
