package org.gosuda.portal.internal

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA binding to the `libportaltunnel` v1 C ABI declared in
 * `native/include/portaltunnel.h`. Method names must match the exported
 * symbols exactly; the signature is verified by
 * scripts/verify-desktop-engine.sh and the desktop ABI test.
 *
 * Memory contract: every `char*` the runtime hands over — out-params and
 * error strings — is caller-freed via [PortalFreeString]. Callback arguments
 * are runtime-owned and must be copied synchronously, never freed.
 */
internal interface PortalNativeLibrary : Library {

    /** Process-global event callback invoked from Go-created threads. */
    fun interface PortalEventCallback : Callback {
        fun invoke(tunnelId: Pointer?, eventType: Pointer?, payloadJson: Pointer?)
    }

    fun PortalSetEventCallback(callback: PortalEventCallback?)
    fun PortalFreeString(pointer: Pointer?)

    fun PortalGenerateIdentity(
        name: Pointer?,
        outIdentityJson: PointerByReference,
        outError: PointerByReference
    ): Int

    fun PortalParseIdentity(
        identityJson: Pointer,
        outIdentityJson: PointerByReference,
        outError: PointerByReference
    ): Int

    fun PortalStart(
        configJson: Pointer,
        outTunnelId: PointerByReference,
        outError: PointerByReference
    ): Int

    fun PortalStop(tunnelId: Pointer, outError: PointerByReference): Int
    fun PortalStopAll()

    fun PortalGetStatus(
        tunnelId: Pointer,
        outStatusJson: PointerByReference,
        outError: PointerByReference
    ): Int

    fun PortalAddRelay(tunnelId: Pointer, relayUrl: Pointer, outError: PointerByReference): Int
    fun PortalRemoveRelay(tunnelId: Pointer, relayUrl: Pointer, outError: PointerByReference): Int
    fun PortalUpdateMetadata(tunnelId: Pointer, metadataJson: Pointer, outError: PointerByReference): Int
}
