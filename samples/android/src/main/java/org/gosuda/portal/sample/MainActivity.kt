package org.gosuda.portal.sample

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalTunnel
import java.io.File

/**
 * Minimal Portal sample: extracts a static site from assets, exposes it via a
 * tunnel owned by a client that outlives the screen, and renders the
 * authoritative snapshot. The tunnel keeps running across rotation; the Stop
 * button is the explicit owner-driven shutdown.
 */
class MainActivity : Activity() {

    // The owner scope outlives individual UI collectors; the client owns the
    // native session, not the screen.
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = PortalClient()
    private var tunnel: PortalTunnel? = null

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
            // Re-collect whenever a new tunnel handle exists.
            while (true) {
                val t = tunnel ?: run {
                    statusView.text = "idle"
                    kotlinx.coroutines.delay(500)
                    return@launch
                }
                t.state.collect { snapshot ->
                    statusView.text = "phase=${snapshot.phase} rev=${snapshot.revision}" +
                        if (snapshot.hasSecurityWarning) "  SECURITY WARNING" else ""
                    urlView.text = snapshot.primaryPublicUrl ?: "no public url yet"
                    relaysView.text = snapshot.relays.joinToString("\n") {
                        "${it.relayUrl} [${it.state}] ${it.publicUrl ?: ""}"
                    }
                    snapshot.lastFailure?.let {
                        statusView.append("\nlast failure: ${it.code} ${it.message}")
                    }
                }
            }
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
                tunnel = client.open(config)
                observeTunnel()
            } catch (e: org.gosuda.portal.PortalException) {
                statusView.text = "start failed: ${e.code} ${e.message}"
            }
        }
    }

    private fun stopTunnel() {
        val t = tunnel ?: return
        // Stop from the owner scope, not a UI-bound scope that may be cancelled.
        ownerScope.launch {
            try {
                t.stop()
            } catch (e: org.gosuda.portal.PortalException) {
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
                tunnel?.stop()
                client.close()
                ownerScope.cancel()
            }
        }
    }
}
