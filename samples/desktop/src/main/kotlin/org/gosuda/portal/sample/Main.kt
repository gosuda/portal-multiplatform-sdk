package org.gosuda.portal.sample

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import org.gosuda.portal.sample.content.ondevice.OllamaModels

private val app = PortalApp()

fun main() = application {
    val windowState = rememberWindowState(width = 460.dp, height = 820.dp)
    var windowVisible by remember { mutableStateOf(true) }
    val keepAlive by app.keepAlive.collectAsState()
    val snapshot by app.snapshot.collectAsState()
    val lastError by app.lastError.collectAsState()
    val identity by app.identity.collectAsState()
    val eventLog by app.eventLog.collectAsState()
    val diagnostics by app.diagnostics.collectAsState()
    val busy by app.busy.collectAsState()

    // System tray: keeps the tunnel alive when the window is hidden — the
    // desktop analogue of Android's foreground-service keep-alive.
    if (keepAlive) {
        Tray(
            icon = trayIcon(),
            tooltip = "Portal — ${snapshot?.phase?.name ?: "idle"}",
            onAction = { windowVisible = true },
            menu = {
                Item("Show", onClick = { windowVisible = true })
                Item("Quit", onClick = {
                    app.shutdown()
                    exitApplication()
                })
            }
        )
    }

    if (windowVisible) {
        Window(
            onCloseRequest = {
                if (keepAlive) {
                    // Hide to tray; the tunnel keeps running.
                    windowVisible = false
                } else {
                    app.shutdown()
                    exitApplication()
                }
            },
            state = windowState,
            title = "Portal Desktop Sample",
            resizable = true
        ) {
            PortalSampleTheme {
                SampleScreen(
                    snapshot = snapshot,
                    lastError = lastError,
                    identity = identity,
                    eventLog = eventLog,
                    diagnostics = diagnostics,
                    busy = busy,
                    keepAlive = keepAlive,
                    actions = SampleActions(
                        onStart = app::startTunnel,
                        onStop = app::stopTunnel,
                        onRefresh = app::refresh,
                        onAwaitReady = app::awaitReady,
                        onGenerateIdentity = app::generateIdentity,
                        onUpdateMetadata = app::updateMetadata,
                        onAddRelay = app::addRelay,
                        onRemoveRelay = app::removeRelay,
                        onDiagnostics = app::loadDiagnostics,
                        onKeepAlive = app::setKeepAlive,
                        onModelChanged = app::onModelChanged,
                    )
                )
            }
        }
    }
}

/** A simple generated tray icon (cyan dot on dark). */
private fun trayIcon(): androidx.compose.ui.graphics.painter.Painter {
    val size = 22
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(size, size)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = androidx.compose.ui.graphics.Paint()
    paint.color = androidx.compose.ui.graphics.Color(0xFF080F1D)
    canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 6f, 6f, paint)
    paint.color = androidx.compose.ui.graphics.Color(0xFF64DCEC)
    canvas.drawCircle(
        androidx.compose.ui.geometry.Offset(size / 2f, size / 2f),
        size / 2f - 4f,
        paint
    )
    return androidx.compose.ui.graphics.painter.BitmapPainter(bitmap)
}
