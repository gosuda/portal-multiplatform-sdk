package org.gosuda.portal.internal

import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Platform-owned default `identity_path` for iOS.
 *
 * The native engine writes `identity.json` to the process working directory
 * when neither `identity_json` nor `identity_path` is set, and that directory
 * is not writable on iOS. The SDK therefore resolves a persistent location
 * under Application Support — the same role `filesDir` plays on Android.
 *
 * Resolution is lazy (per `open`/`publish`) so filesystem failures surface
 * through the operation's completion handler instead of a constructor.
 */
internal object IosIdentityPath {

    private const val DIRECTORY_NAME = "Portal"
    private const val FILE_NAME = "identity.json"

    /** Test seam: overrides the Application Support base directory. */
    internal var applicationSupportDirectory: () -> String? = {
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull()?.toString()
    }

    /** Test seam: overrides directory creation for failure injection. */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    internal var ensureDirectory: (String) -> Boolean = { path ->
        NSFileManager.defaultManager.createDirectoryAtPath(
            path, true, null, null
        )
    }

    /**
     * Returns `<Application Support>/Portal/identity.json`, creating the
     * `Portal` directory when absent.
     *
     * @throws PortalException PERMISSION_DENIED when the directory cannot be
     *   resolved or created. There is no fallback to a temporary path: an
     *   identity that disappears between launches silently changes the
     *   tunnel's public name.
     */
    fun defaultIdentityPath(): String {
        val base = applicationSupportDirectory()
            ?: throw PortalException(
                PortalFailure.Codes.PERMISSION_DENIED,
                "Application Support directory is not available",
                operation = "identity_path"
            )
        val dir = "$base/$DIRECTORY_NAME"
        if (!ensureDirectory(dir)) {
            throw PortalException(
                PortalFailure.Codes.PERMISSION_DENIED,
                "cannot create identity directory: $dir",
                operation = "identity_path"
            )
        }
        return "$dir/$FILE_NAME"
    }
}
