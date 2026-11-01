package org.gosuda.portal.sample.llm

import java.security.SecureRandom
import java.util.Base64

/** Sample HTTP credentials, never Portal identity or persistent model data. */
object LlmPassword {
    private val random = SecureRandom()

    fun generate(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(24).also(random::nextBytes))

    fun isValid(password: String): Boolean =
        Character.codePointCount(password, 0, password.length) in 12..128

    /** Fixed Basic username; UTF-8 password supports non-ASCII input safely. */
    fun authorization(password: String): String = "Basic " + Base64.getEncoder()
        .encodeToString("portal:$password".toByteArray(Charsets.UTF_8))
}
