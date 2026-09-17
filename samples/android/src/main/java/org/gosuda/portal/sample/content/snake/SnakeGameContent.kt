package org.gosuda.portal.sample.content.snake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import java.io.File

/**
 * Built-in Snake game served as a static site (`static_dir`).
 * Assets live in `assets/site/` and are extracted to filesDir on start.
 */
object SnakeGameContent : PublishableContent {
    override val id = "snake"
    override val title = "Snake game"
    override val summary = "Static HTML5 game bundled in the app"
    override val detail = "static_dir → assets/site"

    private const val ASSET_DIR = "site"

    private lateinit var siteDir: File

    override suspend fun start(context: Context) {
        siteDir = withContext(Dispatchers.IO) { Assets.extractAssets(context, ASSET_DIR) }
    }

    override fun stop() = Unit

    override fun applyTo(config: PortalConfig, context: Context): PortalConfig =
        config.copy(staticDir = siteDir.absolutePath, staticIndex = "index.html")

    object Assets {
        /** Extracts `assets/<assetDir>` into `filesDir/portal-public/<assetDir>`. */
        fun extractAssets(context: Context, assetDir: String): File {
            val out = File(context.filesDir, "portal-public/$assetDir")
            out.mkdirs()
            context.assets.list(assetDir)?.forEach { name ->
                context.assets.open("$assetDir/$name").use { input ->
                    File(out, name).outputStream().use { input.copyTo(it) }
                }
            }
            return out
        }
    }
}
