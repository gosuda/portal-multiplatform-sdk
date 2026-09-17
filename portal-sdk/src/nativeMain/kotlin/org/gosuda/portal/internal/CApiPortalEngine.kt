@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.gosuda.portal.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import portaltunnel.PortalAddRelay
import portaltunnel.PortalEventCallback
import portaltunnel.PortalFreeString
import portaltunnel.PortalGenerateIdentity
import portaltunnel.PortalGetStatus
import portaltunnel.PortalParseIdentity
import portaltunnel.PortalRemoveRelay
import portaltunnel.PortalSetEventCallback
import portaltunnel.PortalStart
import portaltunnel.PortalStop
import portaltunnel.PortalStopAll
import portaltunnel.PortalUpdateMetadata

/**
 * Kotlin/Native engine over the `libportaltunnel` C ABI, shared by the iOS
 * and Linux targets through cinterop commonization.
 *
 * Memory contract (mirrors the Flutter SDK binding): every `char*` the
 * runtime hands over — out-params and callback arguments — is caller-freed
 * via `PortalFreeString`.
 */
internal class CApiPortalEngine : PortalNativeEngine {

    override fun setEventListener(
        listener: (tunnelId: String, eventType: String, payloadJson: String) -> Unit
    ) {
        eventListener.store(listener)
        PortalSetEventCallback(nativeEventCallback)
    }

    override fun generateIdentity(name: String): String =
        callString("generateIdentity") { out, err ->
            PortalGenerateIdentity(if (name.isEmpty()) null else name.cstr, out, err)
        }

    override fun parseIdentity(identityJson: String): String =
        callString("parseIdentity") { out, err ->
            PortalParseIdentity(identityJson.cstr, out, err)
        }

    override fun start(configJson: String): String =
        callString("start") { out, err -> PortalStart(configJson.cstr, out, err) }

    override fun stop(tunnelId: String) =
        callUnit("stop") { err -> PortalStop(tunnelId.cstr, err) }

    override fun stopAll() = PortalStopAll()

    override fun getStatus(tunnelId: String): String =
        callString("getStatus") { out, err -> PortalGetStatus(tunnelId.cstr, out, err) }

    override fun addRelay(tunnelId: String, relayUrl: String) =
        callUnit("addRelay") { err -> PortalAddRelay(tunnelId.cstr, relayUrl.cstr, err) }

    override fun removeRelay(tunnelId: String, relayUrl: String) =
        callUnit("removeRelay") { err -> PortalRemoveRelay(tunnelId.cstr, relayUrl.cstr, err) }

    override fun updateMetadata(tunnelId: String, metadataJson: String) =
        callUnit("updateMetadata") { err -> PortalUpdateMetadata(tunnelId.cstr, metadataJson.cstr, err) }

    private inline fun callString(
        operation: String,
        native: MemScope.(
            out: CPointer<CPointerVar<ByteVar>>,
            err: CPointer<CPointerVar<ByteVar>>
        ) -> Int
    ): String = memScoped {
        val out = alloc<CPointerVar<ByteVar>>()
        val err = alloc<CPointerVar<ByteVar>>()
        val code = native(out.ptr, err.ptr)
        val outPtr = out.value
        val errPtr = err.value
        try {
            if (code != 0 || outPtr == null) {
                throw PortalException(
                    PortalFailure.Codes.INTERNAL_ERROR,
                    "native $operation failed (code $code): ${errPtr?.toKString() ?: "unknown error"}",
                    operation = operation,
                    nativeCode = code
                )
            }
            outPtr.toKString()
        } finally {
            outPtr?.let { PortalFreeString(it) }
            errPtr?.let { PortalFreeString(it) }
        }
    }

    private inline fun callUnit(
        operation: String,
        native: MemScope.(err: CPointer<CPointerVar<ByteVar>>) -> Int
    ) {
        memScoped {
            val err = alloc<CPointerVar<ByteVar>>()
            val code = native(err.ptr)
            val errPtr = err.value
            try {
                if (code != 0) {
                    throw PortalException(
                        PortalFailure.Codes.INTERNAL_ERROR,
                        "native $operation failed (code $code): ${errPtr?.toKString() ?: "unknown error"}",
                        operation = operation,
                        nativeCode = code
                    )
                }
            } finally {
                errPtr?.let { PortalFreeString(it) }
            }
        }
    }

    private companion object {
        private val eventListener =
            kotlin.concurrent.atomics.AtomicReference<
                ((tunnelId: String, eventType: String, payloadJson: String) -> Unit)?>(null)

        /**
         * Capture-free C callback. Runs on Go-created threads; must not throw
         * or block. Arguments are copied to Kotlin strings before being freed.
         */
        private val nativeEventCallback: PortalEventCallback =
            staticCFunction { tunnelId, eventType, payloadJson ->
                // Callback arguments are NOT freed: the iOS SDK treats them as
                // runtime-owned (freeing a static buffer would crash). The
                // Flutter SDK frees them; the Go bridge source is unavailable
                // to settle this — see docs/DESIGN_RULES.md.
                val id = tunnelId?.toKString().orEmpty()
                val type = eventType?.toKString().orEmpty()
                val payload = payloadJson?.toKString().orEmpty()
                if (id.isNotEmpty()) {
                    try {
                        eventListener.load()?.invoke(id, type, payload)
                    } catch (_: Throwable) {
                        // Never let exceptions cross the native boundary.
                    }
                }
            }
    }
}

internal actual fun platformNativeEngine(): PortalNativeEngine = CApiPortalEngine()
