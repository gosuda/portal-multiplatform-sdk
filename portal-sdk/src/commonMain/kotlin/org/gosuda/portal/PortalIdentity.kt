package org.gosuda.portal

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gosuda.portal.internal.PortalJson
import org.gosuda.portal.internal.PortalNativeEngine
import org.gosuda.portal.internal.platformNativeEngine

/**
 * Portal identity document handle.
 *
 * [document] is the raw identity JSON consumed by [PortalConfig.identityJson].
 * It contains secret key material: it is never included in [toString] output
 * and must be stored in a platform-protected location (Android Keystore-
 * wrapped file, iOS Keychain), not logged or committed.
 */
class PortalIdentity internal constructor(
    val document: String,
    val name: String,
    val address: String
) {
    /** Alias kept for parity with the platform SDKs. */
    val jsonString: String get() = document

    override fun toString(): String =
        "PortalIdentity(name=$name, address=$address, document=<redacted ${document.length} chars>)"

    override fun equals(other: Any?): Boolean =
        other is PortalIdentity && other.document == document &&
            other.name == name && other.address == address

    override fun hashCode(): Int = 31 * (31 * document.hashCode() + name.hashCode()) + address.hashCode()

    companion object {
        private const val MAX_IDENTITY_BYTES = 64 * 1024

        /**
         * Generates a fresh identity via the native runtime.
         * @throws PortalException [PortalFailure.Codes.IDENTITY_INVALID] if the
         *   native runtime returns a document without a usable address.
         */
        fun generate(name: String = ""): PortalIdentity =
            generate(platformNativeEngine(), name)

        /**
         * Validates and parses an existing identity document.
         * @throws PortalException [PortalFailure.Codes.IDENTITY_INVALID] on
         *   malformed input or a document missing name/address.
         */
        fun parse(identityJson: String): PortalIdentity {
            if (identityJson.isBlank() || identityJson.length > MAX_IDENTITY_BYTES) {
                throw PortalException(
                    PortalFailure.Codes.IDENTITY_INVALID,
                    "identity document is blank or exceeds $MAX_IDENTITY_BYTES bytes"
                )
            }
            return parse(platformNativeEngine(), identityJson)
        }

        internal fun generate(engine: PortalNativeEngine, name: String): PortalIdentity {
            val raw = engine.generateIdentity(name)
            return fromDocument(raw, fallbackName = name)
        }

        internal fun parse(engine: PortalNativeEngine, identityJson: String): PortalIdentity {
            val raw = engine.parseIdentity(identityJson)
            return fromDocument(raw, fallbackName = "")
        }

        private fun fromDocument(raw: String, fallbackName: String): PortalIdentity {
            val element = try {
                PortalJson.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                throw PortalException(
                    PortalFailure.Codes.IDENTITY_INVALID,
                    "native runtime returned a non-JSON identity document",
                    nativeCode = null
                )
            }
            val name = element["name"]?.jsonPrimitive?.content.orEmpty().ifEmpty { fallbackName }
            val address = element["address"]?.jsonPrimitive?.content.orEmpty()
            if (address.isEmpty()) {
                throw PortalException(
                    PortalFailure.Codes.IDENTITY_INVALID,
                    "identity document has no address"
                )
            }
            return PortalIdentity(raw, name, address)
        }
    }
}
