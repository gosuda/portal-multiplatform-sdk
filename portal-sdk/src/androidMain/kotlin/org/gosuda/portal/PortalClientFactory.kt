package org.gosuda.portal

import android.content.Context
import java.io.File

/**
 * Creates a [PortalClient] whose default `identity_path` is
 * `context.filesDir/identity.json`.
 *
 * The native engine writes `identity.json` to the process working directory
 * when neither `identity_json` nor `identity_path` is set — and that
 * directory is read-only on Android. Passing a [Context] makes the default
 * writable and persistent across restarts.
 */
public fun PortalClient(
    context: Context,
    allowRemoteTargets: Boolean = false
): PortalClient = PortalClient(
    engine = org.gosuda.portal.internal.platformNativeEngine(),
    allowRemoteTargets = allowRemoteTargets,
    defaultIdentityPath = { File(context.filesDir, "identity.json").absolutePath }
)
