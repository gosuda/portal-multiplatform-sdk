package org.gosuda.portal.sample

import android.app.Application
import android.content.Intent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.gosuda.portal.sample.llm.LocalLlmServer
import org.gosuda.portal.sample.llm.LlmPassword
import org.gosuda.portal.sample.llm.ModelStore

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
    internal lateinit var modelStore: ModelStore
        private set
    internal val cachedModels = MutableStateFlow<Set<String>>(emptySet())
    internal val activeModelId = MutableStateFlow<String?>(null)
    internal val cleanupPending = MutableStateFlow(false)
    internal val llmPassword = MutableStateFlow(LlmPassword.generate())
    private var downloadJob: Job? = null
    private var llmServer: LocalLlmServer? = null
    private val releaseMutex = Mutex()
    private var activePayload: DemoPayload? = null
    private val client get() = PortalClientHolder.client

    override fun onCreate() {
        super.onCreate()
        PortalClientHolder.init(this)
        keepAlive.value = SampleSettings.getBoolean(this, "keepAlive", false)
        modelStore = ModelStore(File(filesDir, "local-llm-models"),
            assets.open("models.json").bufferedReader().use { it.readText() })
        scope.launch(Dispatchers.IO) { refreshCachedModels() }
        scope.launch {
            client.sessions.collect { owned ->
                // publish can throw after native stop fails. Its handle is still owned
                // here, including STOPPING; expose it rather than losing retry access.
                owned.lastOrNull()?.let { tunnel.value = it }
            }
        }
        scope.launch {
            tunnel.flatMapLatest { it?.state ?: flowOf(null) }.collect { snapshot ->
                if (snapshot?.isTerminal == true && !busy.value) {
                    busy.value = true
                    try { releasePayload() } finally { busy.value = false }
                }
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
        if (busy.value || cleanupPending.value || tunnel.value?.state?.value?.isTerminal == false) return
        val password = llmPassword.value
        if (request.mode == PublishMode.LOCAL_LLM && !LlmPassword.isValid(password)) {
            lastError.value = "Choose a password of 12–128 Unicode characters before publishing."
            return
        }
        busy.value = true
        scope.launch {
            clearFailure()
            demoActivity.value = DemoActivity()
            try {
                if (keepAlive.value) startForegroundService(Intent(this@SampleApp, KeepAliveService::class.java))
                val demoAddress = if (request.mode == PublishMode.LOCAL_LLM) {
                    val modelId = checkNotNull(request.modelId) { "Select a downloaded model first." }
                    val modelFile = withContext(Dispatchers.IO) { modelStore.cachedFile(modelId) }
                    checkNotNull(modelFile) { "Download this model before publishing." }
                    val server = withContext(Dispatchers.IO) {
                        LocalLlmServer(modelStore.model(modelId), modelFile, password) { name ->
                            assets.open(name).use { it.readBytes() }
                        }
                    }
                    llmServer = server
                    activeModelId.value = modelId
                    server.address
                } else if (request.builtIn && request.mode != PublishMode.STATIC_SITE) {
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
                lastError.value = if (request.mode == PublishMode.LOCAL_LLM)
                    "Could not load the local model or start its server (${e.javaClass.simpleName}). Check storage and device memory, then retry."
                else "Could not prepare publishing: ${e.message}"
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
        val current = tunnel.value
        if (current == null && !cleanupPending.value) return
        if (busy.value) return
        busy.value = true
        scope.launch {
            clearFailure()
            try {
                if (current?.state?.value?.isTerminal == false) current.stop()
                releasePayload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortalException) {
                failure.value = e.failure
                // Keep both handle and foreground owner alive for an explicit retry.
            } catch (e: Exception) {
                lastError.value = "Cleanup did not complete (${e.javaClass.simpleName}). Retry stop."
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

    private suspend fun releasePayload() = releaseMutex.withLock {
        try {
            llmServer?.let { server -> withContext(Dispatchers.IO) { server.close() } }
            llmServer = null
            activeModelId.value = null
            activePayload?.let { payload -> withContext(Dispatchers.IO) { payload.stop() } }
            activePayload = null
            cleanupPending.value = false
            localEndpoint.value = null
            stopKeepAliveService()
        } catch (e: CancellationException) {
            cleanupPending.value = true
            throw e
        } catch (e: Exception) {
            cleanupPending.value = true
            lastError.value = "Local server cleanup did not complete (${e.javaClass.simpleName}). Ownership is retained; retry stop."
        }
    }

    internal fun setLlmPassword(password: String) {
        if (busy.value || cleanupPending.value || tunnel.value?.state?.value?.isTerminal == false) return
        llmPassword.value = password
    }

    internal fun generateLlmPassword() {
        if (busy.value || cleanupPending.value || tunnel.value?.state?.value?.isTerminal == false) return
        llmPassword.value = LlmPassword.generate()
    }

    internal fun downloadModel(id: String) {
        if (downloadJob?.isActive == true || id in cachedModels.value) return
        downloadJob = scope.launch {
            try {
                modelStore.download(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ModelStore publishes the user-visible download failure.
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { refreshCachedModels() }
            }
        }
    }

    internal fun cancelModelDownload() = modelStore.cancelDownload()

    private fun refreshCachedModels() {
        cachedModels.value = modelStore.models.mapNotNull { model ->
            model.id.takeIf { modelStore.cachedFile(it) != null }
        }.toSet()
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
