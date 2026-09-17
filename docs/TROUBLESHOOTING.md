# Troubleshooting Log

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
