package org.gosuda.portal.sample.content.explainer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gosuda.portal.PortalConfig
import org.gosuda.portal.sample.content.PublishableContent
import org.gosuda.portal.sample.content.snake.SnakeGameContent
import java.io.File

/**
 * "How Portal works" explainer page served as a static site.
 * Assets live in `assets/site-explainer/`.
 */
object ExplainerContent : PublishableContent {
    override val id = "explainer"
    override val title = "How Portal works"
    override val summary = "Explains the device → relay → visitor flow"
    override val detail = "static_dir → assets/site-explainer"

    private const val ASSET_DIR = "site-explainer"

    private lateinit var siteDir: File

    override suspend fun start(context: Context) {
        siteDir = withContext(Dispatchers.IO) {
            SnakeGameContent.Assets.extractAssets(context, ASSET_DIR)
        }
    }

    override fun stop() = Unit

    override fun applyTo(config: PortalConfig, context: Context): PortalConfig =
        config.copy(staticDir = siteDir.absolutePath, staticIndex = "index.html")
}
