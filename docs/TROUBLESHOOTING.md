# Troubleshooting Log

### [2026-09-18] Desktop release verifier rejected valid Windows and macOS engines

- **Context / Symptom:** The release matrix reported all 11 Windows
  `Portal*` exports missing even though cgo generated the matching header.
  On macOS it reported `libportaltunnel.dylib`, the universal-binary
  architecture headings, and `/usr/lib/libresolv.9.dylib` as unexpected
  dependencies.
- **Root Cause:** The Windows parser stopped at the blank line between GNU
  `objdump`'s Export Address Table and `[Ordinal/Name Pointer] Table`, where
  exported names actually appear. The macOS parser treated every `otool -L`
  line after the first as a dependency, but universal output contains a
  heading per architecture and each dylib slice lists its `LC_ID_DYLIB`.
  The allowlist also omitted macOS's system resolver library.
- **Solution:** Parse indexed `Portal*` rows from the full Windows output.
  For macOS, accept only tab-indented load-command rows, exclude the dylib's
  own install ID, and allow versioned `/usr/lib/libresolv.*.dylib`. Added
  representative parser self-tests and run them in both release workflows.
- **Prevention / Reference:** ABI verification parsers must use the real
  multi-section/multi-architecture tool formats rather than blank-line or
  global-line-number assumptions.

### [2026-09-18] Sample ignored isolated Maven repository and queried Central

- **Context / Symptom:** After publishing `portal-sdk:0.1.0` into
  `build/sample-maven`, the desktop sample still failed with `Could not
  resolve io.github.gosuda:portal-sdk:0.1.0` and queried Maven Central.
- **Root Cause:** `samples/desktop/build.gradle.kts` declared its own
  `repositories` block. Gradle preferred project repositories over the
  repository injected by `dependencyResolutionManagement` from
  `-Pportal.samples.repository`.
- **Solution:** Removed the project-level repository block so all projects
  use the settings-level repositories. The staged repository now precedes
  Google and Maven Central.
- **Prevention / Reference:** Keep repository policy centralized in
  `settings.gradle.kts`; otherwise consumer-verification repositories can be
  silently shadowed by project-local declarations.

### [2026-09-18] `generateNativeIndex` fails on a fresh clone — `Input file does not exist`

- **Context / Symptom:** On a Windows checkout without `native/desktop/`,
  `:portal-native-desktop:generateNativeIndex` failed during Gradle input
  validation: `property 'nativeDesktopDir' specifies directory
  'D:\…\native\desktop' which doesn't exist`.
- **Root Cause:** `nativeDesktopDir` was a plain `@InputDirectory`, so
  Gradle validated the directory exists before the task ran. The task's
  own graceful path (missing binaries → partial-matrix warning) never
  executed. `native/desktop/` is gitignored — engine binaries are built
  by `scripts/build-desktop-engine.sh` or the CI matrix, not committed.
- **Solution:** Added `@get:Optional` to `nativeDesktopDir`. The task now
  runs on a fresh clone and emits the partial-matrix warning; a release
  build still fails via `requireComplete=true` when binaries are absent.
- **Prevention / Reference:** A `@InputDirectory` that may legitimately
  be absent needs `@Optional`; Gradle validates input existence before
  the task's own missing-input handling can run.


### [2026-09-18] Ollama download 404 — wrong asset names and format

- **Context / Symptom:** `OllamaSetup.install()` failed with
  `download failed: HTTP 404` on Linux. The URLs pointed at
  `ollama.com/download/ollama-linux-amd64.tgz` and
  `ollama.com/download/ollama-darwin.zip`.
- **Root Cause:** Two problems. (1) The Linux release asset is
  `ollama-linux-amd64.tar.zst` (Zstandard), not `.tgz` — the `.tgz` name
  was never published. (2) The macOS asset is `Ollama-darwin.zip` with a
  capital O; the lowercase name 404s. `ollama.com/download/` also
  redirects inconsistently; GitHub release assets are the stable source.
- **Solution:** Switched all URLs to
  `github.com/ollama/ollama/releases/latest/download/…` with the correct
  names. Linux `.tar.zst` is decompressed via `zstd-jni` (no `zstd`
  binary needed) then untarred; Windows extracts the full install tree
  so `ollama.exe` finds its bundled `lib/` DLLs.
- **Prevention / Reference:** Verify release asset names against
  `api.github.com/repos/ollama/ollama/releases/latest` — they differ per
  OS and change format (`.tar.zst`, not `.tgz`/`.zip` for Linux).


### [2026-09-18] `inner` class + `enum` inside a Kotlin `object` fails to compile

- **Context / Symptom:** `MinecraftServer` (a singleton `object`) declared
  `private inner class Session` containing `enum class State`; the build
  failed with `Modifier 'inner' is not applicable inside 'standalone
  object'` and `'Enum class' is prohibited here`.
- **Root Cause:** An `object` has no outer instance, so `inner` is
  meaningless; Kotlin also forbids `enum`/`inner`/`sealed`/`interface`
  declarations inside an `inner` class. Both rules fired at once.
- **Solution:** Dropped `inner` (a nested class in an `object` still reads
  the object's members unqualified) and moved the enum to object level as
  `SessionState`.
- **Prevention / Reference:** In a Kotlin `object`, use plain nested
  classes and object-level enums; reserve `inner` for real outer classes.

### [2026-09-18] `mark/reset` on an unbuffered socket stream silently no-ops

- **Context / Symptom:** Sniffing the first byte of a Minecraft connection
  (HTTP vs protocol) via `DataInputStream(socket.getInputStream())` +
  `mark(1)/read/reset` would not have worked — `markSupported()` is false
  on a raw socket stream, so `reset()` throws `IOException`.
- **Root Cause:** `mark/reset` is only honored by streams that buffer;
  `Socket.getInputStream()` does not.
- **Solution:** Wrapped the socket stream in `BufferedInputStream` before
  `DataInputStream`, giving real `mark/reset` support.
- **Prevention / Reference:** Always wrap socket input in
  `BufferedInputStream` when peeking; check `markSupported()` before
  relying on `mark/reset`.


### [2026-09-18] `processResources` placed native runtime at JAR root

- **Context / Symptom:** `portal-native-desktop` JAR contained `index.json`
  and `linux-x64/libportaltunnel.so` at the root instead of under
  `META-INF/portal-native/`, so `DesktopNativeLibraryLoader` could not find
  the packaged resource.
- **Root Cause:** `into("META-INF/portal-native")` was called on the
  `processResources` task itself, not on the `from(...)` source — the
  destination applies per-source, not per-task.
- **Solution:** Nest the `into` inside the `from` block:
  `from(layout.buildDirectory.dir("generated/portal-native")) { into("META-INF/portal-native") }`.
- **Prevention / Reference:** In Gradle `CopySpec`, `into`/`exclude`/`include`
  configure the enclosing `from` source; a bare `into` on the task sets the
  destination dir, not a resource prefix.

### [2026-09-18] Desktop `PortalClient()` failed without a packaged engine

- **Context / Symptom:** `PortalClientTest.portalEntryPointCreatesClient` and
  `builderCreatesWorkingClient` failed on desktop with `IllegalStateException`
  even though the tests only construct and close a client.
- **Root Cause:** `DesktopPortalEngine` loaded the native library eagerly in
  its constructor, so `PortalClient()` threw `NATIVE_UNAVAILABLE` when no
  packaged `.so` was on the classpath — unlike Android, where JNI loads
  lazily.
- **Solution:** Made `DesktopPortalEngine` resolve the library lazily on the
  first native call (matching Android's JNI behavior); the constructor now
  takes a `() -> PortalNativeLibrary` provider.
- **Prevention / Reference:** Platform engines must not touch native code at
  construction — a client with no sessions must close cleanly without a
  native dependency.

### [2026-09-18] Desktop identity path created the directory too early

- **Context / Symptom:** Per-OS identity-path tests failed with
  `PortalException: cannot create identity directory` because
  `DesktopIdentityPath.resolve` ran `Files.createDirectories` eagerly at
  `PortalDesktop.client(...)` construction.
- **Root Cause:** `PortalClient.defaultIdentityPath` was a plain `String?`
  resolved once at construction, so the directory was created before
  `open`/`publish` — contradicting the lazy-creation contract (and iOS).
- **Solution:** Changed `defaultIdentityPath` to a `(() -> String?)?`
  provider invoked inside `open`; split `DesktopIdentityPath` into a pure
  `pathFor` (no IO) and `resolve` (creates the dir, called lazily).
  `PortalDesktop.client` validates `applicationId` eagerly but defers
  directory creation to the operation.
- **Prevention / Reference:** Identity-path resolution that touches the
  filesystem must be deferred to `open`/`publish` so failures surface as
  `PERMISSION_DENIED` through the operation, not at client construction.


### [2026-09-18] Gradle could not locate Android SDK on Windows

- **Context / Symptom:** `publishToMavenLocal` failed with `SDK location not
  found` because neither `ANDROID_HOME` nor `local.properties` supplied an
  SDK path.
- **Root Cause:** The Android SDK was installed at the standard per-user path,
  but the shell environment did not export it.
- **Solution:** Ran Gradle with
  `ANDROID_HOME=C:\Users\mingy\AppData\Local\Android\Sdk`; alternatively set
  `sdk.dir` in the gitignored `local.properties`.
- **Prevention / Reference:** Configure one of those paths before running
  Android Gradle tasks on a fresh Windows checkout.

### [2026-09-17] `org.jetbrains.kotlin.android` rejected by AGP 9

- **Context / Symptom:** `Failed to apply plugin 'org.jetbrains.kotlin.android'`
  — "no longer required for Kotlin support since AGP 9.0".
- **Root Cause:** AGP 9.x ships built-in Kotlin support; the separate
  kotlin-android plugin is an error, not a no-op.
- **Solution:** Removed the plugin from `portal-native-android` and
  `samples/android`; Kotlin `jvmTarget` is set via the top-level `kotlin {
  compilerOptions {} }` extension the built-in plugin registers.
- **Reference:** https://kotl.in/gradle/agp-built-in-kotlin

### [2026-09-17] `androidLibrary {}` deprecated in KMP plugin

- **Context / Symptom:** `'androidLibrary' block is deprecated. Please use
  'android' instead` when configuring the KMP Android target.
- **Root Cause:** `com.android.kotlin.multiplatform.library` renamed the DSL
  block; the JetBrains template still used the old name.
- **Solution:** Use `android { namespace/compileSdk/minSdk/withHostTestBuilder/
  compilerOptions }` inside `kotlin {}`.

### [2026-09-17] cinterop `char**` out-params need `CPointerVar`, not `CPointer`

- **Context / Symptom:** `Argument type mismatch: CPointer<CPointer<ByteVar>>
  vs CValuesRef<CPointerVarOf<CPointer<ByteVar>>>` for every `Portal*` call.
- **Root Cause:** `alloc<CPointer<ByteVar>>()` produces the wrong pointed
  type; `char**` maps to `CPointerVar<ByteVar>` and `const char*` params take
  `CValuesRef` (`string.cstr` inside `memScoped`).
- **Solution:** `alloc<CPointerVar<ByteVar>>()`, pass `.ptr`, and give the
  native-call lambdas a `MemScope` receiver so `.cstr` is in scope.

### [2026-09-17] `munmap_chunk(): invalid pointer` in linuxX64Test

- **Context / Symptom:** `NativeStubTest.tunnelLifecycleThroughCAbi` crashed
  the test process with an invalid `free()`.
- **Root Cause:** The event callback freed its `const char*` arguments via
  `PortalFreeString`. The stub (and plausibly the real Go bridge) passes
  non-heap/static buffers; the iOS SDK never frees callback args while the
  Flutter SDK does — the Go bridge source is unavailable to settle it.
- **Solution:** Do not free callback arguments; document the contract in
  DESIGN_RULES.md §4. Out-params are still freed exactly once.
- **Prevention:** When the mobile bridge source is recovered, verify whether
  callback strings are `C.CString`-allocated per call and revisit.

### [2026-09-17] `fetchAndIncrement` missing on common `AtomicLong`

- **Context / Symptom:** `Unresolved reference 'fetchAndIncrement'` in
  commonMain.
- **Root Cause:** `kotlin.concurrent.atomics.AtomicLong` does not mirror the
  full `java.util.concurrent.atomic` API surface.
- **Solution:** Use `addAndFetch(1)` (returns the incremented value).

### [2026-09-17] iOS test binaries cannot link without the engine archive

- **Context / Symptom:** `iosSimulatorArm64Test`/`iosX64Test` would link the
  real `libportaltunnel`, which is not shipped (source-lock: MISSING).
- **Solution:** `linkDebugTestIos*`/`linkReleaseTestIos*`/`ios*Test` tasks are
  disabled in `portal-sdk/build.gradle.kts` only while
  `native/ios/<target>/libportaltunnel.a` is absent;
  `compileTestKotlinIos*` still type-checks test sources. With the archives
  present (built by `scripts/build-ios-engine.sh`) the tasks link and run
  against the real engine.

### [2026-09-17] `PortalStop` raced the serve-exit goroutine on `serveErr`

- **Context / Symptom:** `native stop failed (code 1): tunnel stop timed
  out` even though the tunnel logged a clean shutdown and emitted STOPPED.
- **Root Cause:** `PortalStop` and the serve-exit goroutine both received
  from the capacity-1 `serveErr` channel; whichever lost the race blocked
  until the 15 s timeout.
- **Solution:** The serve-exit goroutine is now the sole `serveErr`
  consumer; `markStopped` closes a separate `done` channel that
  `PortalStop`/`PortalStopAll` wait on.
- **Prevention:** Never add a second consumer to a "result" channel; use a
  broadcast `done` channel for waiters.

### [2026-09-17] Gradle does not relink when an external `.a` changes

- **Context / Symptom:** After rebuilding `libportaltunnel.a`,
  `linkDebugTestIosSimulatorArm64` stayed UP-TO-DATE and the test binary
  kept the old engine.
- **Root Cause:** The archive is produced outside Gradle, so the link task
  had no declared input for it.
- **Solution:** `inputs.file(native/ios/<target>/libportaltunnel.a)` is
  declared on each `linkDebugTestIos*` task.

### [2026-09-17] Go runtime on iOS needs `Security.framework`

- **Context / Symptom:** `Undefined symbols for architecture arm64:
  _SecTrustEvaluateWithError, _SecCertificateCopyData, …` when linking the
  Go archive.
- **Root Cause:** Go's Darwin TLS path calls the Security framework.
- **Solution:** Add `-framework Security` to the consumer's linker flags
  (already in `portal-sdk` linkerOpts and `samples/ios/project.yml`).

### [2026-09-17] Kotlin/Native does not export default-arg constructors to Swift

- **Context / Symptom:** `'init()' is unavailable` for
  `PortalIosClient()` in Swift.
- **Root Cause:** Kotlin default parameter values are not exported; only
  the full-argument initializer exists in the Objective-C/Swift surface.
- **Solution:** Call `PortalIosClient(allowRemoteTargets: false,
  defaultIdentityPath: nil)` explicitly.

### [2026-09-18] Orphan STOPPED event left a zombie session in `client.sessions`

- **Context / Symptom:** A new `publish` test that emits `STOPPED` inside the
  native `start` (the orphan-buffer path) failed: `client.sessions` still
  contained the terminal tunnel after `open` returned.
- **Root Cause:** `PortalClient.open` called `PortalEventHub.register(tunnel)`
  (which drains buffered orphan events, including `STOPPED` → `markTerminal` →
  `unregisterTunnel`) *before* `registry.register(tunnel)`. The session was
  therefore registered only after it had already been unregistered — a zombie.
- **Solution:** Reordered to `registry.register(tunnel)` then
  `PortalEventHub.register(tunnel)`, so a terminal transition during the
  orphan drain unregisters a session that actually exists.
- **Prevention / Reference:** Any registration pair where one side can
  synchronously tear the other down must register the teardown target first.
  Covered by `publishTerminalSessionDoesNotResurrect` in `PortalClientTest`.

### [2026-09-18] Concurrent close could re-register an event route

- **Context / Symptom:** Review of the orphan-event registration fix found a
  second interleaving: `close()` could stop and unregister a just-created
  tunnel after `registry.register`, then `open()` could continue with
  `PortalEventHub.register` and re-add a route owned by the closed client.
- **Root Cause:** The final closed-state check and the two registration calls
  were not atomic with the `closeMutex` section that marks the client closed
  and snapshots its sessions.
- **Solution:** Wrapped the final `closed` check plus registry/hub registration
  in `closeMutex`. Native start remains outside the mutex; a handle finishing
  after close is stopped non-cancellably and rejected with CLIENT_CLOSED.
- **Prevention / Reference:** Covered by
  `closeDuringNativeStartRejectsAndStopsLateHandle`; lifecycle publication
  must cross the owner-close boundary in one critical section.
