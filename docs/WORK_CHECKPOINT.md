# Work Checkpoint

## Active task
iOS implementation review + completion — **complete for this session's scope**.

## State (2026-09-17)
- Branch `main`; prior sessions produced the KMP SDK, Android sample, and
  iOS SwiftUI sources. This session made iOS work end-to-end.
- Go mobile bridge reimplemented: `native/bridge` (clean-room over
  portal-tunnel v2.4.3 `sdk.Exposure`, `-buildmode=c-archive`).
  `scripts/build-ios-engine.sh` builds `native/ios/<target>/libportaltunnel.a`
  for iosArm64 / iosSimulatorArm64 / iosX64 (gitignored).
- `portal-sdk/build.gradle.kts`: per-target `linkerOpts` (`-lportaltunnel`
  + `-framework Security`) for the framework and debug test binaries;
  archive declared as link-task input; iOS link/test tasks disabled only
  when archives are absent.
- Verified locally:
  - `./gradlew :portal-sdk:iosSimulatorArm64Test` — 29 tests green
    (incl. new `IosEngineSmokeTest` hitting the real Go bridge)
  - `./gradlew :portal-sdk:assemblePortalSDKReleaseXCFramework` — static
    XCFramework with ios-arm64 + ios-arm64_x86_64-simulator slices
  - Real-relay tunnel on the iOS simulator: 2-3 relays ready, public URLs
    issued, clean stop (temporary test, removed after verification)
  - `samples/ios` builds for device via xcodegen project; installed and
    launched on iPhone 15 Pro (PID stable)
  - `./gradlew :portal-sdk:testAndroidHostTest` — 25 tests green
- Fixed in the bridge: `PortalStop`/`serveErr` consumer race (done channel),
  `STARTED`/`STATUS_CHANGED`/`STOPPED`/`ERROR`/`MITM_SUSPECTED` events,
  `PortalStatus` JSON synthesized from `exposure.Relays()`.
- Fixed in the sample: `PortalIosClient(allowRemoteTargets:defaultIdentityPath:)`
  (K/N exports no default-arg init), missing `id`/`listener` fields,
  `Self`/instance-member property-initializer errors, `htonl` →
  `INADDR_LOOPBACK.bigEndian`, folder resources for `site/`/`site-explainer/`.

## Next action
If continuing: exercise a real tunnel from the on-device app UI (manual —
no UI automation available), then resolve `license_review` and
`android_ndk_revision` in `native/source-lock.json` before any release.
See TASKS.md "Blocked / next".

## Environment notes
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
