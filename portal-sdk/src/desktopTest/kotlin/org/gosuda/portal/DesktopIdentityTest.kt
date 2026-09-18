package org.gosuda.portal

import org.gosuda.portal.internal.DesktopOs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Desktop identity-path and facade contract: applicationId validation,
 * per-OS default paths, explicit override, PERMISSION_DENIED mapping, and
 * identity isolation between application IDs.
 */
class DesktopIdentityTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("portal-identity-test")
        DesktopIdentityPath.os = { DesktopOs.LINUX }
        DesktopIdentityPath.baseDirectory = { _, _ -> tmp }
    }

    @AfterTest
    fun tearDown() {
        DesktopIdentityPath.os = { error("unset") }
        DesktopIdentityPath.baseDirectory = { _, _ -> error("unset") }
        tmp.toFile().deleteRecursively()
    }

    // --- applicationId validation -------------------------------------------

    @Test
    fun blankApplicationIdIsRejected() {
        assertFailsWith<PortalException> { PortalDesktop.client("   ") }
        assertFailsWith<PortalException> { PortalDesktop.client("") }
    }

    @Test
    fun separatorsAndTraversalAreRejected() {
        for (bad in listOf("a/b", "a\\b", "..", ".", "a..b/c", "a b", "a:b")) {
            val ex = assertFailsWith<PortalException>(
                message = "expected rejection for '$bad'"
            ) { PortalDesktop.client(bad) }
            assertEquals(PortalFailure.Codes.INVALID_CONFIG, ex.code, "for '$bad'")
        }
    }

    @Test
    fun validApplicationIdsAreAccepted() {
        for (ok in listOf("myapp", "my.app", "my-app", "my_app", "App123", "a.b.c-d_e")) {
            // These resolve a path without throwing; the engine load is lazy.
            val path = DesktopIdentityPath.pathFor(ok, tmp)
            assertTrue(path.toString().endsWith("identity.json"), "for '$ok'")
        }
    }

    // --- per-OS default paths ------------------------------------------------

    @Test
    fun linuxDefaultPathIsExact() {
        DesktopIdentityPath.os = { DesktopOs.LINUX }
        DesktopIdentityPath.baseDirectory = { os, _ -> Path.of("/xdg-state") }
        val path = DesktopIdentityPath.pathFor("myapp", null)
        assertEquals(Path.of("/xdg-state/myapp/portal/identity.json"), path)
    }

    @Test
    fun windowsDefaultPathIsExact() {
        DesktopIdentityPath.os = { DesktopOs.WINDOWS }
        DesktopIdentityPath.baseDirectory = { os, _ -> Path.of("/localappdata") }
        val path = DesktopIdentityPath.pathFor("myapp", null)
        assertEquals(Path.of("/localappdata/myapp/Portal/identity.json"), path)
    }

    @Test
    fun macosDefaultPathIsExact() {
        DesktopIdentityPath.os = { DesktopOs.MACOS }
        DesktopIdentityPath.baseDirectory = { os, _ -> Path.of("/app-support") }
        val path = DesktopIdentityPath.pathFor("myapp", null)
        assertEquals(Path.of("/app-support/myapp/Portal/identity.json"), path)
    }

    @Test
    fun explicitStorageDirectoryWins() {
        val custom = tmp.resolve("custom-store")
        val path = DesktopIdentityPath.pathFor("myapp", custom)
        assertTrue(path.toString().startsWith(custom.toString()))
        assertTrue(path.toString().endsWith("identity.json"))
    }

    // --- failure mapping -----------------------------------------------------

    @Test
    fun directoryFailureMapsToPermissionDenied() {
        // Point the base at a path that cannot be created (a file, not a dir).
        val blocker = tmp.resolve("blocker")
        Files.write(blocker, "x".toByteArray())
        DesktopIdentityPath.baseDirectory = { _, _ -> blocker }
        val ex = assertFailsWith<PortalException> {
            DesktopIdentityPath.resolve("myapp", null)
        }
        assertEquals(PortalFailure.Codes.PERMISSION_DENIED, ex.code)
        assertEquals("identity_path", ex.failure.operation)
    }

    // --- isolation -----------------------------------------------------------

    @Test
    fun twoApplicationIdsNeverShareIdentityPath() {
        val a = DesktopIdentityPath.pathFor("app-a", tmp)
        val b = DesktopIdentityPath.pathFor("app-b", tmp)
        assertNotEquals(a, b)
        assertTrue(a.toString().contains("app-a"))
        assertTrue(b.toString().contains("app-b"))
    }

    // --- runtime diagnostics -------------------------------------------------

    @Test
    fun runtimeReportsPackagedSource() {
        val rt = PortalDesktop.runtime()
        assertEquals(DesktopOs.LINUX, rt.os)
        assertEquals(org.gosuda.portal.internal.DesktopArchitecture.X86_64, rt.architecture)
        assertEquals(org.gosuda.portal.internal.NativeLibrarySource.PACKAGED, rt.source)
        assertTrue(rt.nativeSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(rt.nativeLibraryPath.toString().endsWith(".so"))
    }
}
