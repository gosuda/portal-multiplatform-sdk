package org.gosuda.portal.sample.content.snake

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import org.gosuda.portal.sample.content.SampleEnv
import java.io.File

/**
 * Built-in Snake game served as a static site (`static_dir`). Assets live in
 * `resources/site/` and are extracted to the app dir on start — the desktop
 * analogue of the Android `assets/site` payload.
 */
object SnakeGameContent : PublishableContent {
    override val id = "snake"
    override val title = "Snake game"
    override val summary = "Static HTML5 game bundled in the app"
    override val detail = "static_dir → resources/site"

    private const val RESOURCE_DIR = "site"
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
