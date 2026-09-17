package org.gosuda.portal.sample

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.gosuda.portal.Capability
import org.gosuda.portal.PortalClient
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalIdentity
import org.gosuda.portal.PortalTunnel
import java.io.File

/**
 * End-to-end example: persist an identity, host a static site from app
 * storage, observe the authoritative snapshot, stop from the owner scope.
 */
class StaticSiteActivity : Activity() {

    // Owner scope outlives UI collectors; the client owns native sessions.
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = PortalClient()
    private var tunnel: PortalTunnel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownerScope.launch { startTunnel() }
    }

    private suspend fun startTunnel() {
        try {
            // 1. Identity: reuse a stored document or generate once. The
            //    document is secret — store it Keystore-wrapped, never log it.
            val identityFile = File(filesDir, "portal-secrets/identity.json")
            val identity = if (identityFile.exists()) {
                PortalIdentity.parse(identityFile.readText())
            } else {
                PortalIdentity.generate("kmp-sample").also {
                    identityFile.parentFile?.mkdirs()
                    identityFile.writeText(it.document)
                }
            }

            // 2. Public content lives in a dedicated directory — never serve
            //    filesDir itself or the secrets directory.
            val siteDir = File(filesDir, "portal-public/site").apply { mkdirs() }
            assets.open("site/index.html").use { input ->
                File(siteDir, "index.html").outputStream().use { input.copyTo(it) }
            }

            // 3. Open: returns once ownership is registered.
            val t = client.open(
                PortalConfig(
                    name = "kmp-sample",
                    identityJson = identity.document,
                    staticDir = siteDir.absolutePath,
                    staticIndex = "index.html",
                    discovery = true,
                    maxActiveRelays = 2,
                    description = "Hosted from a KMP app"
                )
            )
            tunnel = t

            // 4. Observe authoritative state.
            ownerScope.launch {
                t.state.collect { s ->
                    println("phase=${s.phase} url=${s.primaryPublicUrl} " +
                        "relays=${s.relays.count { it.isReady }} warn=${s.hasSecurityWarning}")
                }
            }

            // 5. Or suspend until the site is actually reachable.
            val ready = t.awaitReady(Capability.STATIC_SITE)
            println("public: ${ready.primaryPublicUrl}")
        } catch (e: PortalException) {
            println("portal error ${e.code}: ${e.message}")
        }
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
