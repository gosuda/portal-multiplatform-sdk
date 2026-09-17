# Tasks

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
