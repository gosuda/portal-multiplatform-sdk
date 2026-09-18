package org.gosuda.portal.internal

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop engine over the `libportaltunnel` v1 C ABI via JNA.
 *
 * The native library is loaded lazily on the first native call so that
 * constructing a [org.gosuda.portal.PortalClient] does not require the engine
 * to be present — matching the Android JNI adapter, which loads on first use.
 *
 * Memory contract (mirrors the Kotlin/Native [CApiPortalEngine]):
 * - every `char*` the runtime hands over — out-params and error strings — is
 *   caller-freed via `PortalFreeString`, exactly once, on success and failure;
 * - callback arguments are runtime-owned: copied synchronously, never freed;
 * - a fresh [PointerByReference] is used per call because the Go side does not
 *   always write out-params — a reused ref can return a stale (already-freed)
 *   pointer and double-free.
 *
 * The JNA callback is held in a strong reference for the engine lifetime so
 * GC cannot invalidate the native function pointer, and it never throws or
 * blocks — exceptions stop at the boundary.
 */
internal class DesktopPortalEngine private constructor(
    private val libraryProvider: () -> PortalNativeLibrary
) : PortalNativeEngine {

    /** Lazily-loaded native library; resolved once on first use. */
    private val library: PortalNativeLibrary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        libraryProvider()
    }

    /** Production engine: resolves the packaged or explicit native library. */
    constructor(explicitNativePath: Path? = null) : this({
        DesktopNativeLibraryLoader.load(explicitNativePath)
    })

    /** Test seam: injects an already-loaded library (e.g. the C stub). */
    internal constructor(library: PortalNativeLibrary) : this({ library })

    private val eventListener =
        AtomicReference<((tunnelId: String, eventType: String, payloadJson: String) -> Unit)?>(null)

    // Strong reference: JNA must not GC the callback while native code holds it.
    @Suppress("unused")
    private val nativeEventCallback = PortalNativeLibrary.PortalEventCallback { tunnelId, eventType, payloadJson ->
        val id = tunnelId?.getString(0, UTF8).orEmpty()
        val type = eventType?.getString(0, UTF8).orEmpty()
        val payload = payloadJson?.getString(0, UTF8).orEmpty()
        if (id.isNotEmpty()) {
            try {
                eventListener.get()?.invoke(id, type, payload)
            } catch (_: Throwable) {
                // Never let exceptions cross the native boundary.
            }
        }
    }

    override fun setEventListener(
        listener: (tunnelId: String, eventType: String, payloadJson: String) -> Unit
    ) {
        eventListener.set(listener)
        library.PortalSetEventCallback(nativeEventCallback)
    }

    override fun generateIdentity(name: String): String =
        callString("generateIdentity") { out, err ->
            library.PortalGenerateIdentity(if (name.isEmpty()) null else cstr(name), out, err)
        }

    override fun parseIdentity(identityJson: String): String =
        callString("parseIdentity") { out, err ->
            library.PortalParseIdentity(cstr(identityJson), out, err)
        }

    override fun start(configJson: String): String =
        callString("start") { out, err ->
            library.PortalStart(cstr(configJson), out, err)
        }

    override fun stop(tunnelId: String) =
        callUnit("stop") { err -> library.PortalStop(cstr(tunnelId), err) }

    override fun stopAll() = library.PortalStopAll()

    override fun getStatus(tunnelId: String): String =
        callString("getStatus") { out, err ->
            library.PortalGetStatus(cstr(tunnelId), out, err)
        }

    override fun addRelay(tunnelId: String, relayUrl: String) =
        callUnit("addRelay") { err -> library.PortalAddRelay(cstr(tunnelId), cstr(relayUrl), err) }

    override fun removeRelay(tunnelId: String, relayUrl: String) =
        callUnit("removeRelay") { err -> library.PortalRemoveRelay(cstr(tunnelId), cstr(relayUrl), err) }

    override fun updateMetadata(tunnelId: String, metadataJson: String) =
        callUnit("updateMetadata") { err -> library.PortalUpdateMetadata(cstr(tunnelId), cstr(metadataJson), err) }

    // --- helpers -------------------------------------------------------------

    private inline fun callString(
        operation: String,
        native: (out: PointerByReference, err: PointerByReference) -> Int
    ): String {
        val out = PointerByReference()
        val err = PointerByReference()
        val code = native(out, err)
        val outPtr = out.value
        val errPtr = err.value
        try {
            if (code != 0 || outPtr == null) {
                throw PortalException(
                    PortalFailure.Codes.INTERNAL_ERROR,
                    "native $operation failed (code $code): ${errPtr?.getString(0, UTF8) ?: "unknown error"}",
                    operation = operation,
                    nativeCode = code
                )
            }
            return outPtr.getString(0, UTF8)
        } finally {
            outPtr?.let { library.PortalFreeString(it) }
            errPtr?.let { library.PortalFreeString(it) }
        }
    }

    private inline fun callUnit(
        operation: String,
        native: (err: PointerByReference) -> Int
    ) {
        val err = PointerByReference()
        val code = native(err)
        val errPtr = err.value
        try {
            if (code != 0) {
                throw PortalException(
                    PortalFailure.Codes.INTERNAL_ERROR,
                    "native $operation failed (code $code): ${errPtr?.getString(0, UTF8) ?: "unknown error"}",
                    operation = operation,
                    nativeCode = code
                )
            }
        } finally {
            errPtr?.let { library.PortalFreeString(it) }
        }
    }

    private companion object {
        private const val UTF8 = "UTF-8"

        /** Allocates an explicit UTF-8, NUL-terminated native string. */
        private fun cstr(value: String): Memory {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val mem = Memory(bytes.size + 1L)
            mem.write(0, bytes, 0, bytes.size)
            mem.setByte(bytes.size.toLong(), 0)
            return mem
        }
    }
}

internal actual fun platformNativeEngine(): PortalNativeEngine = DesktopPortalEngine()
