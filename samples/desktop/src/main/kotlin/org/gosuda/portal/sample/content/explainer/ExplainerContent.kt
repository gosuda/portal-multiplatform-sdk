package org.gosuda.portal.sample.content.explainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import org.gosuda.portal.sample.content.SampleEnv
import java.io.File

/**
 * "How Portal works" explainer page served as a static site. Assets live in
 * `resources/site-explainer/` — the desktop analogue of the Android
 * `assets/site-explainer` payload.
 */
object ExplainerContent : PublishableContent {
    override val id = "explainer"
    override val title = "How Portal works"
    override val summary = "Explains the device → relay → visitor flow"
    override val detail = "static_dir → resources/site-explainer"

    private const val RESOURCE_DIR = "site-explainer"
    private val FILES = listOf("index.html")

    private lateinit var siteDir: File

    override suspend fun start() {
        siteDir = withContext(Dispatchers.IO) {
            val out = File(SampleEnv.appDir, "portal-public/$RESOURCE_DIR")
            out.mkdirs()
            FILES.forEach { SampleEnv.copyResource("/$RESOURCE_DIR/$it", out, it) }
            out
        }
    }

    override fun stop() = Unit

    override fun baseConfig(): PortalConfig =
        PortalConfig.staticSite(siteDir.absolutePath, index = "index.html")
}
