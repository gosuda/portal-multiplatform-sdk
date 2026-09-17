package org.gosuda.portal.internal

import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import org.gosuda.portal.android.internal.PortalJni
import org.gosuda.portal.android.internal.PortalNativeUnavailableException

/**
 * Android engine backed by the JNI bridge in `:portal-native-android`.
 * The bridge class lives in `org.gosuda.portal.android.internal` because the
 * prebuilt `libportaltunnel.so` exports name-mangled JNI symbols for it.
 */
internal class AndroidPortalEngine : PortalNativeEngine {

    override fun setEventListener(
        listener: (tunnelId: String, eventType: String, payloadJson: String) -> Unit
    ) = call("setEventListener") { PortalJni.setEventListener(listener) }

    override fun generateIdentity(name: String): String =
        call("generateIdentity") { PortalJni.generateIdentity(name) }

    override fun parseIdentity(identityJson: String): String =
        call("parseIdentity") { PortalJni.parseIdentity(identityJson) }

    override fun start(configJson: String): String =
        call("start") { PortalJni.start(configJson) }

    override fun stop(tunnelId: String) =
        call("stop") { PortalJni.stop(tunnelId) }

    override fun stopAll() =
        call("stopAll") { PortalJni.stopAll() }

    override fun getStatus(tunnelId: String): String =
        call("getStatus") { PortalJni.getStatus(tunnelId) }

    override fun addRelay(tunnelId: String, relayUrl: String) =
        call("addRelay") { PortalJni.addRelay(tunnelId, relayUrl) }

    override fun removeRelay(tunnelId: String, relayUrl: String) =
        call("removeRelay") { PortalJni.removeRelay(tunnelId, relayUrl) }

    override fun updateMetadata(tunnelId: String, metadataJson: String) =
        call("updateMetadata") { PortalJni.updateMetadata(tunnelId, metadataJson) }

    private inline fun <T> call(operation: String, block: () -> T): T = try {
        block()
    } catch (e: PortalNativeUnavailableException) {
        throw PortalException(
            PortalFailure.Codes.NATIVE_UNAVAILABLE,
            e.message ?: "libportaltunnel unavailable",
            operation = operation
        )
    } catch (e: PortalException) {
        throw e
    } catch (t: Throwable) {
        throw PortalException(
            PortalFailure.Codes.INTERNAL_ERROR,
            "native $operation failed: ${t.message}",
            operation = operation
        )
    }
}

internal actual fun platformNativeEngine(): PortalNativeEngine = AndroidPortalEngine()
