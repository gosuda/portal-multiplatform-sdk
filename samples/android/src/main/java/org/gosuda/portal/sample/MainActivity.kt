package org.gosuda.portal.sample

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalSnapshot
import org.gosuda.portal.PortalTunnel
import java.io.File

/**
 * Minimal Portal sample: extracts a static site from assets, exposes it via a
 * tunnel owned by a client that outlives the screen, and renders the
 * authoritative snapshot. The tunnel keeps running across rotation; the Stop
 * button is the explicit owner-driven shutdown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : Activity() {

    // The owner scope outlives individual UI collectors; the client owns the
    // native session, not the screen.
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = PortalClient()

    // Current tunnel handle; the observer below re-collects on every change.
    private val tunnel = MutableStateFlow<PortalTunnel?>(null)

    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var relaysView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this)
        urlView = TextView(this)
        relaysView = TextView(this)
        val startButton = TextView(this).apply {
            text = "Start tunnel"
            setOnClickListener { startTunnel() }
        }
        val stopButton = TextView(this).apply {
            text = "Stop tunnel"
            setOnClickListener { stopTunnel() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(startButton)
            addView(stopButton)
            addView(statusView)
            addView(urlView)
            addView(relaysView)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        observeTunnel()
    }

    private fun observeTunnel() {
        ownerScope.launch {
            tunnel.flatMapLatest { it?.state ?: flowOf(null) }
                .collectLatest { snapshot -> render(snapshot) }
        }
    }

    private fun render(snapshot: PortalSnapshot?) {
        if (snapshot == null) {
            statusView.text = "idle"
            urlView.text = ""
            relaysView.text = ""
            return
        }
        statusView.text = buildString {
            append("phase=${snapshot.phase} rev=${snapshot.revision}")
            if (snapshot.hasSecurityWarning) append("  SECURITY WARNING")
            snapshot.lastFailure?.let { append("\nlast failure: ${it.code} ${it.message}") }
        }
        urlView.text = snapshot.primaryPublicUrl ?: "no public url yet"
        relaysView.text = snapshot.relays.joinToString("\n") {
            "${it.relayUrl} [${it.state}] ${it.publicUrl ?: ""}"
        }
    }

    private fun startTunnel() {
        ownerScope.launch {
            try {
                val siteDir = extractSite()
                val config = PortalConfig(
                    name = "kmp-sample",
                    staticDir = siteDir.absolutePath,
                    staticIndex = "index.html",
                    discovery = true,
                    description = "Portal KMP SDK sample"
                )
                tunnel.value = client.open(config)
            } catch (e: PortalException) {
                statusView.text = "start failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun stopTunnel() {
        val t = tunnel.value ?: return
        // Stop from the owner scope, not a UI-bound scope that may be cancelled.
        ownerScope.launch {
            try {
                t.stop()
            } catch (e: PortalException) {
                statusView.text = "stop failed: ${e.code} ${e.message}"
            }
        }
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
        // Observation ends with the screen; the tunnel keeps running until the
        // user stops it or the process dies. For a long-running tunnel, move
        // the client into a foreground service owner.
        if (isFinishing) {
            ownerScope.launch {
                tunnel.value?.stop()
                client.close()
                ownerScope.cancel()
            }
        }
    }
}
