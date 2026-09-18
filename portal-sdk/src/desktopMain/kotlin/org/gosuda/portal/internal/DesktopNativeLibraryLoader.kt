package org.gosuda.portal.internal

import com.sun.jna.Native
import org.gosuda.portal.PortalException
import org.gosuda.portal.PortalFailure
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Desktop operating system the loader can select a native binary for. */
public enum class DesktopOs { LINUX, WINDOWS, MACOS }

/** CPU architecture the loader can select a native binary for. */
public enum class DesktopArchitecture { X86_64, ARM64 }

/** Where the loaded native library came from. */
public enum class NativeLibrarySource { PACKAGED, EXPLICIT }

/**
 * Resolved native library: the absolute file to load plus its provenance.
 */
internal data class ResolvedNativeLibrary(
    val path: Path,
    val sha256: String,
    val os: DesktopOs,
    val architecture: DesktopArchitecture,
    val source: NativeLibrarySource
)

/**
 * Loads `libportaltunnel` for the desktop JVM target.
 *
 * Resolution order:
 * 1. an explicit `nativeLibraryPath` override (development/enterprise), or
 * 2. the checksum-indexed resource packaged in `:portal-native-desktop`.
 *
 * Packaged binaries are extracted to a content-addressed, versioned cache:
 * `<cache>/portal-sdk/<sdkVersion>/<sha256>/<filename>`. Extraction is
 * coordinated by a file lock, written to a sibling temp file, fsynced,
 * hash-verified, then atomically moved — a loaded binary is never overwritten
 * in place (required on Windows).
 */
internal object DesktopNativeLibraryLoader {

    private const val INDEX_RESOURCE = "META-INF/portal-native/index.json"
    private const val RESOURCE_PREFIX = "META-INF/portal-native/"
    private const val CACHE_DIR_NAME = "portal-sdk"

    /** Cache of successfully loaded libraries by canonical path. */
    private val loaded = ConcurrentHashMap<String, PortalNativeLibrary>()

    /** Test seam: overrides the packaged index resource stream. */
    internal var indexResourceStream: () -> InputStream? = {
        DesktopNativeLibraryLoader::class.java.classLoader
            ?.getResourceAsStream(INDEX_RESOURCE)
    }

    /** Test seam: overrides a packaged binary resource stream. */
    internal var binaryResourceStream: (String) -> InputStream? = { name ->
        DesktopNativeLibraryLoader::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_PREFIX + name)
    }

    /** Test seam: overrides the user cache root. */
    internal var cacheRoot: () -> Path = { defaultCacheRoot() }

    /** Test seam: overrides the SDK version used in the cache path. */
    internal var sdkVersion: () -> String = { SDK_VERSION }

    /** Test seam: overrides OS/arch detection. */
    internal var osName: () -> String = { System.getProperty("os.name") ?: "" }
    internal var osArch: () -> String = { System.getProperty("os.arch") ?: "" }

    /**
     * Resolves the native library for [explicitPath] or the packaged runtime,
     * verifies it, and returns a loaded [PortalNativeLibrary].
     */
    fun load(explicitPath: Path? = null): PortalNativeLibrary =
        loadResolved(explicitPath).library

    /**
     * Resolves and loads, returning the resolution metadata for diagnostics.
     */
    fun loadResolved(explicitPath: Path? = null): LoadedNativeLibrary {
        val resolved = resolve(explicitPath)
        val canonical = resolved.path.toAbsolutePath().normalize().toString()
        val library = loaded.computeIfAbsent(canonical) {
            try {
                Native.load(canonical, PortalNativeLibrary::class.java)
            } catch (t: Throwable) {
                throw PortalException(
                    PortalFailure.Codes.NATIVE_UNAVAILABLE,
                    "failed to load libportaltunnel at $canonical: ${t.message}",
                    operation = "native_load"
                )
            }
        }
        return LoadedNativeLibrary(library, resolved)
    }

    internal data class LoadedNativeLibrary(
        val library: PortalNativeLibrary,
        val resolved: ResolvedNativeLibrary
    )

    /**
     * Resolves which file to load without loading it. Verifies the packaged
     * resource SHA-256 before extraction and re-verifies a cached entry.
     */
    fun resolve(explicitPath: Path? = null): ResolvedNativeLibrary {
        val os = detectOs()
        val arch = detectArch()
        if (explicitPath != null) {
            return resolveExplicit(explicitPath, os, arch)
        }
        return resolvePackaged(os, arch)
    }

    // --- explicit override ---------------------------------------------------

    private fun resolveExplicit(path: Path, os: DesktopOs, arch: DesktopArchitecture): ResolvedNativeLibrary {
        val abs = path.toAbsolutePath().normalize()
        val expectedExt = when (os) {
            DesktopOs.LINUX -> ".so"
            DesktopOs.WINDOWS -> ".dll"
            DesktopOs.MACOS -> ".dylib"
        }
        if (!abs.isAbsolute || !Files.isRegularFile(abs)) {
            throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "explicit nativeLibraryPath is not a regular file: $abs",
                operation = "native_load"
            )
        }
        if (!abs.fileName.toString().endsWith(expectedExt)) {
            throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "explicit nativeLibraryPath must end with $expectedExt for ${os.name.lowercase()}: $abs",
                operation = "native_load"
            )
        }
        return ResolvedNativeLibrary(abs, sha256(abs), os, arch, NativeLibrarySource.EXPLICIT)
    }

    // --- packaged resource ---------------------------------------------------

    private fun resolvePackaged(os: DesktopOs, arch: DesktopArchitecture): ResolvedNativeLibrary {
        val target = targetDir(os, arch)
        val index = readIndex()
        val entry = index[target]
            ?: throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "no packaged libportaltunnel for $target",
                operation = "native_load"
            )
        val resourceName = "$target/${entry.file}"
        val cacheDir = cacheRoot().resolve(CACHE_DIR_NAME).resolve(sdkVersion()).resolve(entry.sha256)
        val targetFile = cacheDir.resolve(entry.file)

        // Fast path: a verified cache entry already exists.
        if (Files.isRegularFile(targetFile) && sha256(targetFile) == entry.sha256) {
            return ResolvedNativeLibrary(targetFile, entry.sha256, os, arch, NativeLibrarySource.PACKAGED)
        }

        extractLocked(resourceName, entry.sha256, cacheDir, targetFile)
        return ResolvedNativeLibrary(targetFile, entry.sha256, os, arch, NativeLibrarySource.PACKAGED)
    }

    /**
     * Extracts [resourceName] to [targetFile] under a lock file in the same
     * directory, writing a sibling temp file, fsyncing, verifying the hash,
     * then atomically moving. Concurrent processes converge on one entry.
     */
    private fun extractLocked(resourceName: String, expectedSha: String, cacheDir: Path, targetFile: Path) {
        try {
            Files.createDirectories(cacheDir)
        } catch (e: IOException) {
            throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "cannot create native cache dir $cacheDir: ${e.message}",
                operation = "native_load"
            )
        }
        val lockFile = cacheDir.resolve(".extract.lock")
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ).use { channel ->
            channel.lock().use { _: FileLock ->
                // Re-check inside the lock: another process may have finished.
                if (Files.isRegularFile(targetFile) && sha256(targetFile) == expectedSha) {
                    return
                }
                val tmp = cacheDir.resolve("${targetFile.fileName}.tmp-${ProcessHandle.current().pid()}")
                try {
                    binaryResourceStream(resourceName)?.use { input ->
                        // fsync via FileChannel.force so the temp file is
                        // durable before the atomic move.
                        FileChannel.open(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                        ).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(java.nio.ByteBuffer.wrap(buf, 0, n))
                            }
                            out.force(true)
                        }
                    } ?: throw PortalException(
                        PortalFailure.Codes.NATIVE_UNAVAILABLE,
                        "packaged resource missing: $resourceName",
                        operation = "native_load"
                    )
                    val actual = sha256(tmp)
                    if (actual != expectedSha) {
                        throw PortalException(
                            PortalFailure.Codes.NATIVE_UNAVAILABLE,
                            "packaged resource hash mismatch for $resourceName: expected $expectedSha, got $actual",
                            operation = "native_load"
                        )
                    }
                    try {
                        Files.move(tmp, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp, targetFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }
    }

    // --- index ---------------------------------------------------------------

    internal data class IndexEntry(val file: String, val sha256: String)

    private fun readIndex(): Map<String, IndexEntry> {
        val text = indexResourceStream()?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "packaged native index not found: $INDEX_RESOURCE",
                operation = "native_load"
            )
        return parseIndex(text)
    }

    /** Minimal JSON parse for the generated index (no extra dependency). */
    internal fun parseIndex(text: String): Map<String, IndexEntry> {
        val result = LinkedHashMap<String, IndexEntry>()
        val entryRe = Regex(
            """"([^"]+)"\s*:\s*\{\s*"file"\s*:\s*"([^"]+)"\s*,\s*"sha256"\s*:\s*"([0-9a-fA-F]{64})""""
        )
        for (m in entryRe.findAll(text)) {
            result[m.groupValues[1]] = IndexEntry(m.groupValues[2], m.groupValues[3])
        }
        return result
    }

    // --- platform detection --------------------------------------------------

    private fun detectOs(): DesktopOs {
        val name = osName().lowercase()
        return when {
            name.startsWith("linux") -> DesktopOs.LINUX
            name.startsWith("windows") || name.startsWith("win") -> DesktopOs.WINDOWS
            name.startsWith("mac") || name.startsWith("darwin") -> DesktopOs.MACOS
            else -> throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "unsupported desktop os: ${osName()}",
                operation = "native_load"
            )
        }
    }

    private fun detectArch(): DesktopArchitecture {
        val arch = osArch().lowercase()
        return when {
            arch == "x86_64" || arch == "amd64" || arch == "x64" -> DesktopArchitecture.X86_64
            arch == "aarch64" || arch == "arm64" -> DesktopArchitecture.ARM64
            else -> throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "unsupported desktop architecture: ${osArch()}",
                operation = "native_load"
            )
        }
    }

    private fun targetDir(os: DesktopOs, arch: DesktopArchitecture): String = when (os) {
        DesktopOs.LINUX -> when (arch) {
            DesktopArchitecture.X86_64 -> "linux-x64"
            DesktopArchitecture.ARM64 -> throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "linux arm64 is not in the first release matrix",
                operation = "native_load"
            )
        }
        DesktopOs.WINDOWS -> when (arch) {
            DesktopArchitecture.X86_64 -> "windows-x64"
            DesktopArchitecture.ARM64 -> throw PortalException(
                PortalFailure.Codes.NATIVE_UNAVAILABLE,
                "windows arm64 is not in the first release matrix",
                operation = "native_load"
            )
        }
        DesktopOs.MACOS -> "macos-universal"
    }

    private fun defaultCacheRoot(): Path {
        val os = detectOs()
        val home = System.getProperty("user.home") ?: "."
        return when (os) {
            DesktopOs.LINUX -> Path.of(
                System.getenv("XDG_CACHE_HOME") ?: "$home/.cache"
            )
            DesktopOs.WINDOWS -> Path.of(
                System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"
            ).resolve("cache")
            DesktopOs.MACOS -> Path.of(home, "Library", "Caches")
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** SDK version baked into the cache path; kept in sync with the artifact. */
    private const val SDK_VERSION = "0.1.0"
}
