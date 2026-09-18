# Tasks

## Planned — SDK usability roadmap

### P0 — Define the easy path

- [ ] Set onboarding budgets: Android local HTTP publish in ≤10 app-code lines;
      Swift in ≤15 lines; no caller-managed identity path on either platform.
- [ ] Add compile-only consumer fixtures for the documented Android/Kotlin and
      Swift entry points so examples cannot drift from the exported API.

### P1 — Make platform defaults safe

- [ ] Change `PortalClientHolder` and `PortalTunnelService` to construct
      `PortalClient` with an Android application context, giving identities a
      writable persistent default path.
- [ ] Give `PortalIosClient` a platform-derived Application Support identity
      path when the caller does not provide one.
- [ ] Lead Android documentation with `PortalClient(context)`, not the
      context-free constructor.

### P2 — Add intent-oriented configuration

- [ ] Keep `PortalConfig` as the exact v1 wire DTO, but add additive factories
      for HTTP upstream, HTTP routes, TCP, UDP, and static-site exposure.
- [ ] Add iOS-specific config factories that avoid the generated all-fields
      initializer and `KotlinBoolean` at Swift call sites.
- [ ] Preserve the raw constructor and builder as advanced escape hatches;
      validate every factory through the existing `ConfigValidation` path.

### P3 — Add one-call publishing

- [ ] Add a high-level `publish` operation that opens a tunnel, waits for
      inferred readiness, and rolls the session back if readiness fails.
- [ ] Expose equivalent Kotlin suspend and iOS completion-based operations;
      keep `open` for callers that need accepted-before-ready semantics.
- [ ] Return the owned tunnel/session so state, URLs, live updates, retryable
      stop, and explicit ownership remain available.

### P4 — Simplify distribution

- [ ] Clear `license_review` and `android_ndk_revision`, then publish the
      Android/KMP artifact to Maven Central.
- [ ] Package the iOS engine with the SDK distribution so consumers do not
      build or manually link `libportaltunnel.a`.
- [ ] Provide a versioned Swift Package or binary XCFramework release with one
      reproducible installation path.

### P5 — Rebuild onboarding around outcomes

- [ ] Replace the primary quick starts with copy-paste HTTP publish examples
      using the high-level APIs; move raw config/lifecycle detail later.
- [ ] Add focused recipes for TCP, UDP, routes, static content, foreground
      Android operation, identity persistence, and structured failure handling.
- [ ] Update both sample apps to use the easy path, while retaining one
      advanced screen or fixture that covers the low-level API.

### Exit criteria

- [ ] A clean Android consumer resolves one released dependency and obtains a
      public URL without supplying an identity path.
- [ ] A clean iOS consumer installs one released package/artifact and obtains a
      public URL without manually linking the Go archive or filling every
      `PortalConfig` field.
- [ ] Existing wire golden tests, lifecycle tests, platform builds, and
      real-relay Android/iOS smoke scenarios remain green.

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
