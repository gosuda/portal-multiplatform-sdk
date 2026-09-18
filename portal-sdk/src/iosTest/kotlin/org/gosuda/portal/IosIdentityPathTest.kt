package org.gosuda.portal

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gosuda.portal.internal.IosIdentityPath
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.timeIntervalSince1970

/**
 * iOS identity-path contract: the platform-owned default resolves under
 * Application Support, failures surface as structured `PERMISSION_DENIED`
 * with `operation="identity_path"`, and the resolved path reaches the engine
 * unchanged. Completions fire on `Dispatchers.Main`, so tests pump the main
 * run loop instead of suspending.
 */
class IosIdentityPathTest {

    private lateinit var baseDir: String
    private lateinit var savedBase: () -> String?
    private lateinit var savedEnsure: (String) -> Boolean

    @BeforeTest
    fun setUp() {
        baseDir = NSTemporaryDirectory() + "portal-ios-identity-test"
        savedBase = IosIdentityPath.applicationSupportDirectory
        savedEnsure = IosIdentityPath.ensureDirectory
        IosIdentityPath.applicationSupportDirectory = { baseDir }
    }

    @AfterTest
    fun tearDown() {
        IosIdentityPath.applicationSupportDirectory = savedBase
        IosIdentityPath.ensureDirectory = savedEnsure
    }

    @Test
    fun defaultPathResolvesUnderApplicationSupport() {
        val path = IosIdentityPath.defaultIdentityPath()
        assertEquals("$baseDir/Portal/identity.json", path)
    }

    @Test
    fun defaultPathIsStableAcrossCalls() {
        // Relaunch reuse depends on the same path resolving every time.
        assertEquals(
            IosIdentityPath.defaultIdentityPath(),
            IosIdentityPath.defaultIdentityPath()
        )
    }

    @Test
    fun missingApplicationSupportFailsPermissionDenied() {
        IosIdentityPath.applicationSupportDirectory = { null }
        val ex = kotlin.test.assertFailsWith<PortalException> {
            IosIdentityPath.defaultIdentityPath()
        }
        assertEquals(PortalFailure.Codes.PERMISSION_DENIED, ex.failure.code)
        assertEquals("identity_path", ex.failure.operation)
    }

    @Test
    fun directoryCreationFailureFailsPermissionDenied() {
        IosIdentityPath.ensureDirectory = { false }
        val ex = kotlin.test.assertFailsWith<PortalException> {
            IosIdentityPath.defaultIdentityPath()
        }
        assertEquals(PortalFailure.Codes.PERMISSION_DENIED, ex.failure.code)
        assertEquals("identity_path", ex.failure.operation)
    }

    @Test
    fun openSendsPersistentIdentityPathToEngine() {
        val engine = FakeEngine()
        val client = PortalIosClient(engine, allowRemoteTargets = false, defaultIdentityPath = null)
        var completed = false
        client.open(PortalConfig.tcp(name = "identity-test")) { _, _ -> completed = true }
        pumpUntil { engine.startedConfigs.isNotEmpty() || completed }
        assertEquals(1, engine.startedConfigs.size)
        assertEquals("$baseDir/Portal/identity.json", engine.startedConfigs[0].identityPath)
    }

    @Test
    fun explicitConfigIdentityWins() {
        val engine = FakeEngine()
        val client = PortalIosClient(engine, allowRemoteTargets = false, defaultIdentityPath = null)
        var completed = false
        client.open(
            PortalConfig.tcp(name = "identity-test").copy(identityPath = "/explicit/id.json")
        ) { _, _ -> completed = true }
        pumpUntil { engine.startedConfigs.isNotEmpty() || completed }
        assertEquals("/explicit/id.json", engine.startedConfigs[0].identityPath)
    }

    @Test
    fun constructorOverrideWinsOverPlatformDefault() {
        val engine = FakeEngine()
        val client = PortalIosClient(
            engine, allowRemoteTargets = false, defaultIdentityPath = "/ctor/id.json"
        )
        var completed = false
        client.open(PortalConfig.tcp(name = "identity-test")) { _, _ -> completed = true }
        pumpUntil { engine.startedConfigs.isNotEmpty() || completed }
        assertEquals("/ctor/id.json", engine.startedConfigs[0].identityPath)
    }

    @Test
    fun openReportsIdentityPathFailureOnce() {
        IosIdentityPath.applicationSupportDirectory = { null }
        val engine = FakeEngine()
        val client = PortalIosClient(engine, allowRemoteTargets = false, defaultIdentityPath = null)
        var calls = 0
        var session: PortalIosSession? = null
        var failure: PortalFailure? = null
        client.open(PortalConfig.tcp(name = "identity-test")) { s, f ->
            calls += 1; session = s; failure = f
        }
        pumpUntil { calls > 0 }
        assertEquals(1, calls)
        assertNull(session)
        val f = failure
        assertNotNull(f)
        assertEquals(PortalFailure.Codes.PERMISSION_DENIED, f.code)
        assertEquals("identity_path", f.operation)
        assertTrue(engine.startedConfigs.isEmpty())
    }

    /** Pumps the main run loop until [condition] holds or 5 s elapse. */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0).timeIntervalSince1970
        while (!condition() && NSDate().timeIntervalSince1970 < deadline) {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.05, true)
        }
    }
}
