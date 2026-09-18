package org.gosuda.portal.sample

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalTunnel
import java.io.File

/**
 * End-to-end example: persist an identity, host a static site from app
 * storage, publish it ready-on-return, observe the authoritative snapshot,
 * stop from the owner scope.
 */
class StaticSiteActivity : Activity() {
    // PortalClient(context) defaults identity_path to filesDir/identity.json —
    // persistent across relaunch with no caller-managed path. Lazy because
    // applicationContext is only valid after attach().
    private val client by lazy { PortalClient(applicationContext) }
    private var tunnel: PortalTunnel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownerScope.launch { startTunnel() }
    }

    private suspend fun startTunnel() {
        try {
            // 1. Public content lives in a dedicated directory — never serve
            //    filesDir itself or a secrets directory.
            val siteDir = File(filesDir, "portal-public/site").apply { mkdirs() }
            assets.open("site/index.html").use { input ->
                File(siteDir, "index.html").outputStream().use { input.copyTo(it) }
            }

            // 2. Publish: returns only after the tunnel reports ACTIVE and
            //    rolls the session back if readiness fails. The intent
            //    factory sets only the fields this mode needs.
            val t = client.publish(
                PortalConfig.staticSite(
                    siteDir.absolutePath,
                    index = "index.html",
                    name = "kmp-sample"
                ).copy(
                    maxActiveRelays = 2,
                    description = "Hosted from a KMP app"
                )
            )
            tunnel = t
            println("public: ${t.publicUrl}")

            // 3. Observe authoritative state for live updates.
            ownerScope.launch {
                t.state.collect { s ->
                    println("phase=${s.phase} url=${s.primaryPublicUrl} " +
                        "relays=${s.relays.count { it.isReady }} warn=${s.hasSecurityWarning}")
                }
            }
        } catch (e: PortalException) {
            // Structured failure: e.failure.code / .operation / .retryable /
            // .terminalPhase / .readinessFailure — no message parsing.
            println("portal error ${e.code}: ${e.message}")
        }
    }

    // Advanced path retained for reference: manage the identity document
    // yourself (Keystore-wrapped storage, never logged) and use open() when
    // accepted-before-ready observation is required.
    @Suppress("unused")
    private suspend fun advancedOpen() {
        val identityFile = File(filesDir, "portal-secrets/identity.json")
        val identity = if (identityFile.exists()) {
            PortalIdentity.parse(identityFile.readText())
        } else {
            PortalIdentity.generate("kmp-sample").also {
                identityFile.parentFile?.mkdirs()
                identityFile.writeText(it.document)
            }
        }
        val t = client.open(
            PortalConfig(
                name = "kmp-sample",
                identityJson = identity.document,
                staticDir = File(filesDir, "portal-public/site").absolutePath,
                staticIndex = "index.html",
                discovery = true
            )
        )
        // open() returns before readiness — observe CONNECTING live.
        t.awaitReady(org.gosuda.portal.Capability.STATIC_SITE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // Stop from the still-alive owner scope — not lifecycleScope,
            // which may already be cancelled here.
            ownerScope.launch {
                tunnel?.stop()
                client.close()
            }
        }
    }
}
