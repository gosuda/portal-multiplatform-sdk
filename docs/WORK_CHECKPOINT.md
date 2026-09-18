# Work Checkpoint

## Active task
SDK usability implementation — **P0–P3, P5 done; P4 partially blocked**.

## State (2026-09-18)
- Implemented per `docs/SDK_USABILITY_IMPLEMENTATION_PLAN.md`:
  - `PortalConfig` companion factories: `http`, `routes`, `tcp`, `udp`,
    `staticSite` (commonMain; wire DTO unchanged).
  - `PortalClient.publish(config, timeoutMillis)`: `open` + `awaitActive` +
    rollback `stop` on failure/cancellation; cleanup failures surface with
    `operation="publish_cleanup"` and leave the session retryable.
  - `PortalIosClient.publish` + `PortalIosConfigFactory` (Swift facade);
    `PortalIosClient` gained a private primary ctor + internal engine-injecting
    ctor for tests.
  - `IosIdentityPath` (iosMain/internal): Application Support
    `Portal/identity.json` resolved inside `open`/`publish`; filesystem
    failures → PERMISSION_DENIED, never a temp-path fallback.
  - `PortalClientHolder.init(context)` + `PortalTunnelService` now build
    context-backed clients (application context only).
  - Compile fixtures: `QuickStartContract.kt` (Android sample) and
    `QuickStartContract.swift` (added to `samples/ios/project.yml`).
  - `scripts/package-ios-xcframework.sh`: merges `libportaltunnel.a` into each
    framework slice, rebuilds the XCFramework, emits SwiftPM `Package.swift`.
  - `publish.yml`: `release-*` tag + `release(scope):` subject gate +
    provenance-lock check (was GitHub-release trigger).
  - `source-lock.json`: `android_ndk_revision` resolved → r29
    (clang-r563880c, read from `.so` `.comment`); `license_review` stays
    PENDING — `portal-android-sdk` (the `.so` source repo) has no LICENSE.
- Bug found by the new tests and fixed: `open` registered the tunnel with the
  session registry only *after* draining orphan events, so a STOPPED emitted
  inside the native start left a zombie session — registration order swapped
  (recorded in docs/TROUBLESHOOTING.md).
- Verification on this host (WSL2, no Android SDK / macOS):
  - `./gradlew :portal-sdk:linuxX64Test --offline` → 38/38 green incl. 6 new
    publish tests + 6 factory tests.
  - `compileCommonMainKotlinMetadata` clean.
  - NOT verifiable here: Android/iOS target compilation, iosMain/iosTest,
    sample builds, real-relay smokes, XCFramework packaging, Maven publish.
- Docs updated: README (publish-first quick starts, factories, identity
  defaults), skill api-reference, DESIGN_RULES (publish + platform identity
  contracts), CHANGELOG, TASKS (status + blockers), TROUBLESHOOTING.

## Next action
On the macOS host: run `./gradlew :portal-sdk:iosSimulatorArm64Test
:samples:android:assembleDebug`, build the iOS sample via xcodegen+xcodebuild
(compiles `QuickStartContract.swift` and validates the Swift API spelling),
then run `scripts/package-ios-xcframework.sh` and a clean SwiftPM consumer.
`license_review` needs an upstream LICENSE on `gosuda/portal-android-sdk` or a
rebuild of the `.so`s from MIT-licensed `portal-tunnel` source.

## Prior environment notes (2026-09-17 macOS session)
- Android SDK at `~/Android/Sdk` (platform 36, build-tools 36.0.0) via
  `local.properties` (gitignored).
- JDK 17, Gradle 9.6.1 wrapper, Kotlin 2.4.10, AGP 9.1.0.
- Go 1.27.1 darwin/arm64, Xcode 27.0, xcodegen 2.46.0 (brew).
- iPhone 15 Pro connected (UDID 00008130-001E58C13AE0001C), team
  `37FAA8L9Q7` in `samples/ios/project.yml`.
- AGP 9: no `org.jetbrains.kotlin.android` plugin; use `android {}` not
  `androidLibrary {}` in the KMP block. See docs/TROUBLESHOOTING.md.

## Blockers
- `license_review` and `android_ndk_revision` still gate releases
  (source-lock.json). No code blockers for iOS.
