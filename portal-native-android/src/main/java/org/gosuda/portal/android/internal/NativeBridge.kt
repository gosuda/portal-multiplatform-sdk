package org.gosuda.portal.android.internal

/**
 * JNI bridge to the generated `libportaltunnel`.
 *
 * The package and class name are load-bearing: `native/bridge/jni_android.c`
 * exports name-mangled JNI symbols bound to this exact class. Do not rename
 * or move it without rebuilding the generated Android libraries.
 */
internal object NativeBridge {
    internal var isLoaded = false
    private var eventListener: ((tunnelId: String, eventType: String, payloadJson: String) -> Unit)? = null

    init {
        tryLoadNativeLibrary()
    }

    private fun tryLoadNativeLibrary() {
        if (isLoaded) return
        try {
            System.loadLibrary("portaltunnel")
            isLoaded = true
            setupNativeCallback()
        } catch (e: UnsatisfiedLinkError) {
            // Surface the failure through PortalJni.isLoaded instead of
            // silently continuing with an unloaded library.
        }
    }

    fun setEventListener(listener: (tunnelId: String, eventType: String, payloadJson: String) -> Unit) {
        this.eventListener = listener
    }

    // Called from JNI/CGO.
    @JvmStatic
    fun onNativeEvent(tunnelId: String, eventType: String, payloadJson: String) {
        eventListener?.invoke(tunnelId, eventType, payloadJson)
    }

    // Native declarations
    external fun nativeGenerateIdentity(name: String): String
    external fun nativeParseIdentity(identityJson: String): String
    external fun nativeStart(configJson: String): String
    external fun nativeStop(tunnelId: String)
    external fun nativeStopAll()
    external fun nativeGetStatus(tunnelId: String): String
    external fun nativeAddRelay(tunnelId: String, relayUrl: String)
    external fun nativeRemoveRelay(tunnelId: String, relayUrl: String)
    external fun nativeUpdateMetadata(tunnelId: String, metadataJson: String)
    private external fun setupNativeCallback()

    fun generateIdentity(name: String): String = nativeGenerateIdentity(name)
    fun parseIdentity(json: String): String = nativeParseIdentity(json)
    fun start(configJson: String): String = nativeStart(configJson)
    fun stop(tunnelId: String) = nativeStop(tunnelId)
    fun stopAll() = nativeStopAll()
    fun getStatus(tunnelId: String): String = nativeGetStatus(tunnelId)
    fun addRelay(tunnelId: String, relayUrl: String) = nativeAddRelay(tunnelId, relayUrl)
    fun removeRelay(tunnelId: String, relayUrl: String) = nativeRemoveRelay(tunnelId, relayUrl)
    fun updateMetadata(tunnelId: String, metadataJson: String) = nativeUpdateMetadata(tunnelId, metadataJson)
}
