package org.gosuda.portal.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Screen observation is Activity-owned; the client and operations are process-owned. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as SampleApp
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) app.lastError.value = "Notifications denied. Use the app to inspect and stop publishing."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val snapshot = app.tunnel.flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(observationScope, SharingStarted.Eagerly, app.tunnel.value?.state?.value)
        setContent {
            PortalSampleTheme {
                val snap by snapshot.collectAsState()
                val error by app.lastError.collectAsState()
                val failure by app.failure.collectAsState()
                val events by app.eventLog.collectAsState()
                val diagnostics by app.diagnostics.collectAsState()
                val busy by app.busy.collectAsState()
                val keepAlive by app.keepAlive.collectAsState()
                val demo by app.demoActivity.collectAsState()
                val endpoint by app.localEndpoint.collectAsState()
                val cachedModels by app.cachedModels.collectAsState()
                val download by app.modelStore.progress.collectAsState()
                val activeModelId by app.activeModelId.collectAsState()
                val cleanupPending by app.cleanupPending.collectAsState()
                val llmPassword by app.llmPassword.collectAsState()
                SampleScreen(
                    snapshot = snap, lastError = error, failure = failure,
                    eventLog = events, diagnostics = diagnostics, busy = busy,
                    keepAlive = keepAlive, demo = demo, localEndpoint = endpoint,
                    models = app.modelStore.models, cachedModels = cachedModels, download = download,
                    activeModelId = activeModelId, cleanupPending = cleanupPending,
                    llmPassword = llmPassword,
                    actions = SampleActions(
                        onStart = app::publish, onStop = app::stop,
                        onRefresh = app::refresh, onAwaitReady = app::awaitReady,
                        onUpdateMetadata = app::updateMetadata,
                        onAddRelay = app::addRelay, onRemoveRelay = app::removeRelay,
                        onDiagnostics = app::loadDiagnostics, onKeepAlive = ::setKeepAlive,
                        onDownloadModel = app::downloadModel, onCancelDownload = app::cancelModelDownload,
                        onLlmPasswordChange = app::setLlmPassword,
                        onGenerateLlmPassword = app::generateLlmPassword,
                    ),
                )
            }
        }
    }

    private fun setKeepAlive(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.setKeepAlive(enabled)
    }

    override fun onDestroy() {
        observationScope.cancel()
        super.onDestroy()
    }
}
