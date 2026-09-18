# Troubleshooting Log

## Decision tree — runtime symptoms → structured fields → fix

Start from the symptom, read the named field, apply the fix. All fields are
on `PortalFailure` (`tunnel.state.value.lastFailure`, the `PortalException`,
or the iOS completion's `PortalFailure`) unless noted.

### "No public URL" — tunnel runs but `publicUrls` stays empty

1. `snapshot.phase` is `CONNECTING` for a long time → the relay handshake is
   still in flight; keep waiting or check `snapshot.relays[].state`.
2. `snapshot.relays[].state == "failed"` → read `relay.failure`/`relay.error`;
   a `mitm` failure sets `snapshot.hasSecurityWarning` — do not trust that
   endpoint, switch networks or relays.
3. `snapshot.lastFailure.code == "RELAY_UNAVAILABLE"` → the configured relay
   is unreachable; verify the URL (`https`, no `ws://`), or enable discovery.
4. `snapshot.lastFailure.code == "NETWORK_UNAVAILABLE"` → device connectivity;
   retry when the network returns (`retryable == true`).

### Relay connection failure

- `code == "RELAY_UNAVAILABLE"` → relay refused or unreachable; check the URL
  and that the relay serves the v1 protocol.
- `code == "INVALID_CONFIG"` with a relay message → scheme/host rejected:
  `https` only, `http` only for loopback, no user-info, port 1–65535.
- `hasSecurityWarning == true` → MITM self-probe failed on a relay; treat the
  endpoint as untrusted.

### Local upstream refusal (tunnel ACTIVE, requests fail)

- `code == "INVALID_CONFIG"` mentioning `target_addr`/`udp_addr` →
  non-loopback target without `allowRemoteTargets`, or a missing port.
- Requests time out while the tunnel is ACTIVE → the app-local server is not
  listening on the configured loopback address; verify `target_addr` matches
  the server's bind address and that the server started before `publish`.

### Permission / background limits

- `code == "PERMISSION_DENIED"`, `operation == "identity_path"` → the
  platform identity directory could not be created; check app storage
  permissions (Android `filesDir`, iOS Application Support, desktop app dir).
- Tunnel dies when the app backgrounds → Android: move the tunnel to a
  `PortalTunnelService` foreground service; iOS: no indefinite background
  execution is promised — keep the app foregrounded.

### Shutdown failure

- `stop()` threw → the session stays registered in `STOPPING`
  (`snapshot.phase`), retry `stop()`; `lastFailure` holds the native error.
- `publish` failed with `operation == "publish_cleanup"` → readiness failed
  AND rollback failed; `failure.readinessFailure` has the original cause and
  the session remains in `client.sessions` for a manual `stop()` retry.

### Native engine problems

- `code == "NATIVE_UNAVAILABLE"` → engine binary missing/mismatched:
  Android ABI not shipped (arm64-v8a/x86_64 only), desktop packaged runtime
  absent or hash-mismatched, iOS archive not embedded in the klib.
- `code == "ABI_MISMATCH"` → engine older/newer than ABI v1; rebuild from
  `native/bridge` at the pinned portal-tunnel version.


### [2026-09-19] `@Optional` input directory still failed validation on fresh clones

- **Context / Symptom:** `./gradlew :portal-sdk:compileKotlinDesktop` on a
  clone without `native/desktop/` failed with `Input file does not exist …
  property 'nativeDesktopDir'` even though the property was annotated
  `@Optional`.
- **Root Cause:** `@Optional` permits a null/unset value but does not
  suppress existence validation for a set `@InputDirectory` — the directory
  was always set, so Gradle still demanded it exist.
- **Solution:** Mark the directory `@Internal` and track the expected
  binaries through a `ConfigurableFileCollection` `@InputFiles` input, which
  skips missing entries. `requireComplete` remains the release gate that
  turns absence into an error.
- **Prevention / Reference:** For "may not exist" inputs prefer file
  collections over `@Optional` scalar/directory inputs; verify on a clean
  checkout, not only where the directory already exists.

### [2026-09-19] XCFramework symbol check failed on macOS despite present symbols

- **Context / Symptom:** `scripts/package-ios-xcframework.sh` reported
  `PortalStart missing` from the simulator framework even though `nm`
  listed the symbol.
- **Root Cause:** `nm -g … | grep -Eq` — `grep -q` exits on the first match
  and closes the pipe; `nm` kept writing to the large static archive and
  died with SIGPIPE (141), which `set -o pipefail` turned into a pipeline
  failure. Linux runs never hit it because the archive was smaller.
- **Solution:** Consume the full listing (`grep -E … >/dev/null`) instead of
  `grep -q` inside `pipefail` pipelines.
- **Prevention / Reference:** Under `pipefail`, never pair `grep -q` with a
  producer that writes more than a few lines; redirect to `/dev/null` or
  capture then match.

### [2026-09-19] iOS sources had never compiled on macOS

- **Context / Symptom:** First `compileKotlinIosSimulatorArm64` on a macOS
  host failed: `IosIdentityPath.kt` used `NSFileManager` without the
  `ExperimentalForeignApi` opt-in.
- **Root Cause:** Previous verification ran on Linux where Apple cinterop is
  disabled, so the opt-in error was never compiled.
- **Solution:** Added `@OptIn(ExperimentalForeignApi::class)`; all three iOS
  variants now compile and `iosSimulatorArm64Test` runs 47 tests green.
- **Prevention / Reference:** Platform-gated source sets need a real host
  build in the verification matrix; dry-run task-graph checks do not compile.

### [2026-09-19] Desktop tests hardcoded Linux assumptions

- **Context / Symptom:** `desktopTest` on macOS failed: the JNA stub was
  built as `libportaltunnel_stub.so` (rejected — macOS needs `.dylib`), and
  two tests asserted `DesktopOs.LINUX`/`linux-x64` unconditionally.
- **Root Cause:** The stub task and the packaged-runtime tests were written
  and only ever run on the Linux checkpoint host.
- **Solution:** The stub extension now follows the host OS
  (`.so`/`.dylib`/`.dll`); `runtimeReportsPackagedSource` and
  `packagedResourceResolvesAndLoads` derive expected OS/arch from
  `os.name`/`os.arch`.
- **Prevention / Reference:** Any test that loads a real binary must
  parameterize host OS/arch; hardcoding the CI host's platform is a latent
  cross-platform failure.


### [2026-09-18] Downloaded desktop matrix was flattened and appeared missing

- **Context / Symptom:** The publish job downloaded all three successful
  desktop artifacts, but `GenerateNativeIndex` reported every expected binary
  missing under `native/desktop/<target>/`.
- **Root Cause:** `actions/download-artifact` used a wildcard with
  `merge-multiple: true`. Each upload's root was already the contents of its
  target directory, so merging flattened all files directly into
  `native/desktop/`.
- **Solution:** Download each named artifact explicitly into its exact
  `native/desktop/linux-x64`, `windows-x64`, or `macos-universal` directory in
  both CI and publication workflows.
- **Prevention / Reference:** Artifact upload/download roots are content
  boundaries, not preserved parent paths; reconstruct matrix directories
  explicitly before packaging.

### [2026-09-18] Published iOS SDK required consumers to link the Go archive

- **Context / Symptom:** KMP/Swift consumers could compile Portal APIs but had
  to build and link a matching `libportaltunnel.a` plus Security manually.
- **Root Cause:** The cinterop definition generated bindings only; target
  archives were linked from repository paths during framework/test builds and
  were absent from published klibs.
- **Solution:** Generate a target-specific `.def` with `staticLibraries`,
  `libraryPaths`, and Security `linkerOpts`; make every Apple cinterop depend
  on the source archive build. Published klibs and XCFramework slices are now
  self-contained.
- **Prevention / Reference:** For a private native implementation dependency,
  inspect the published `.klib` and require the static archive entry; a green
  producer build alone does not prove consumer packaging.

### [2026-09-18] Clean release runner could not write native `index.json`

- **Context / Symptom:** `publishDesktopSdkToSampleRepository` failed on the
  macOS runner with `FileNotFoundException:
  portal-native-desktop/build/generated/portal-native/index.json`.
- **Root Cause:** `GenerateNativeIndex` deleted its output directory, then
  only recreated target subdirectories while copying binaries. On a clean
  runner no directory existed when it wrote the top-level index.
- **Solution:** Recreate the declared output directory immediately after
  cleanup. Also stream binaries through `DigestInputStream` so hashing the
  complete release matrix does not allocate one byte array per native binary.
- **Prevention / Reference:** A task owning an `@OutputDirectory` must create
  that directory before writing root-level outputs; never rely on stale build
  directories from earlier tasks.

### [2026-09-18] AGP rejected a Provider-backed JNI source directory

- **Context / Symptom:** `:portal-native-android:buildAndroidEngine` failed
  during configuration with `You cannot add Provider instances to the Android
  SourceSet API`.
- **Root Cause:** AGP cannot classify a `DirectoryProvider` passed through the
  legacy `sourceSets.main.jniLibs.srcDir` API as generated or static.
- **Solution:** Resolve the deterministic build-directory path to a `File`
  when registering it as the JNI source directory, and keep explicit
  `dependsOn(buildAndroidEngine)` wiring on native merge/AAR tasks.
- **Prevention / Reference:** Use AGP's variant generated-source API when a
  custom task exposes a typed `DirectoryProperty`; otherwise pass a concrete
  path and wire the producer task explicitly.

### [2026-09-18] Multiplatform publication stopped at the source-lock gate

- **Context / Symptom:** `Publish Multiplatform SDK` stopped with
  `native/source-lock.json still has unresolved release gates` after native
  runtime builds completed.
- **Root Cause:** `portal-native-android` shipped two `.so` files copied from
  `gosuda/portal-android-sdk`. That repository has no LICENSE, so the files
  had neither a reproducible build path here nor established redistribution
  terms. The generic grep check also matched its own policy sentence and ran
  only after the expensive desktop matrix.
- **Solution:** Deleted the inherited binaries. Added an Android JNI shim to
  `native/bridge` and `build-android-engine.sh`; Gradle now builds arm64-v8a
  and x86_64 libraries with Go 1.27.1 and NDK r29 before AAR assembly or
  publication. CI installs the pinned toolchain, validates release gates
  first, and publishes only generated binaries.
- **Prevention / Reference:** Never clear a provenance/licensing marker merely
  to make CI green. Every native artifact published to consumers needs a
  traceable source, toolchain, checksum, and redistribution basis.

### [2026-09-18] Desktop release verifier rejected valid Windows and macOS engines

- **Context / Symptom:** The release matrix reported all 11 Windows
  `Portal*` exports missing even though cgo generated the matching header.
  On macOS it reported `libportaltunnel.dylib`, the universal-binary
  architecture headings, and `/usr/lib/libresolv.9.dylib` as unexpected
  dependencies.
- **Root Cause:** The first Windows parser stopped at the blank line before
  `[Ordinal/Name Pointer] Table`; its initial fix then assumed one exact
  `[ordinal] name` row shape. The Windows runner's MinGW `objdump` emitted a
  different column/annotation layout, so valid names still did not match.
  The macOS parser treated every `otool -L` line after the first as a
  dependency, but universal output contains a heading per architecture and
  each dylib slice lists its `LC_ID_DYLIB`. The allowlist also omitted
  macOS's system resolver library.
- **Solution:** Extract exact `Portal*` tokens from the complete PE metadata,
  independent of columns, annotations, and CRLF. For macOS, accept only
  tab-indented load-command rows, exclude the dylib's own install ID, and
  allow versioned `/usr/lib/libresolv.*.dylib`. Representative parser
  self-tests run in both release workflows.
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
