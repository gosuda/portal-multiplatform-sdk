package org.gosuda.portal

import org.gosuda.portal.internal.DesktopNativeLibraryLoader
import org.gosuda.portal.internal.DesktopPortalEngine
import org.gosuda.portal.internal.NativeLibrarySource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the packaged runtime path end-to-end: the real
 * `META-INF/portal-native/` resources on the test classpath (from
 * `:portal-native-desktop`) are resolved, hash-verified, extracted to the
 * content-addressed cache, and loaded through JNA — no injected streams.
 */
class DesktopPackagedLoadTest {

    @Test
    fun packagedResourceResolvesAndLoads() {
        // Use the real classpath resources (reset the loader's test seams).
        DesktopNativeLibraryLoader.indexResourceStream = {
            DesktopNativeLibraryLoader::class.java.classLoader
                ?.getResourceAsStream("META-INF/portal-native/index.json")
        }
        DesktopNativeLibraryLoader.binaryResourceStream = { name ->
            DesktopNativeLibraryLoader::class.java.classLoader
                ?.getResourceAsStream("META-INF/portal-native/$name")
        }
        val tmp = Files.createTempDirectory("portal-packaged-test")
        DesktopNativeLibraryLoader.cacheRoot = { tmp }
        DesktopNativeLibraryLoader.sdkVersion = { "test-1.0" }
        DesktopNativeLibraryLoader.osName = { "Linux" }
        DesktopNativeLibraryLoader.osArch = { "amd64" }
        try {
            val resolved = DesktopNativeLibraryLoader.resolve(null)
            assertEquals(NativeLibrarySource.PACKAGED, resolved.source)
            assertTrue(resolved.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(Files.isRegularFile(resolved.path))
            assertTrue(resolved.path.toString().contains(resolved.sha256),
                "cache path is content-addressed: ${resolved.path}")

            // The packaged engine loads and answers a real call.
            val engine = DesktopPortalEngine(
                DesktopNativeLibraryLoader.load(null)
            )
            val identity = engine.generateIdentity("packaged-test")
            assertTrue(identity.contains("\"address\""))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
