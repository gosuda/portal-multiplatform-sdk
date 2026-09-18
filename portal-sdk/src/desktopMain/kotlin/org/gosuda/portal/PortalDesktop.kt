package org.gosuda.portal

import org.gosuda.portal.internal.DesktopArchitecture
import org.gosuda.portal.internal.DesktopNativeLibraryLoader
import org.gosuda.portal.internal.DesktopOs
import org.gosuda.portal.internal.DesktopPortalEngine
import org.gosuda.portal.internal.NativeLibrarySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Diagnostic snapshot of the resolved desktop native runtime. Contains paths
 * and hashes only — never identity contents, tokens, or request data.
 */
public data class PortalDesktopRuntime(
    val os: DesktopOs,
    val architecture: DesktopArchitecture,
    val nativeLibraryPath: Path,
    val nativeSha256: String,
    val source: NativeLibrarySource
)

/**
 * Desktop entry point for the Portal SDK.
 *
 * A desktop application owns its [PortalClient] explicitly: construct once
 * per application (or long-lived component), keep it alive for the tunnels'
 * lifetime, and close it from the application lifecycle. There is no hidden
 * global state, singleton, or shutdown hook.
 */
public object PortalDesktop {

    /**
     * Creates a [PortalClient] backed by the desktop `libportaltunnel` engine.
     *
     * @param applicationId required, nonblank, filesystem-safe segment
     *   (`[A-Za-z0-9._-]`, no separators or `..`). Different desktop apps must
     *   not silently share one Portal identity.
     * @param storageDirectory overrides the platform application-data
     *   directory used for the default identity path.
     * @param nativeLibraryPath explicit development/enterprise override. Must
     *   be an absolute regular file with the expected extension; failure is
     *   `NATIVE_UNAVAILABLE` with no silent fallback.
     * @param allowRemoteTargets permits non-loopback `target_addr`/`udp_addr`.
     */
    public fun client(
        applicationId: String,
        storageDirectory: Path? = null,
        nativeLibraryPath: Path? = null,
        allowRemoteTargets: Boolean = false
    ): PortalClient {
        // Validate eagerly: a bad applicationId is a programmer error, not a
        // filesystem condition, so it must fail at client() not inside open().
        DesktopIdentityPath.validate(applicationId)
        return PortalClient(
            engine = DesktopPortalEngine(nativeLibraryPath),
            allowRemoteTargets = allowRemoteTargets,
            // Lazy: the identity directory is created inside open/publish so a
            // filesystem failure surfaces through the operation, matching iOS.
            defaultIdentityPath = {
                DesktopIdentityPath.resolve(applicationId, storageDirectory).toString()
            }
        )
    }

    /**
     * Resolves the native runtime that [client] would load, for diagnostics.
     * Does not create or retain a client/session.
     */
    public fun runtime(nativeLibraryPath: Path? = null): PortalDesktopRuntime {
        val resolved = DesktopNativeLibraryLoader.resolve(nativeLibraryPath)
        return PortalDesktopRuntime(
            os = resolved.os,
            architecture = resolved.architecture,
            nativeLibraryPath = resolved.path,
            nativeSha256 = resolved.sha256,
            source = resolved.source
        )
    }
}

/**
 * Platform-owned default `identity_path` for desktop.
 *
 * Resolution is lazy in the sense that the directory is created inside
 * `open`/`publish` (the engine writes `identity.json` on first use), so a
 * filesystem failure surfaces through the operation — matching the iOS
 * contract — rather than at client construction.
 */
internal object DesktopIdentityPath {

    private const val PORTAL_DIR_UNIX = "portal"
    private const val PORTAL_DIR_APPLE_WIN = "Portal"
    private const val IDENTITY_FILE = "identity.json"

    private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")

    /** Test seam: overrides the platform application-data base directory. */
    internal var baseDirectory: (DesktopOs, String) -> Path = { os, appId -> defaultBase(os, appId) }

    /** Test seam: overrides OS detection. */
    internal var os: () -> DesktopOs = { detectOs() }

    /**
     * Returns the identity path for [applicationId] without touching the
     * filesystem. Pure path computation — used by tests and by [resolve].
     */
    fun pathFor(applicationId: String, storageDirectory: Path?): Path {
        validateApplicationId(applicationId)
        val os = os()
        val base = storageDirectory ?: baseDirectory(os, applicationId)
        return base.resolve(applicationId).resolve(portalDirName(os)).resolve(IDENTITY_FILE)
    }

    /**
     * Returns the default identity path for [applicationId], creating the
     * parent directory. Throws [PortalException] PERMISSION_DENIED when the
     * directory cannot be created — there is no temporary-path fallback.
     * Called lazily inside `open`/`publish` so the failure surfaces through
     * the operation, matching iOS.
     */
    fun resolve(applicationId: String, storageDirectory: Path?): Path {
        val path = pathFor(applicationId, storageDirectory)
        val dir = path.parent
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            throw PortalException(
                PortalFailure.Codes.PERMISSION_DENIED,
                "cannot create identity directory: $dir",
                operation = "identity_path"
            )
        }
        return path
    }

    /** Validates [applicationId] without touching the filesystem. */
    fun validate(applicationId: String) = validateApplicationId(applicationId)

    private fun validateApplicationId(applicationId: String) {
        if (applicationId.isBlank() ||
            !SAFE_SEGMENT.matches(applicationId) ||
            applicationId == "." || applicationId == ".."
        ) {
            throw PortalException(
                PortalFailure.Codes.INVALID_CONFIG,
                "applicationId must be a filesystem-safe segment [A-Za-z0-9._-], got: '$applicationId'",
                operation = "identity_path"
            )
        }
    }

    private fun detectOs(): DesktopOs {
        val name = (System.getProperty("os.name") ?: "").lowercase()
        return when {
            name.startsWith("linux") -> DesktopOs.LINUX
            name.startsWith("windows") || name.startsWith("win") -> DesktopOs.WINDOWS
            name.startsWith("mac") || name.startsWith("darwin") -> DesktopOs.MACOS
            else -> DesktopOs.LINUX
        }
    }

    private fun defaultBase(os: DesktopOs, applicationId: String): Path {
        val home = System.getProperty("user.home") ?: "."
        return when (os) {
            DesktopOs.LINUX -> Path.of(System.getenv("XDG_STATE_HOME") ?: "$home/.local/state")
            DesktopOs.WINDOWS -> Path.of(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local")
            DesktopOs.MACOS -> Path.of(home, "Library", "Application Support")
        }
    }

    private fun portalDirName(os: DesktopOs): String = when (os) {
        DesktopOs.LINUX -> PORTAL_DIR_UNIX
        DesktopOs.WINDOWS, DesktopOs.MACOS -> PORTAL_DIR_APPLE_WIN
    }
}
