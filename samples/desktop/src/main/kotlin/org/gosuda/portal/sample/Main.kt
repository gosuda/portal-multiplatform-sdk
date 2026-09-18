package org.gosuda.portal.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalDesktop
import org.gosuda.portal.PortalTunnel
import org.gosuda.portal.TunnelPhase
import org.gosuda.portal.portalConfig
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.InetSocketAddress
import java.net.URI

private const val LOCAL_PORT = 8080
private const val APP_ID = "org.gosuda.portal.sample.desktop"

fun main() = application {
    val windowState = rememberWindowState(width = 720.dp, height = 560.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Portal Desktop Sample"
    ) {
        MaterialTheme {
            PortalSampleApp()
        }
    }
}

@Composable
private fun PortalSampleApp() {
    val scope = rememberCoroutineScope()

    var client by remember { mutableStateOf<PortalClient?>(null) }
    var tunnel by remember { mutableStateOf<PortalTunnel?>(null) }
    var status by remember { mutableStateOf("Starting local HTTP server on 127.0.0.1:$LOCAL_PORT…") }
    var publicUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    fun appendLog(line: String) {
        log = (log + line + "\n").takeLast(4000)
    }

    // Start the loopback HTTP server once.
    remember {
        startLoopbackServer(LOCAL_PORT) { appendLog("local: $it") }
        status = "Local server ready on 127.0.0.1:$LOCAL_PORT"
        true
    }

    val snapshot = tunnel?.state?.collectAsState()?.value

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Portal Desktop Sample", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Exposes a loopback HTTP server through a Portal relay. " +
                "The public URL serves the same content to anyone.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Text(status)
                snapshot?.let {
                    Text("Phase: ${it.phase}")
                    it.lastFailure?.let { f -> Text("Failure: ${f.code} ${f.message}") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && tunnel == null,
                onClick = {
                    busy = true
                    status = "Opening tunnel…"
                    scope.launch {
                        try {
                            val c = client ?: PortalDesktop.client(APP_ID).also { client = it }
                            val t = c.open(
                                portalConfig {
                                    setName("desktop-sample")
                                    setTargetAddress("127.0.0.1:$LOCAL_PORT")
                                    setDiscovery(true)
                                }
                            )
                            tunnel = t
                            status = "Waiting for public URL…"
                            val snap = t.awaitReady(org.gosuda.portal.Capability.HTTP_TLS)
                            publicUrl = snap.primaryPublicUrl
                            status = "Tunnel active"
                            appendLog("tunnel: active ${snap.primaryPublicUrl}")
                        } catch (e: Exception) {
                            status = "Open failed: ${e.message}"
                            appendLog("tunnel: open failed ${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Publish") }

            OutlinedButton(
                enabled = !busy && tunnel != null,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            tunnel?.stop()
                            status = "Tunnel stopped"
                            appendLog("tunnel: stopped")
                        } catch (e: Exception) {
                            status = "Stop failed: ${e.message}"
                        } finally {
                            tunnel = null
                            publicUrl = null
                            busy = false
                        }
                    }
                }
            ) { Text("Stop") }
        }

        publicUrl?.let { url ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Public URL", style = MaterialTheme.typography.titleSmall)
                    TextField(
                        value = url,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(url), null)
                            appendLog("copied $url")
                        }) { Text("Copy") }
                        OutlinedButton(onClick = {
                            runCatching {
                                Desktop.getDesktop().browse(URI(url))
                            }.onFailure { appendLog("browse failed: ${it.message}") }
                        }) { Text("Open in browser") }
                    }
                }
            }
        }

        if (log.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun startLoopbackServer(port: Int, onLog: (String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { exchange ->
        val body = """
            <html><body>
            <h1>Portal Desktop Sample</h1>
            <p>Served from a loopback HTTP server inside this desktop app.</p>
            <p>Path: ${exchange.requestURI.path}</p>
            </body></html>
        """.trimIndent().toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        onLog("GET ${exchange.requestURI.path} -> 200")
    }
    server.executor = null
    server.start()
}
