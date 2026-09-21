@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.gosuda.portal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.timeIntervalSince1970

class IosLifecycleTest {
    private val config = PortalConfig.tcp(name = "lifecycle-test")

    @Test
    fun closeRetriesFailedNativeCleanupAfterOwnerScopeWasCancelled() {
        val engine = FakeEngine()
        val client = PortalIosClient(engine, allowRemoteTargets = false, defaultIdentityPath = "/test/identity.json")
        var session: PortalIosSession? = null
        client.open(config) { opened, failure ->
            assertNull(failure)
            session = opened
        }
        pumpUntil { session != null }
        val opened = assertNotNull(session)
        engine.stopFailure = PortalException(PortalFailure.Codes.INTERNAL_ERROR, "stop failed")
        var calls = 0
        var failure: PortalFailure? = null

        client.close { failure = it; calls++ }
        pumpUntil { calls == 1 }
        assertEquals(PortalFailure.Codes.INTERNAL_ERROR, failure?.code)
        assertEquals(TunnelPhase.STOPPING, opened.snapshot.phase)

        engine.stopFailure = null
        client.close { failure = it; calls++ }
        pumpUntil { calls == 2 }
        assertNull(failure)
        assertEquals(TunnelPhase.STOPPED, opened.snapshot.phase)
        assertEquals(listOf(opened.sessionId), engine.stoppedIds)
    }

    @Test
    fun operationOnClosedClientCompletesOnMainEvenFromWorkerThread() {
        val client = PortalIosClient(FakeEngine(), allowRemoteTargets = false, defaultIdentityPath = "/test/identity.json")
        var closed = false
        client.close { closed = true }
        pumpUntil { closed }
        val calls = AtomicInt(0)
        val onMain = AtomicBoolean(false)
        val failure = AtomicReference<PortalFailure?>(null)

        CoroutineScope(Dispatchers.Default).launch {
            client.open(config) { _, result ->
                onMain.store(NSThread.isMainThread)
                failure.store(result)
                calls.addAndFetch(1)
            }
        }
        pumpUntil { calls.load() > 0 }
        assertTrue(onMain.load())
        assertEquals(1, calls.load())
        assertEquals(PortalFailure.Codes.CLIENT_CLOSED, failure.load()?.code)
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0).timeIntervalSince1970
        while (!condition() && NSDate().timeIntervalSince1970 < deadline) {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.05, true)
        }
        assertTrue(condition(), "iOS completion did not arrive")
    }
}
