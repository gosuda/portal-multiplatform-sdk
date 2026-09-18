# KMP Desktop Expansion Implementation Plan

Date: 2026-09-18
Status: proposed; implementation not started

## 1. Decision

Add a **JVM desktop target** to `portal-sdk` and bind the existing
`libportaltunnel` v1 C ABI through **JNA**.

Supported first release matrix:

| Portal KMP target | Runtime | Native engine |
|---|---|---|
| `desktop` | JVM 17+ | JNA → `libportaltunnel` C ABI |
| Linux | x86_64, glibc | `libportaltunnel.so` |
| Windows | x86_64 | `portaltunnel.dll` |
| macOS | universal x86_64 + arm64 | `libportaltunnel.dylib` |

Linux arm64 is a follow-up target after the first three operating systems pass
real-relay smoke tests. Do not claim support from compilation alone.

### Why JVM + JNA

- Compose Multiplatform Desktop applications run on the JVM. A Kotlin/Native
  desktop target would produce a different application model and would not be
  consumable from a normal Compose Desktop project.
- The Android JNI binary is not reusable as the desktop architecture. Its
  exported JNI names are pinned to
  `org.gosuda.portal.android.internal.NativeBridge`, and the artifact is an
  Android AAR containing only Android ABIs.
- The plain C ABI in `native/include/portaltunnel.h` is already the stable
  cross-platform boundary used by Kotlin/Native. JNA can bind it without a
  second JNI-specific Go bridge or generated JNI symbol set.
- JNA keeps the desktop adapter small and testable. The only added runtime
  dependency lives in `desktopMain`; common, Android, and iOS consumers do not
  receive it.

### Rejected alternatives

1. **Reuse Android JNI class and symbols on desktop.** Rejected: Android
   package/class names become a desktop ABI contract, packaging remains tied
   to an AAR, and rebuilding per OS still requires JNI-specific exports.
2. **Kotlin/Native `linuxX64`/`mingwX64`/`macos*` as the public desktop SDK.**
   Rejected for the first release: it cannot be consumed by Compose Desktop/JVM
   and would duplicate the public artifact surface.
3. **Download native libraries at runtime.** Rejected: breaks offline use,
   complicates supply-chain verification, and makes first launch network-
   dependent.
4. **Ask consumers to set `jna.library.path`.** Retained only as an explicit
   development override, not as the production installation path.

## 2. Scope and non-goals

### In scope

- `jvm("desktop")` target in the existing `portal-sdk` KMP module.
- Desktop implementation of `PortalNativeEngine` over the v1 C ABI.
- Reproducible Go `c-shared` builds for Linux, Windows, and macOS.
- Verified, checksum-pinned native binary packaging.
- Desktop-safe identity persistence.
- Minimal Compose Desktop sample exposing a real loopback HTTP server.
- Host tests, native ABI tests, clean-consumer tests, and real-relay smokes on
  all supported operating systems.
- Maven publication metadata for the desktop KMP variant and native runtime.

### Out of scope

- ABI v2 or transport changes.
- Android/iOS API redesign.
- Linux arm64 in the first release.
- Background-service abstractions. Desktop apps explicitly own and close their
  `PortalClient`.
- Automatic authentication or firewall rule changes.
- Runtime downloading, silent fallbacks, or mock engines in production.
- Bundling a full Compose UI library inside `portal-sdk`; Compose belongs only
  in `samples:desktop`.

## 3. Architecture

```text
Compose Desktop / JVM application
  -> portal-sdk desktop variant
     -> commonMain PortalClient / PortalTunnel / PortalConfig
     -> desktopMain DesktopPortalEngine
        -> DesktopNativeLibraryLoader
        -> JNA PortalNativeLibrary
           -> libportaltunnel.so / portaltunnel.dll / libportaltunnel.dylib
              -> portal-tunnel Go sdk.Exposure
```

### Source-set layout

```text
portal-sdk/src/commonMain/       unchanged public lifecycle/config/state API
portal-sdk/src/desktopMain/      JVM/JNA adapter + desktop entry point
portal-sdk/src/desktopTest/      loader, ABI, lifecycle, identity-path tests
native/desktop/<os-arch>/        generated native binaries; gitignored locally
native/source-lock.json          release hashes/toolchains for shipped binaries
portal-native-desktop/           packaged, checksum-indexed runtime resources
samples/desktop/                 Compose Desktop app and loopback HTTP demo
```

Use one implementation of lifecycle/state/config in `commonMain`. Desktop may
add constructors/factories, but must not fork `PortalClient`, `PortalTunnel`,
`PortalSnapshot`, validation, event routing, or serialization.

## 4. Public API

### 4.1 Desktop entry point

Add in `desktopMain`:

```kotlin
public object PortalDesktop {
    public fun client(
        applicationId: String,
        storageDirectory: Path? = null,
        nativeLibraryPath: Path? = null,
        allowRemoteTargets: Boolean = false
    ): PortalClient

    public fun runtime(
        nativeLibraryPath: Path? = null
    ): PortalDesktopRuntime
}
```

Contract:

- `applicationId` is required, nonblank, and restricted to filesystem-safe
  segments (`[A-Za-z0-9._-]`, no separators or `..`). Different desktop apps
  must not silently share one Portal identity.
- `storageDirectory` overrides the platform application-data directory.
- Default identity path:
  - Linux: `${XDG_STATE_HOME:-~/.local/state}/<applicationId>/portal/identity.json`
  - Windows: `%LOCALAPPDATA%\<applicationId>\Portal\identity.json`
  - macOS: `~/Library/Application Support/<applicationId>/Portal/identity.json`
- Parent directories are created lazily inside `open`/`publish` semantics;
  failure becomes `PortalException(PERMISSION_DENIED, operation="identity_path")`.
- `nativeLibraryPath` is an explicit development/enterprise override. It must
  be an absolute regular file with the expected extension; failure is
  `NATIVE_UNAVAILABLE`. It never falls back silently after a supplied override
  fails.
- The returned object is the existing `PortalClient`. Ownership, `publish`,
  `open`, state, events, and retryable stop stay identical across platforms.

Do not add hidden global state, an automatic shutdown hook, or a desktop
singleton. The desktop app owns the client and closes it from its application
lifecycle.

### 4.2 Platform information

Expose a small diagnostic value from the desktop facade, not from commonMain:

```kotlin
public data class PortalDesktopRuntime(
    val os: DesktopOs,
    val architecture: DesktopArchitecture,
    val nativeLibraryPath: Path,
    val nativeSha256: String,
    val source: NativeLibrarySource // PACKAGED or EXPLICIT
)
```

`PortalDiagnostics` remains secret-free and platform-neutral. The desktop
runtime value contains paths and hashes only—never identity contents, tokens,
or request data.

`runtime()` resolves the same explicit-or-packaged library path as `client()`;
it does not expose or retain a process-global client/session.

## 5. JNA binding contract

Add an internal `PortalNativeLibrary : Library` matching
`native/include/portaltunnel.h` exactly:

```kotlin
internal interface PortalNativeLibrary : Library {
    fun PortalSetEventCallback(callback: PortalEventCallback?)
    fun PortalFreeString(pointer: Pointer?)
    fun PortalGenerateIdentity(name: Pointer?, out: PointerByReference, error: PointerByReference): Int
    fun PortalParseIdentity(json: Pointer, out: PointerByReference, error: PointerByReference): Int
    fun PortalStart(config: Pointer, out: PointerByReference, error: PointerByReference): Int
    fun PortalStop(tunnelId: Pointer, error: PointerByReference): Int
    fun PortalStopAll()
    fun PortalGetStatus(tunnelId: Pointer, out: PointerByReference, error: PointerByReference): Int
    fun PortalAddRelay(tunnelId: Pointer, relay: Pointer, error: PointerByReference): Int
    fun PortalRemoveRelay(tunnelId: Pointer, relay: Pointer, error: PointerByReference): Int
    fun PortalUpdateMetadata(tunnelId: Pointer, metadata: Pointer, error: PointerByReference): Int
}
```

Binding rules:

1. Allocate every input as explicit UTF-8, NUL-terminated JNA `Memory`; do not
   depend on platform default encodings.
2. Read out/error pointers as UTF-8 before freeing.
3. Free every non-null out/error `char*` exactly once through
   `PortalFreeString`, including nonzero-code paths.
4. Callback arguments are runtime-owned. Copy them synchronously and never
   free them, matching the current Go bridge and native adapter contract.
5. Keep a strong reference to the JNA callback for the engine lifetime so GC
   cannot invalidate the native function pointer.
6. The callback must never throw or block. It copies strings and hands them to
   `PortalEventHub`; exceptions stop at the boundary.
7. Map nonzero native codes to the existing `PortalException` shape with
   operation/nativeCode populated. Do not invent desktop-only exception
   classes.
8. Engine calls remain blocking and are dispatched by the existing common
   layer off caller threads.

The C header is the source of truth. Add an automated signature/ABI check so
the JNA declaration, cgo-generated header, and checked-in
`native/include/portaltunnel.h` cannot drift independently.

## 6. Native binary build

Extend `native/bridge` to support `-buildmode=c-shared`; do not create a second
Go bridge.

New scripts:

```text
scripts/build-desktop-engine.sh         host build for local development
scripts/verify-desktop-engine.sh        exports, ABI, dependency, hash checks
scripts/package-desktop-runtime.sh      resource layout + checksum index
```

Build outputs:

```text
native/desktop/linux-x64/libportaltunnel.so
native/desktop/windows-x64/portaltunnel.dll
native/desktop/macos-universal/libportaltunnel.dylib
```

### Linux

- Build x86_64 in a pinned old-glibc container (target baseline chosen and
  recorded before implementation; prefer glibc 2.17-compatible output).
- `CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared`.
- Inspect `DT_NEEDED`; permit only documented system libraries.
- Run on the baseline container and current Ubuntu CI.

### Windows

- Build on a Windows runner with a pinned MinGW-w64 toolchain; do not rely on
  an unverified cross-compiler from Linux.
- `CGO_ENABLED=1 GOOS=windows GOARCH=amd64 go build -buildmode=c-shared`.
- Inspect PE architecture/import table and run the real DLL on Windows CI.

### macOS

- Build arm64 and amd64 slices on macOS, then combine them with `lipo`.
- Set a supported deployment target and record it in `source-lock.json`.
- Verify both architectures and exported `Portal*` symbols.
- Sign the dylib before packaging. Signature remains embedded after resource
  extraction; the clean sample distribution must pass codesign/notarization
  verification.

### Required exported symbols

`PortalSetEventCallback`, `PortalFreeString`, `PortalGenerateIdentity`,
`PortalParseIdentity`, `PortalStart`, `PortalStop`, `PortalStopAll`,
`PortalGetStatus`, `PortalAddRelay`, `PortalRemoveRelay`,
`PortalUpdateMetadata`.

Every release binary gets SHA-256, Go version, C compiler/toolchain, target OS,
architecture, minimum OS/glibc version, and source commit recorded in
`native/source-lock.json`. Release remains blocked by unresolved provenance or
license markers.

## 7. Runtime packaging and loading

### Artifact model

Create `:portal-native-desktop`, a plain JVM library containing only native
runtime resources and a checksum index. `portal-sdk`'s desktop variant depends
on it at runtime.

Initial resource layout:

```text
META-INF/portal-native/index.json
META-INF/portal-native/linux-x64/libportaltunnel.so
META-INF/portal-native/windows-x64/portaltunnel.dll
META-INF/portal-native/macos-universal/libportaltunnel.dylib
```

The first release uses one self-contained runtime JAR. This is intentionally
chosen over classifier/variant complexity for reliable Gradle and IDE
resolution. Before release, measure the JAR. If compressed size exceeds the
agreed distribution budget, split it into OS-specific Gradle variants while
keeping `PortalDesktop.client` unchanged. Never switch to runtime downloads.

### Loader

`DesktopNativeLibraryLoader`:

1. Normalize `os.name` and `os.arch` to a closed enum. Unknown values fail
   `NATIVE_UNAVAILABLE` with the observed values.
2. If `nativeLibraryPath` was supplied, validate and load exactly that file.
3. Otherwise read `index.json`, choose the exact packaged resource, and verify
   the resource SHA-256 before extraction.
4. Extract into a versioned user cache:
   `<cache>/portal-sdk/<sdkVersion>/<sha256>/<filename>`.
5. Coordinate concurrent processes with a file lock. Write a sibling temporary
   file, fsync, verify SHA-256, then atomically move. Never overwrite a loaded
   binary in place (especially on Windows).
6. Reverify an existing cache entry before use; replace corrupt entries under
   the lock.
7. Load by absolute path through JNA. Convert `UnsatisfiedLinkError`,
   `LastErrorException`, architecture mismatch, and missing dependency errors
   to `PortalException(NATIVE_UNAVAILABLE, operation="native_load")` with no
   fallback to a different OS/architecture.
8. Cache one successful load per canonical path. Do not call `NativeLibrary`
   unload while sessions or callbacks may exist.

The explicit path override and packaged resource use the same JNA adapter and
ABI checks. No alternate behavior path.

## 8. Gradle/module changes

### `portal-sdk`

```kotlin
kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    sourceSets {
        desktopMain.dependencies {
            implementation(libs.jna)
            implementation(project(":portal-native-desktop"))
        }
    }
}
```

Confirm Vanniktech publishing emits a usable JVM/KMP variant and dependency
metadata. Do not publish the desktop variant until a clean external project
resolves it from Maven Local without project substitution.

### `portal-native-desktop`

- Plain `java-library` + Maven publication.
- Resource generation task consumes only verified files listed in
  `native/source-lock.json` and writes `index.json` deterministically.
- `processResources` fails if a supported matrix entry, hash, or binary is
  missing.
- Reproducible JAR ordering/timestamps enabled.

### `samples:desktop`

- Kotlin JVM + Compose Multiplatform application.
- Depends on project `:portal-sdk`; no direct JNA or native-runtime dependency.
- Uses JDK `HttpServer` on `127.0.0.1` to avoid adding a server framework only
  for the demo.
- Calls `PortalDesktop.client("org.gosuda.portal.sample.desktop")` and
  `publish(PortalConfig.http(...))`.
- Renders authoritative `PortalSnapshot`, public URLs, structured failure, and
  stop/retry state.
- Closes the tunnel/client from explicit application lifecycle code; no hidden
  shutdown hook.

## 9. Implementation phases

### D0 — Contract and build proof

Files:

- `docs/DESKTOP_IMPLEMENTATION_PLAN.md`
- `native/bridge/bridge.go` (only if c-shared requires portability guards)
- `scripts/build-desktop-engine.sh`
- `scripts/verify-desktop-engine.sh`
- `native/source-lock.json`

Steps:

1. Build a host Linux `.so` from the existing bridge.
2. Verify all required exports and generated header equivalence.
3. Call identity generate/parse and start rejection from a throwaway JNA
   program before changing `portal-sdk`.
4. Establish Linux glibc baseline, Windows MinGW version, macOS deployment
   target, and binary sizes.
5. Record provenance; do not copy unknown binaries from another SDK.

Exit: one real Linux c-shared engine is callable through JNA and frees every
returned string without leaks/crashes.

Commit: `build(desktop): produce verified native engine binaries`

### D1 — Desktop target and JNA adapter

Files:

- `portal-sdk/build.gradle.kts`
- `gradle/libs.versions.toml`
- new `portal-sdk/src/desktopMain/...`
- new `portal-sdk/src/desktopTest/...`

RED tests:

1. UTF-8 inputs/outputs and structured native errors.
2. Every out/error pointer freed exactly once on success and failure.
3. Callback strings copied, callback reference retained, callback exceptions
   contained.
4. Unknown OS/architecture rejected.
5. Explicit native path validation and no fallback.
6. Common `PortalClient` lifecycle tests run on the desktop JVM target with a
   fake engine.

GREEN implementation:

1. Add `jvm("desktop")` and desktop source sets.
2. Implement `PortalNativeLibrary`, UTF-8 memory helpers, and
   `DesktopPortalEngine`.
3. Implement desktop `actual platformNativeEngine()` using the loader.
4. Keep all public session behavior in commonMain.

Exit: desktop JVM tests exercise the real C stub through JNA, not a mock of
JNA calls.

Commit: `feat(desktop): add jvm jna engine adapter`

### D2 — Deterministic runtime packaging

Files:

- new `portal-native-desktop/`
- `settings.gradle.kts`
- `scripts/package-desktop-runtime.sh`
- `.gitignore`
- publication configuration

RED tests:

1. Correct OS/arch selects the exact resource.
2. Hash mismatch is rejected before load.
3. Concurrent extraction produces one valid cache entry.
4. Interrupted/corrupt cache entry is atomically replaced.
5. Windows-style locked target is never overwritten.

GREEN implementation:

1. Package verified binaries and deterministic `index.json`.
2. Implement version/hash cache, lock, fsync, and atomic move.
3. Publish runtime dependency only for desktop.
4. Build clean Maven Local consumer on Linux.

Exit: `implementation("io.github.gosuda:portal-sdk:<version>")` is the only
consumer dependency and a packaged engine loads offline.

Commit: `feat(desktop): package verified native runtimes`

### D3 — Desktop identity and ergonomics

Files:

- `PortalDesktop.kt`
- desktop path/runtime helpers
- desktop tests
- API reference/design rules

RED tests:

1. Application IDs reject blank, separators, and `..`.
2. Linux/Windows/macOS default identity paths are exact.
3. Explicit storage directory wins.
4. Directory failure maps to PERMISSION_DENIED/`identity_path`.
5. Two application IDs never share an identity path.

GREEN implementation:

1. Implement `PortalDesktop.client` and runtime diagnostics.
2. Inject the resolved default identity path into the existing `PortalClient`
   constructor; do not change `PortalConfig` wire fields.
3. Document explicit ownership and close behavior.

Commit: `feat(desktop): add safe application-scoped defaults`

### D4 — Compose Desktop sample

Files:

- new `samples/desktop/`
- `settings.gradle.kts`
- root/plugin version catalog entries

Behavior:

1. Start a JDK loopback HTTP server on an ephemeral port.
2. Publish with `PortalConfig.http` and `PortalClient.publish`.
3. Display phase, primary/all URLs, relays, security warning, and structured
   failure.
4. Open/copy public URL.
5. Stop/retry cleanly and close on application exit.
6. No simulated connection path.

Smoke: fetch the public URL and assert the body was served by the desktop
process.

Commit: `feat(sample): add desktop tunnel application`

### D5 — Cross-platform CI and release

Files:

- `.github/workflows/gradle.yml`
- `.github/workflows/publish.yml`
- native build/package scripts
- `native/source-lock.json`

Jobs (still only `release-*` tags and `workflow_dispatch`):

- Linux: build/verify `.so`, desktop tests, sample package, real-relay smoke.
- Windows: build/verify `.dll`, desktop tests, sample MSI/EXE package,
  real-relay smoke.
- macOS: build two dylib slices, lipo/sign, desktop tests, DMG/app package,
  codesign verification, real-relay smoke.
- Aggregate: compare hashes with source lock, build deterministic runtime JAR,
  Maven Local clean-consumer tests, then publish only after release gates pass.

Do not run relay-dependent tests as ordinary unit tests. Keep them explicit
release/manual smoke jobs with credentials and cleanup.

Commit: `ci(desktop): verify supported host matrix`

### D6 — Documentation and release cutover

Files:

- `README.md`
- `docs/DESIGN_RULES.md`
- `CHANGELOG.md`
- `TASKS.md`
- `.agents/skills/portal-multiplatform-sdk/`
- `docs/WORK_CHECKPOINT.md`

Add:

- Platform matrix with exact OS/architecture/minimum versions.
- One-dependency Compose Desktop quick start.
- Native cache/override diagnostics and failure handling.
- Firewall, proxy, antivirus, Gatekeeper, and Windows DLL troubleshooting.
- Desktop lifecycle guidance.
- Reproducible native build/provenance instructions.

Commit: `docs(desktop): add installation and lifecycle guide`

## 10. Verification matrix

| Layer | Linux | Windows | macOS |
|---|---|---|---|
| common lifecycle/config tests | JVM | JVM | JVM |
| JNA C-stub ABI test | `.so` | `.dll` | `.dylib` |
| real engine identity round trip | required | required | required |
| real loopback HTTP + relay | required | required | required |
| runtime resource/hash/cache | required | required | required |
| clean Maven consumer | required | required | required |
| Compose sample package launch | required | required | required |
| signature/notarization | n/a | optional signing policy | required |

Permanent tests must cover observable contracts: native memory ownership,
callback delivery, platform selection, cache integrity, identity isolation,
session ownership, readiness rollback, and structured failures. Do not retain
tests that assert source text, forwarding, or incidental loader internals.

## 11. Key risks

### Go `c-shared` platform compatibility

Risk: glibc baseline, MinGW imports, and macOS deployment target differ by
builder. Mitigation: native host matrix, pinned toolchains, dependency-table
inspection, source lock, and clean-machine execution before publication.

### JNA callback lifetime and native threads

Risk: callback GC or an exception crossing into Go crashes the process.
Mitigation: process-lifetime strong reference, synchronous copy, no blocking,
exception containment, and callback stress test.

### Native cache integrity

Risk: concurrent extraction or upgrade corrupts/locks a binary. Mitigation:
content-addressed version/hash paths, file lock, temp+fsync+atomic move, hash
reverification, and never overwriting loaded files.

### macOS hardened runtime

Risk: runtime-loaded unsigned dylib fails in signed/notarized apps. Mitigation:
sign the dylib before packaging, verify its signature after extraction, and
build/notarize the real Compose sample—not only a unit fixture.

### Artifact size

Risk: one JAR carrying three OS runtimes may be large. Mitigation: measure
before release. Prefer reliable one-dependency resolution initially; adopt
OS/arch Gradle variants only if the measured budget requires it. Runtime
download remains prohibited.

## 12. Definition of done

Desktop support is complete only when:

- A clean Compose Desktop project adds one Portal SDK dependency and compiles
  on Linux, Windows, and macOS.
- Each supported host loads the packaged native engine offline with a verified
  SHA-256.
- `PortalDesktop.client(applicationId)` persists a distinct identity in the
  correct platform directory.
- `publish(PortalConfig.http(...))` exposes a real desktop loopback server and
  the public URL returns the expected body on all three operating systems.
- Cancellation/readiness failure leaves no live session; cleanup failure stays
  visible and retryable.
- The desktop sample package launches and stops cleanly on all three hosts.
- macOS dylib and app signature/notarization verification passes.
- Maven Local and staged/released clean consumers resolve without project
  substitution, manual native paths, or direct JNA dependencies.
- Native source commits, toolchains, hashes, licenses, and minimum OS versions
  are complete in `native/source-lock.json`.
- README, design rules, skill, changelog, tasks, troubleshooting, and
  checkpoint match the verified product.
