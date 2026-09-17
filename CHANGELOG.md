# Changelog

## 2026-09-17

- feat(sdk): add `PortalClient`/`PortalTunnel` common API with `StateFlow`
  snapshots, bounded event stream, `awaitReady`, typed metadata, and
  per-client session ownership
- feat(sdk): add Android engine via `:portal-native-android` JNI bridge with
  prebuilt `libportaltunnel.so` (arm64-v8a, x86_64; 16KB-page aligned)
- feat(sdk): add Kotlin/Native cinterop engine shared by iOS
  (arm64/simulatorArm64/x64) and experimental linuxX64 targets
- feat(ios): add `PortalIosClient`/`PortalIosSession` callback facade with
  explicit subscriptions and main-dispatcher completions
- feat(sdk): add config validation (identity XOR, relay scheme/host rules,
  loopback targets, route exclusivity, capability pre-check)
- fix(sdk): defensive-copy Builder lists, redact identity document from
  `toString`, buffer pre-registration events, keep failed stops retryable,
  roll back native handles on `open` cancellation
- build: add `native/source-lock.json` provenance and linuxX64 C stub test
- docs: add DESIGN_RULES, TROUBLESHOOTING, TASKS, agent skill
- ci: run Gradle workflow only on `release-*` tags and manual dispatch
