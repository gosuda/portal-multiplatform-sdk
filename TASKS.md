# Tasks

## Planned — KMP Desktop expansion

Detailed architecture, file ownership, packaging, CI matrix, RED/GREEN steps,
and release criteria:
[`docs/DESKTOP_IMPLEMENTATION_PLAN.md`](docs/DESKTOP_IMPLEMENTATION_PLAN.md).

- [x] D0 — Proved the current C ABI from a JVM/JNA Linux host (throwaway
      harness called `PortalGenerateIdentity`/`PortalParseIdentity` through
      JNA against the real `.so`).
- [x] D1 — Added `jvm("desktop")`, `PortalDesktop`, safe identity defaults,
      and the desktop JNA engine (`PortalNativeLibrary`,
      `DesktopPortalEngine`, lazy native load).
- [x] D2 — Built and verified the linux-x64 `libportaltunnel.so` from the Go
      bridge (`scripts/build-desktop-engine.sh` + `verify-desktop-engine.sh`,
      glibc 2.17 baseline via zig cc); windows-x64 and macos-universal are
      produced by the CI release matrix.
- [x] D3 — Packaged checksum-indexed native resources in
      `portal-native-desktop` with deterministic, content-addressed
      extraction (file lock + fsync + atomic move) and no runtime downloads.
- [x] D4 — Added a Compose Desktop sample publishing a loopback HTTP server
      plus a headless `:samples:desktop:smoke` real-relay publish check.
      Follow-up (2026-09-18): desktop-native sidebar layout, a real playable
      Minecraft 1.21.1 server (`:samples:desktop:minecraftSmoke` join test),
      and in-app Ollama setup (`OllamaSetup` managed daemon).
- [x] D5 — Added a per-OS `desktop-native` CI build/verify matrix and a
      `desktop-package` job gating on the complete runtime matrix; verified a
      clean Maven Local consumer resolves the desktop variant and loads the
      packaged engine offline.
- [ ] D6 — Publish only after provenance, licensing, size, signing, and
      documentation gates pass. Android binaries are now reproducibly built
      from `native/bridge`; release publication still needs a green full
      matrix and Maven Central upload.

## Assessed — Kotlin/Wasm support

Feasibility evidence, rejected compatibility shims, future gates, and the
separate browser-control alternative:
[`docs/WASM_SUPPORT_ASSESSMENT.md`](docs/WASM_SUPPORT_ASSESSMENT.md).

- [x] Browser `wasmJs` cannot preserve the current local HTTP/TCP/UDP,
      filesystem, identity, or native-engine `PortalClient` contract.
- [x] Kotlin `wasmWasi` currently targets WASI 0.1, while Go `wasip1` lacks
      portable full socket open/listen support required by the Portal engine.
- [x] Do not publish a compile-only/no-op Wasm target or hide a remote fallback
      behind `PortalClient`.
- [ ] Re-evaluate a WASI component only when Kotlin and the Portal engine share
      a production Component Model/socket generation and pass real network
      scenarios on at least two runtimes.
- [ ] If browser management becomes a product requirement, design a separately
      named web-control artifact over a secure management API; do not treat it
      as a local tunnel runtime.

## Planned — SDK usability roadmap

Detailed API contracts, file-by-file steps, RED/GREEN checks, release
packaging, verification commands, and exit criteria:
[`docs/SDK_USABILITY_IMPLEMENTATION_PLAN.md`](docs/SDK_USABILITY_IMPLEMENTATION_PLAN.md).

### P0 — Define the easy path

- [x] Set onboarding budgets: Android local HTTP publish in ≤10 app-code lines;
      Swift in ≤15 lines; no caller-managed identity path on either platform.
- [x] Add compile-only consumer fixtures for the documented Android/Kotlin and
      Swift entry points so examples cannot drift from the exported API.
      (`samples/android/.../QuickStartContract.kt`,
      `samples/ios/QuickStartContract.swift` — wired into both build targets;
      Android/Swift compilation pending a host with the SDK/Xcode.)

### P1 — Make platform defaults safe

- [x] Change `PortalClientHolder` and `PortalTunnelService` to construct
      `PortalClient` with an Android application context, giving identities a
      writable persistent default path.
- [x] Give `PortalIosClient` a platform-derived Application Support identity
      path when the caller does not provide one.
- [x] Lead Android documentation with `PortalClient(context)`, not the
      context-free constructor.

### P2 — Add intent-oriented configuration

- [x] Keep `PortalConfig` as the exact v1 wire DTO, but add additive factories
      for HTTP upstream, HTTP routes, TCP, UDP, and static-site exposure.
- [x] Add iOS-specific config factories that avoid the generated all-fields
      initializer and `KotlinBoolean` at Swift call sites.
- [x] Preserve the raw constructor and builder as advanced escape hatches;
      validate every factory through the existing `ConfigValidation` path.

### P3 — Add one-call publishing

- [x] Add a high-level `publish` operation that opens a tunnel, waits for
      inferred readiness, and rolls the session back if readiness fails.
- [x] Expose equivalent Kotlin suspend and iOS completion-based operations;
      keep `open` for callers that need accepted-before-ready semantics.
- [x] Return the owned tunnel/session so state, URLs, live updates, retryable
      stop, and explicit ownership remain available.

### P4 — Simplify distribution

- [ ] Publish the Android/KMP artifact to Maven Central.
      `android_ndk_revision` is pinned to r29 and the previous external
      binary-license blocker was removed: Gradle now builds both JNI `.so`
      files from the MIT-licensed `native/bridge`.
- [x] Package the iOS engine with each published KMP klib and XCFramework
      slice so consumers do not build or manually link `libportaltunnel.a`.
- [ ] Publish the generated versioned Swift package/XCFramework release asset.
      The release workflow now builds and uploads the self-contained zip and
      `Package.swift`; attaching them to the final GitHub release remains part
      of the release operation.

#### Next release verification

- [ ] Push commit `24b3875` and run `Publish Multiplatform SDK` on a macOS
      release runner.
- [x] Confirm all three staged iOS klibs contain `libportaltunnel.a`, the clean
      KMP consumer links its `iosArm64` framework, and the self-contained
      XCFramework passes embedded-symbol verification.
      (Verified 2026-09-19 on macOS arm64: all three cinterop klibs embed the
      archive, `verify-ios-maven-consumer.sh` links `iosArm64`, and
      `package-ios-xcframework.sh` passes symbol checks on both slices.)
- [ ] Publish the validated Android, desktop, and iOS Maven graph to Maven
      Central.
- [x] Attach `PortalSDK.xcframework.zip` and the generated versioned
      `Package.swift` to the matching GitHub release.
      (Automated 2026-09-19: the publish workflow now generates the Dokka
      reference and attaches the XCFramework zip, `Package.swift`, and
      `dokka-html.zip` to the tag's GitHub release after Maven Central
      succeeds; it still needs a green release run.)

### P5 — Rebuild onboarding around outcomes

- [x] Replace the primary quick starts with copy-paste HTTP publish examples
      using the high-level APIs; move raw config/lifecycle detail later.
- [x] Add focused recipes for TCP, UDP, routes, static content, foreground
      Android operation, identity persistence, and structured failure handling.
      (2026-09-19: README "Recipes" section covers all seven; structured
      failures documented with the new typed fields.)
- [x] Update both sample apps to use the easy path, while retaining one
      advanced screen or fixture that covers the low-level API.
      (2026-09-19: Android/desktop contents declare factory-built
      `baseConfig` merged with editor fields and publish via `client.publish`;
      the iOS sample uses `PortalIosConfigFactory.custom` + `publish`.
      `AdvancedContract.kt`/`.swift` fixtures keep raw `PortalConfig` +
      `open` compiled; desktop `Smoke.kt` retains `open` + `awaitReady`.)

### P6 — Remove remaining consumer friction

#### Installation and first success

- [ ] Publish one canonical install path per consumer: one Gradle coordinate
      for Android/Desktop/KMP and one copy-paste SwiftPM package URL for iOS.
      Remove repository-build instructions from the primary onboarding path.
- [ ] Add clean, standalone Android, Desktop, KMP-iOS, and Swift consumers that
      are not included builds and resolve only released artifacts in CI.
- [ ] Make each clean consumer publish a loopback HTTP server and assert a real
      request through the returned public URL; compilation alone is
      insufficient.
- [ ] After publication, cut the Android and Desktop samples over to the exact
      released Maven Central version and build them without
      `portal.samples.repository`, Maven Local, dependency substitution, or
      any SDK publication task in the same Gradle invocation.
- [ ] After the GitHub release is published, replace the iOS sample's local
      `../../dist/PortalSDK.xcframework` reference with the versioned remote
      Swift package URL; the repository-built XCFramework may remain only in
      pre-release artifact verification.
- [ ] Add a post-release clean-consumer workflow that checks out/builds the
      samples independently, resolves only the just-published Maven Central
      and SwiftPM artifacts, and fails if any SDK dependency comes from this
      repository's project modules, `build/sample-maven`, or `dist/`.
- [x] Add a release compatibility table covering SDK version, Kotlin version,
      Android API/NDK requirements, iOS deployment target, JVM version, and
      supported native architectures.
      (2026-09-19: README "Compatibility" table.)

#### Easy-path API

- [x] Add a ready-result convenience surface so the common `publish` path can
      read its primary public URL directly without navigating
      `tunnel.state.value.primaryPublicUrl`; preserve live state for advanced
      consumers.
      (2026-09-19: `PortalTunnel.publicUrl` +
      `PortalIosSession.primaryPublicUrl`; `state` unchanged.)
- [x] Provide one managed-lifetime pattern per platform: Android lifecycle/
      foreground ownership, Swift cancellation and owner teardown, and JVM
      close/use semantics. Each pattern must stop only sessions owned by that
      client.
      (2026-09-19: `PortalClient.use {}` for JVM/desktop/common,
      `PortalClientHolder.publish` + `PortalTunnelService` on Android,
      `PortalOperation.cancel` + `PortalIosClient.close` on iOS.)
- [x] Define stable, typed publish failures with operation, retryability,
      terminal reason, and cleanup outcome available without parsing exception
      messages; map the same fields into Swift-friendly errors.
      (2026-09-19: `PortalFailure.operation` = `publish`/`publish_cleanup`,
      `terminalPhase`, `readinessFailure`; all exported to Swift.)
- [x] Add an opt-in diagnostics snapshot that reports SDK/engine versions,
      current phase, selected relay, and last structured failure without
      exposing identity secrets.
      (2026-09-19: `PortalDiagnostics.engineVersion` +
      per-session `activeRelay`/`lastFailure`.)
- [x] Review generated Swift names from the real XCFramework and add facade
      methods only where Kotlin-exported names, optionals, or callbacks remain
      awkward; compile every documented Swift call site.
      (2026-09-19: reviewed `PortalSDK.h` on macOS — added
      `PortalIosConfigFactory.custom` and `PortalIosSession.primaryPublicUrl`;
      `QuickStartContract.swift` + `AdvancedContract.swift` compile in the
      sample target; `docs/SWIFT_API.md` records the reviewed surface.)

#### Outcome-oriented guidance

- [x] Add a minimal troubleshooting decision tree for “no public URL,” relay
      connection failure, local upstream refusal, permission/background limits,
      and shutdown failure, keyed by structured error fields.
      (2026-09-19: `docs/TROUBLESHOOTING.md` decision tree.)
- [x] Publish generated Kotlin API reference and a reviewed Swift symbol/API
      reference, and link both directly from the install and quick-start
      sections.
      (2026-09-19: Dokka plugin added; the release workflow generates and
      attaches `dokka-html.zip` to the GitHub release, linked from Install.
      `docs/SWIFT_API.md` is the reviewed Swift reference, linked from
      Install and the Swift quick start.)

#### Usability acceptance gates

- [x] Keep first successful HTTP publication within 10 Android/Kotlin app-code
      lines and 15 Swift app-code lines, excluding imports and UI rendering.
      (QuickStartContract.kt: 4 app lines; QuickStartContract.swift: 8.)
- [ ] Verify identity persistence and automatic reuse across process relaunch
      on Android and iOS without a caller-supplied filesystem path.
- [x] Verify cancellation during connect, readiness timeout, terminal relay
      failure, and cleanup failure all leave ownership observable and do not
      silently leak a native session.
      (Covered by PortalClientTest: publishCancellationStopsSession,
      publishTimeoutStopsSession, publishTerminalSessionDoesNotResurrect,
      publishCleanupFailureLeavesRetryableSession/ExposesReadinessFailure.)
- [ ] Run onboarding from a clean machine/workspace with no repository checkout,
      record time-to-first-public-URL, and remove every undocumented prerequisite
      found during the exercise.

### Exit criteria

- [ ] A clean Android consumer resolves one released dependency and obtains a
      public URL without supplying an identity path. (Blocked on P4 publish.)
- [ ] A clean iOS consumer installs one released package/artifact and obtains a
      public URL without manually linking the Go archive or filling every
      `PortalConfig` field. Packaging and clean-consumer link verification are
      wired; the release workflow must pass on macOS.
- [x] Existing wire golden tests, lifecycle tests, platform builds, and
      real-relay Android/iOS smoke scenarios remain green.
      (2026-09-19 macOS arm64: `desktopTest` 65/65 green,
      `iosSimulatorArm64Test` 47/47 green incl. real-engine smoke,
      `assembleDebug`/`compileKotlinDesktop`/`compileKotlinIosSimulatorArm64`
      all pass. Real-relay Android/iOS device smokes remain manual.)

## Done (2026-09-17)

- [x] KMP module layout: `:portal-sdk` (common/android/ios/linuxX64),
      `:portal-native-android` (JNI bridge + prebuilt `.so`), `:samples:android`
- [x] Common API: `PortalClient`, `PortalTunnel`, `PortalSnapshot`,
      `TunnelPhase`, `Capability`, `PortalFailure`/`PortalException`,
      `PortalMetadata`, `PortalIdentity` (redacted), `PortalDiagnostics`
- [x] v1 wire contract preserved (snake_case keys, `encodeDefaults=false`)
- [x] Lifecycle: owner-scoped start, cancellation rollback, orphan event
      buffer, retryable stop, terminal-state protection, drop metrics
- [x] Engines: Android JNI facade (`PortalJni`), Kotlin/Native cinterop
      (`CApiPortalEngine`), iOS callback facade (`PortalIosClient`)
- [x] Tests: 20 commonTest cases via FakeEngine (androidHostTest) +
      linuxX64 native stub test — all green locally
- [x] `native/source-lock.json` provenance (SHA-256, 16KB alignment verified)
- [x] Sample Android app builds; APK contains both `.so`s
- [x] Process-global `PortalEventHub` event routing (multi-client safe)
- [x] Relay rules aligned with `utils.NormalizeRelayURL` upstream
- [x] `explicitApi()` mode; `PortalRelayStatus.isMitm`/`isActive`
- [x] README logo + full docs, CONTRIBUTING.md
- [x] `portal-android-lifecycle` module (PortalClientHolder + PortalTunnelService)
- [x] R8 release build verified; Maven Local publish + clean-consumer resolve
- [x] `PortalIosSession` parity: refresh/addRelay/removeRelay/updateMetadata/awaitReady
- [x] `PortalClient.isClosed`, `awaitActive`, `isActive`, close mutex,
      non-cancellable cleanup scope, hub dispatch guard, drop counters

## Blocked / next

- [x] Go mobile bridge reimplemented (`native/bridge`, clean-room over
  portal-tunnel v2.4.3 `sdk.Exposure`); `scripts/build-ios-engine.sh`
  builds per-target `libportaltunnel.a`
- [x] iOS: archives linked via per-target `linkerOpts` + `-framework
  Security`; `ios*Test` re-enabled when archives exist;
  `iosSimulatorArm64Test` 29/29 green incl. real-engine smoke test
- [x] Real-relay tunnel verified on iOS simulator (public URLs issued,
  clean stop); `PortalSDK.xcframework` built; sample app installed and
  launched on iPhone 15 Pro
- [ ] Exercise a tunnel from the on-device app UI (manual step; no UI
  automation)
- [x] Replaced externally sourced Android `.so` files with reproducible JNI
      builds from `native/bridge` using Go 1.27.1 and Android NDK r29
- [ ] ABI v2 (create/attach/start split, observer quiescence, native
  revisions) — tracked in docs/DESIGN_RULES.md
