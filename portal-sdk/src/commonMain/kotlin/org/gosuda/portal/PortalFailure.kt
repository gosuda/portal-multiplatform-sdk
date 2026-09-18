package org.gosuda.portal

/**
 * Structured SDK failure. [code] is a stable machine-readable value from
 * [PortalFailure.Codes]; [nativeCode] preserves the raw return code of the
 * underlying `libportaltunnel` call for diagnostics.
 */
public data class PortalFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val operation: String? = null,
    val nativeCode: Int? = null,
    /**
     * Terminal phase the session reached when this failure ended it
     * ([TunnelPhase.FAILED]/[TunnelPhase.STOPPED]); null when the failure is
     * not tied to a terminal transition.
     */
    val terminalPhase: TunnelPhase? = null,
    /**
     * The readiness failure that triggered cleanup when this failure is a
     * `publish_cleanup` outcome; null otherwise. Lets callers inspect both
     * the cleanup result and the original cause without parsing [message].
     */
    val readinessFailure: PortalFailure? = null
) {
    public object Codes {
        public const val NATIVE_UNAVAILABLE: String = "NATIVE_UNAVAILABLE"
        public const val ABI_MISMATCH: String = "ABI_MISMATCH"
        public const val INVALID_CONFIG: String = "INVALID_CONFIG"
        public const val IDENTITY_INVALID: String = "IDENTITY_INVALID"
        public const val RELAY_UNAVAILABLE: String = "RELAY_UNAVAILABLE"
        public const val NETWORK_UNAVAILABLE: String = "NETWORK_UNAVAILABLE"
        public const val PERMISSION_DENIED: String = "PERMISSION_DENIED"
        public const val UNSUPPORTED_CAPABILITY: String = "UNSUPPORTED_CAPABILITY"
        public const val PROTOCOL_ERROR: String = "PROTOCOL_ERROR"
        public const val STOP_TIMEOUT: String = "STOP_TIMEOUT"
        public const val SECURITY_WARNING: String = "SECURITY_WARNING"
        public const val CLIENT_CLOSED: String = "CLIENT_CLOSED"
        public const val TUNNEL_CLOSED: String = "TUNNEL_CLOSED"
        public const val INTERNAL_ERROR: String = "INTERNAL_ERROR"
    }
}

/**
 * Single exception type thrown by the SDK. `CancellationException` is never
 * wrapped: coroutine cancellation always propagates as cancellation.
 */
public class PortalException(public val failure: PortalFailure) : Exception(failure.message) {
    public constructor(
        code: String,
        message: String,
        retryable: Boolean = false,
        operation: String? = null,
        nativeCode: Int? = null
    ) : this(PortalFailure(code, message, retryable, operation, nativeCode))

    public val code: String get() = failure.code
}
