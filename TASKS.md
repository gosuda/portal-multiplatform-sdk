# Tasks

## Planned — KMP Desktop expansion

Detailed architecture, file ownership, packaging, CI matrix, RED/GREEN steps,
and release criteria:
[`docs/DESKTOP_IMPLEMENTATION_PLAN.md`](docs/DESKTOP_IMPLEMENTATION_PLAN.md).

- [ ] D0 — Prove the current C ABI from a JVM/JNA Linux host.
- [ ] D1 — Add `jvm("desktop")`, `PortalDesktop`, safe identity defaults, and
      the desktop JNA engine.
- [ ] D2 — Build and verify Linux x86_64, Windows x86_64, and macOS universal
      native libraries from the existing Go bridge.
- [ ] D3 — Package checksum-indexed native resources with deterministic,
      content-addressed extraction and no runtime downloads.
- [ ] D4 — Add a Compose Desktop sample that publishes a loopback HTTP server.
- [ ] D5 — Verify each supported OS in CI plus clean Maven consumers and
      end-to-end real-relay smoke scenarios.
- [ ] D6 — Publish only after provenance, licensing, size, signing, and
      documentation gates pass.

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

- [ ] Clear `license_review` and `android_ndk_revision`, then publish the
      Android/KMP artifact to Maven Central.
      (`android_ndk_revision` resolved → r29 via `.so` `.comment`; `license_review`
      still PENDING — `portal-android-sdk`, the `.so` source repo, ships no
      LICENSE file. Needs an upstream license grant or a rebuild from
      MIT-licensed `portal-tunnel` source.)
- [ ] Package the iOS engine with the SDK distribution so consumers do not
      build or manually link `libportaltunnel.a`.
      (`scripts/package-ios-xcframework.sh` written — merges the Go archive
      into each framework slice, rebuilds the XCFramework, emits a SwiftPM
      manifest; requires macOS to run and verify.)
- [ ] Provide a versioned Swift Package or binary XCFramework release with one
      reproducible installation path. (Blocked on the packaging step above.)

### P5 — Rebuild onboarding around outcomes

- [x] Replace the primary quick starts with copy-paste HTTP publish examples
      using the high-level APIs; move raw config/lifecycle detail later.
- [ ] Add focused recipes for TCP, UDP, routes, static content, foreground
      Android operation, identity persistence, and structured failure handling.
      (Factories and lifecycle/identity sections exist; dedicated structured
      failure handling and per-mode recipes are still incomplete.)
- [ ] Update both sample apps to use the easy path, while retaining one
      advanced screen or fixture that covers the low-level API.
      (Compile-only quick-start fixtures were added, but the real sample UI
      flows still construct raw `PortalConfig` and call `open`.)

### Exit criteria

- [ ] A clean Android consumer resolves one released dependency and obtains a
      public URL without supplying an identity path. (Blocked on P4 publish.)
- [ ] A clean iOS consumer installs one released package/artifact and obtains a
      public URL without manually linking the Go archive or filling every
      `PortalConfig` field. (Blocked on P4 packaging.)
- [ ] Existing wire golden tests, lifecycle tests, platform builds, and
      real-relay Android/iOS smoke scenarios remain green.
      (`linuxX64Test` 39/39 is green; Android/iOS builds and real-relay smokes
      have not been run on this host.)

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
- [ ] Resolve `license_review` + `android_ndk_revision` in
  `native/source-lock.json` (release gate)
- [ ] ABI v2 (create/attach/start split, observer quiescence, native
  revisions) — tracked in docs/DESIGN_RULES.md
