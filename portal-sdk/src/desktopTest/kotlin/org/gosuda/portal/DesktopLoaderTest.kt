package org.gosuda.portal

import org.gosuda.portal.internal.DesktopArchitecture
import org.gosuda.portal.internal.DesktopNativeLibraryLoader
import org.gosuda.portal.internal.DesktopOs
import org.gosuda.portal.internal.NativeLibrarySource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Loader contract tests: platform selection, explicit-path validation,
 * packaged-resource hash verification, and cache integrity. Resource streams
 * and platform detection are injected through the loader's test seams.
 */
class DesktopLoaderTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("portal-loader-test")
        DesktopNativeLibraryLoader.cacheRoot = { tmp }
        DesktopNativeLibraryLoader.sdkVersion = { "test-1.0" }
        DesktopNativeLibraryLoader.osName = { "Linux" }
        DesktopNativeLibraryLoader.osArch = { "amd64" }
    }

    @AfterTest
    fun tearDown() {
        DesktopNativeLibraryLoader.cacheRoot = { error("unset") }
        DesktopNativeLibraryLoader.osName = { System.getProperty("os.name") ?: "" }
        DesktopNativeLibraryLoader.osArch = { System.getProperty("os.arch") ?: "" }
        tmp.toFile().deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun indexFor(target: String, file: String, sha: String): String =
        """{"abi_version":1,"libraries":{"$target":{"file":"$file","sha256":"$sha","size":1}}}"""

    @Test
    fun linuxX64SelectsLinuxResource() {
        val payload = "fake-elf".toByteArray()
        val sha = sha256(payload)
        DesktopNativeLibraryLoader.osName = { "Linux" }
        DesktopNativeLibraryLoader.osArch = { "x86_64" }
        DesktopNativeLibraryLoader.indexResourceStream = {
            ByteArrayInputStream(indexFor("linux-x64", "libportaltunnel.so", sha).toByteArray())
        }
        DesktopNativeLibraryLoader.binaryResourceStream = { name ->
            if (name == "linux-x64/libportaltunnel.so") ByteArrayInputStream(payload) else null
        }
        val resolved = DesktopNativeLibraryLoader.resolve(null)
        assertEquals(DesktopOs.LINUX, resolved.os)
        assertEquals(DesktopArchitecture.X86_64, resolved.architecture)
        assertEquals(NativeLibrarySource.PACKAGED, resolved.source)
        assertEquals(sha, resolved.sha256)
        assertTrue(resolved.path.toString().contains("linux-x64").not(),
            "cache path is content-addressed, not target-named: ${resolved.path}")
        assertTrue(Files.readAllBytes(resolved.path).contentEquals(payload))
    }

    @Test
    fun hashMismatchIsRejectedBeforeLoad() {
        DesktopNativeLibraryLoader.indexResourceStream = {
            ByteArrayInputStream(indexFor("linux-x64", "libportaltunnel.so", "0".repeat(64)).toByteArray())
        }
        DesktopNativeLibraryLoader.binaryResourceStream = {
            ByteArrayInputStream("tampered".toByteArray())
        }
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(null)
        }
        assertEquals(PortalFailure.Codes.NATIVE_UNAVAILABLE, ex.code)
        assertTrue(ex.message!!.contains("hash mismatch"))
    }

    @Test
    fun corruptCacheEntryIsReplaced() {
        val payload = "good-binary".toByteArray()
        val sha = sha256(payload)
        DesktopNativeLibraryLoader.indexResourceStream = {
            ByteArrayInputStream(indexFor("linux-x64", "libportaltunnel.so", sha).toByteArray())
        }
        DesktopNativeLibraryLoader.binaryResourceStream = { ByteArrayInputStream(payload) }

        // Pre-seed a corrupt cache entry at the content-addressed path.
        val cacheDir = tmp.resolve("portal-sdk").resolve("test-1.0").resolve(sha)
        Files.createDirectories(cacheDir)
        val target = cacheDir.resolve("libportaltunnel.so")
        Files.write(target, "corrupt".toByteArray())

        val resolved = DesktopNativeLibraryLoader.resolve(null)
        assertTrue(Files.readAllBytes(resolved.path).contentEquals(payload))
    }

    @Test
    fun unknownOsIsRejected() {
        DesktopNativeLibraryLoader.osName = { "Solaris" }
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(null)
        }
        assertEquals(PortalFailure.Codes.NATIVE_UNAVAILABLE, ex.code)
        assertTrue(ex.message!!.contains("Solaris"))
    }

    @Test
    fun unknownArchIsRejected() {
        DesktopNativeLibraryLoader.osArch = { "riscv64" }
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(null)
        }
        assertTrue(ex.message!!.contains("riscv64"))
    }

    @Test
    fun linuxArm64IsNotInFirstMatrix() {
        DesktopNativeLibraryLoader.osArch = { "aarch64" }
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(null)
        }
        assertTrue(ex.message!!.contains("arm64"))
    }

    @Test
    fun explicitPathMustExist() {
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(Path.of("/nonexistent/libportaltunnel.so"))
        }
        assertEquals(PortalFailure.Codes.NATIVE_UNAVAILABLE, ex.code)
    }

    @Test
    fun explicitPathMustMatchOsExtension() {
        val wrong = Files.createTempFile("libportaltunnel", ".dll")
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(wrong)
        }
        assertTrue(ex.message!!.contains(".so"))
    }

    @Test
    fun explicitPathIsUsedVerbatim() {
        val real = Files.createTempFile("libportaltunnel", ".so")
        Files.write(real, "explicit".toByteArray())
        val resolved = DesktopNativeLibraryLoader.resolve(real)
        assertEquals(NativeLibrarySource.EXPLICIT, resolved.source)
        assertEquals(real.toAbsolutePath().normalize(), resolved.path)
    }

    @Test
    fun missingPackagedIndexIsRejected() {
        DesktopNativeLibraryLoader.indexResourceStream = { null }
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(null)
        }
        assertTrue(ex.message!!.contains("index"))
    }

    @Test
    fun missingPackagedBinaryIsRejected() {
        val sha = "a".repeat(64)
        DesktopNativeLibraryLoader.indexResourceStream = {
            ByteArrayInputStream(indexFor("linux-x64", "libportaltunnel.so", sha).toByteArray())
        }
        DesktopNativeLibraryLoader.binaryResourceStream = { null }
        val ex = assertFailsWith<PortalException> {
            DesktopNativeLibraryLoader.resolve(null)
        }
        assertTrue(ex.message!!.contains("missing"))
    }
}
