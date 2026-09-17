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

## Blocked / next

- [ ] Recover Go mobile bridge source (`portal-tunnel/mobile` is gone from
      upstream) — required for iOS archive, ABI v2, and release gate G0
- [ ] iOS: build `libportaltunnel.a` per target, embed via
      `staticLibraries`/`libraryPaths` in `portaltunnel.def`, re-enable
      `ios*Test` link tasks, verify `PortalSDK.xcframework` on macOS
- [ ] Real-relay integration test (controlled relay, not public pool)
- [ ] Device verification: Android release + R8, iOS device + simulator
- [ ] Clean-consumer check: Maven Local + SwiftPM binary from a fresh project
- [ ] Decide Android minSdk (26 placeholder) and 32-bit ABI support
- [ ] Optional `portal-android-lifecycle` module (foreground-service owner)
