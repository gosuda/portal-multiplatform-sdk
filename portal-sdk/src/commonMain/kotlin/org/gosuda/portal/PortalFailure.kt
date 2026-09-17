package org.gosuda.portal

/**
 * Structured SDK failure. [code] is a stable machine-readable value from
 * [PortalFailure.Codes]; [nativeCode] preserves the raw return code of the
 * underlying `libportaltunnel` call for diagnostics.
 */
data class PortalFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val operation: String? = null,
    val nativeCode: Int? = null
) {
    object Codes {
        const val NATIVE_UNAVAILABLE = "NATIVE_UNAVAILABLE"
        const val ABI_MISMATCH = "ABI_MISMATCH"
        const val INVALID_CONFIG = "INVALID_CONFIG"
        const val IDENTITY_INVALID = "IDENTITY_INVALID"
        const val RELAY_UNAVAILABLE = "RELAY_UNAVAILABLE"
        const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val UNSUPPORTED_CAPABILITY = "UNSUPPORTED_CAPABILITY"
        const val PROTOCOL_ERROR = "PROTOCOL_ERROR"
        const val STOP_TIMEOUT = "STOP_TIMEOUT"
        const val SECURITY_WARNING = "SECURITY_WARNING"
        const val CLIENT_CLOSED = "CLIENT_CLOSED"
        const val TUNNEL_CLOSED = "TUNNEL_CLOSED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}

/**
 * Single exception type thrown by the SDK. `CancellationException` is never
 * wrapped: coroutine cancellation always propagates as cancellation.
 */
class PortalException(val failure: PortalFailure) : Exception(failure.message) {
    constructor(
        code: String,
        message: String,
        retryable: Boolean = false,
        operation: String? = null,
        nativeCode: Int? = null
    ) : this(PortalFailure(code, message, retryable, operation, nativeCode))

    val code: String get() = failure.code
}
