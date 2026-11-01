package org.gosuda.portal.sample.llm

/** Sample-only CPU inference. Prompts and responses use standard UTF-8 bytes, not JNI modified UTF-8. */
object NativeLlm {
    private var loaded = false

    /** An explicit desktop path may be loaded once before any caller uses the default library name. */
    @Synchronized
    fun load(libraryPath: String? = null) {
        if (loaded) return
        if (libraryPath == null) {
            System.loadLibrary("portal_sample_llm")
        } else {
            require(libraryPath.isNotBlank()) { "The native library path must not be blank." }
            System.load(libraryPath)
        }
        loaded = true
    }

    external fun open(modelPathUtf8: ByteArray): Long
    external fun generate(handle: Long, promptUtf8: ByteArray, maxTokens: Int): ByteArray

    /** Permanently cancels this model owner; future generation on it fails. */
    external fun cancel(handle: Long)

    /** Cancels and waits for in-flight native inference before releasing model resources. */
    external fun close(handle: Long)
}
