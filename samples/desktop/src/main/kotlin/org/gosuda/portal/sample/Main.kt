package org.gosuda.portal.sample

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val app = remember { PortalApp() }
    val snapshot by app.snapshot.collectAsState()
    val lastError by app.lastError.collectAsState()
    val eventLog by app.eventLog.collectAsState()
    val diagnostics by app.diagnostics.collectAsState()
    val busy by app.busy.collectAsState()
    val localConfig by app.localConfig.collectAsState()
    val demoActivity by app.demoActivity.collectAsState()
    val downloadState by app.downloadState.collectAsState()
    val cachedModels by app.cachedModels.collectAsState()
    val llmPassword by app.llmPassword.collectAsState()
    Window(
        onCloseRequest = { app.shutdown { exitApplication() } },
        state = rememberWindowState(width = 1000.dp, height = 850.dp),
        title = "Portal SDK Workbench",
        icon = painterResource("icon.png"),
    ) {
        PortalSampleTheme {
            SampleScreen(snapshot, lastError, eventLog, diagnostics, busy, localConfig, demoActivity,
                SampleActions(app::startTunnel, app::stopTunnel, app::refresh, app::awaitReady,
                    app::updateMetadata, app::addRelay, app::removeRelay, app::loadDiagnostics,
                    app::downloadModel, app::cancelDownload, app::deleteModel,
                    app::updateLlmPassword, app::generateLlmPassword),
                app.models.models, downloadState, cachedModels, llmPassword)
        }
    }
}
