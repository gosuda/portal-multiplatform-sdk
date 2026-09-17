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

- [ ] Recover Go mobile bridge source (`portal-tunnel/mobile` is gone from
      upstream) — required for iOS archive, ABI v2, and release gate G0
- [ ] iOS: build `libportaltunnel.a` per target, embed via
      `staticLibraries`/`libraryPaths` in `portaltunnel.def`, re-enable
      `ios*Test` link tasks, verify `PortalSDK.xcframework` on macOS
- [x] Real-relay integration verified on emulator (3 relays ready, HTTP 200)
- [x] Android release + R8 verified; iOS device/simulator still blocked on libportaltunnel.a
- [x] Maven Local clean-consumer resolves portal-sdk-android + portal-native-android
- [x] Android minSdk = 26 (decided); 32-bit ABIs not shipped (no .so)
