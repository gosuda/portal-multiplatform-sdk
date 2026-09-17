package org.gosuda.portal.android.internal

/**
 * Public facade over the internal JNI bridge. `portal-sdk`'s `androidMain`
 * consumes this module and cannot see `internal` declarations across module
 * boundaries, so this is the only supported entry point.
 *
 * All functions are blocking; callers dispatch off the UI thread.
 */
object PortalJni {

    /** True when `libportaltunnel` loaded and the native callback is armed. */
    val isLoaded: Boolean
        get() = try {
            NativeBridge.isLoaded
        } catch (t: Throwable) {
            false
        }
    fun setEventListener(listener: (tunnelId: String, eventType: String, payloadJson: String) -> Unit) {
        ensureLoaded()
        NativeBridge.setEventListener(listener)
    }

    fun generateIdentity(name: String): String {
        ensureLoaded()
        return NativeBridge.generateIdentity(name)
    }

    fun parseIdentity(identityJson: String): String {
        ensureLoaded()
        return NativeBridge.parseIdentity(identityJson)
    }

    fun start(configJson: String): String {
        ensureLoaded()
        return NativeBridge.start(configJson)
    }

    fun stop(tunnelId: String) {
        ensureLoaded()
        NativeBridge.stop(tunnelId)
    }

    fun stopAll() {
        ensureLoaded()
        NativeBridge.stopAll()
    }

    fun getStatus(tunnelId: String): String {
        ensureLoaded()
        return NativeBridge.getStatus(tunnelId)
    }

    fun addRelay(tunnelId: String, relayUrl: String) {
        ensureLoaded()
        NativeBridge.addRelay(tunnelId, relayUrl)
    }

    fun removeRelay(tunnelId: String, relayUrl: String) {
        ensureLoaded()
        NativeBridge.removeRelay(tunnelId, relayUrl)
    }

    fun updateMetadata(tunnelId: String, metadataJson: String) {
        ensureLoaded()
        NativeBridge.updateMetadata(tunnelId, metadataJson)
    }

    private fun ensureLoaded() {
        if (!isLoaded) {
            throw PortalNativeUnavailableException(
                "libportaltunnel is not loaded; check that the portal-native-android " +
                    "artifact is packaged and the device ABI is arm64-v8a or x86_64"
            )
        }
    }
}

/** Thrown when the native engine binary is unavailable in this process. */
class PortalNativeUnavailableException(message: String) : RuntimeException(message)
