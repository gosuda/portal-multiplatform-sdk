# Work Checkpoint

## Active task — 2026-11-01

The requested SDK cleanup, easy publishing APIs, five-mode workbenches and
shared four-game arcade are implemented and verified, including the packaged
macOS launcher. Delivery remains active: commit/push the verified source,
then update and launch the user's connected Galaxy S10 5G (SM-G977N, Android
12 / API 31). Record actual device results here and commit/push them.
Do not infer deployment completion from a feature commit or a clean tree.

Baseline: `main`, initially clean at `ea6dad8`. No release/tag/public binary
publication was requested. Ordinary commits must not use `release(scope):`.

## Implemented contracts

- Kotlin: application-owned client plus `publish(intentFactory(...))`.
  `PortalConfig.tcp(targetAddress, name?)` replaced the name-only factory
  (source-breaking). Identity persistence, readiness and rollback remain SDK-owned.
- Swift: real `PortalIosClient()`, one-argument config factories, route-entry
  helpers and default-30-second `publish(config:completion:)`; advanced
  configuration remains optional. All completions/observers stay on Main.
- Fixed IPv6, route/remote-target and static-path validation, UTF-8 metadata
  limits, live-relay normalization and diagnostic secret redaction.
- Preserved retryable STOPPING ownership, including failed publish/cancellation
  cleanup, iOS closed-owner observation and retry after failed client close.
- Upgraded the native bridge to portal-tunnel v2.7.1 / protocol 10. Native ABI
  v1 and wire defaults remain unchanged. ECH, x402/priced/method-constrained
  routes and overlay fail explicitly as unsupported.
- TCP/UDP readiness requires its own nonblank endpoint on a ready relay;
  disappearance revokes readiness. HTTP readiness alone cannot imply UDP readiness.
- Removed old model download/runtime installers, Minecraft/server code,
  fallback models, obsolete content/assets/smoke tasks, duplicate compile-only
  fixtures, unused dependency/version files and internal global-stop plumbing.
- Samples expose static files, real HTTP/webhooks, two-upstream prefix routes,
  TCP echo and UDP echo. Listeners bind ephemeral loopback ports; logs/input
  are bounded and do not retain bodies, headers or query values.
- `samples/shared/common/site` is the only arcade source: Snake, memory pairs,
  reaction testing and a volume-based sloshing/overflow water-fill game.
  Keyboard/touch controls, score/reset and visibility/input cleanup are real.
  No game backend, frontend dependencies, sensors or uploaded/global scores.
- Android assets, iOS resource folder and desktop application resources use
  that bundle. Desktop no longer copies/deletes a temporary static page.
- iOS cinterop tracks embedded native archives as inputs; engine upgrades no
  longer leave old Go code in cached klibs. Desktop teardown uses AWT's event
  queue, not an unavailable Main coroutine dispatcher.

## Changed file groups

- `portal-sdk/src/{commonMain,commonTest,androidMain,desktopMain,desktopTest,iosMain,iosTest,nativeMain}`
  and `portal-sdk/build.gradle.kts`: APIs, validation, lifecycle, adapters,
  consumer-visible regressions and native archive cache inputs.
- `portal-android-lifecycle`, `portal-native-android/build.gradle.kts`: safe
  ownership/cancellation and resolved Android SDK environment injection.
- `native/bridge`, `native/source-lock.json`, `native/README.md`: current engine,
  pruned dependency graph and truthful capability/provenance metadata.
- `samples/android`, `samples/desktop`, `samples/ios`, `samples/shared`:
  SDK-focused apps, real demo services, shared arcade and asset packaging.
- `scripts/native-provenance.py`, `verify-desktop-engine.sh`,
  `package-ios-xcframework.sh`, `verify-ios-swift-package.sh`: portable hashing,
  architecture checks and real-workbench Swift-package consumer verification.
- README/platform READMEs, design/API/plan/troubleshooting docs, changelog,
  skill references, workflow and dependency catalog were updated with the cutover.

## Observed verification

| SDK suite | Tests | Failures/errors/skips |
|---|---:|---:|
| `:portal-sdk:desktopTest` | 83 | 0 |
| `:portal-sdk:testAndroidHostTest` | 53 | 0 |
| `:portal-sdk:iosSimulatorArm64Test` | 71 | 0 |

- Original common sources reproduced 10 failures out of 53 targeted cases.
  The closed-owner iOS callback regression also failed before its fix.
- `go test ./...` in `native/bridge` passed (no Go test files). Real native
  builds/export/header/architecture checks passed for macOS universal,
  Android arm64/x86_64 with 16 KiB alignment, and all three iOS slices.
- Desktop/Android Maven staging, debug and R8 release sample APK builds passed.
- Desktop local smoke passed twice for HTTP, routes, TCP and UDP, including
  bounded activity, 413 rejection, listener shutdown and restart.
- Discovery-enabled ALL smoke passed all five public round trips earlier.
  A later discovery-enabled repeat selected an unreachable `gosunuts.xyz`
  TCP port; this remained a visible failure, not a synthetic success.
- Final controlled probe:
  `PORTAL_SMOKE_RELAYS=https://kakashit.org ./gradlew :samples:desktop:smoke
  -Pportal.smoke.mode=ALL -Pportal.smoke.discovery=false ...` passed all five
  modes with one explicit relay, including HTML/JavaScript MIME types and
  exact TCP/UDP echoes. Native SPA fallback was preserved; an invented
  missing-file 404 assertion was removed, not repinned.
- Desktop `createDistributable` passed with a full Temurin 17 toolchain and
  packaging checks enabled. A desktop-only clean rebuild removed obsolete
  incremental JAR resource entries. The actual packaged macOS launcher then
  published the bundled arcade: public HTML and `water.js` both returned 200.
  Normal window close cleaned up the session and exited with code 0.
- Android emulator: public API 200, webhook 202, distinct routed services,
  static arcade, TCP and UDP exact echo, same-session rotation, STOPPED and
  actual opted-in foreground-service start/removal were observed. One public
  relay TCP port timed out; another ready relay served the same listener.
- iOS: production SwiftUI app compiled for simulator; temporary XCTest
  exercised `PortalIosClient()` + factory + default publish, actual HTTPS
  arcade response and JavaScript MIME type, then owned stop to STOPPED.
- `scripts/verify-ios-swift-package.sh` compiled the actual workbench through
  the local Swift package for generic simulator and device, without manual
  native/Security linkage. Removed fixtures are not copied or recreated.
- Real Chromium: Snake food scoring/collision/restart/pause; memory eight-pair
  victory/mismatch cancellation/restart; reaction false-start rejection and
  keyboard timing; water target scoring, hold/release, tilt, real overflow and
  restart passed. A 390px touch viewport filled and dragged the cup without
  horizontal overflow. No JavaScript errors. The arcade also loaded and ran
  through an actual Android SDK public HTTPS tunnel.

## Commands / environment

- macOS arm64, Xcode 27.1, owned iOS 26.5 simulator; Gradle runs on JBR 21.
  Go module selects 1.27.3; Android NDK 29.0.14206865.
- Full packaging JDK:
  `$HOME/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home`.
  Temurin archive SHA-256 was verified before installation. Android Studio
  JBR lacks jpackage; Compose rejects the available Homebrew JDK for packaging.
- Sample repository argument:
  `-Pportal.samples.repository="$PWD/build/sample-maven"`.
- Final consumer commands use `--no-configuration-cache`,
  `-Porg.gradle.java.installations.auto-detect=false` and
  `-Porg.gradle.java.installations.paths="$JDK17"`.
- Relevant tasks: `:samples:desktop:createDistributable`,
  `:samples:desktop:workbenchSmoke`, `:samples:desktop:smoke
  -Pportal.smoke.mode=ALL`, `:samples:android:assembleDebug`,
  `:samples:android:assembleRelease`.
- iOS packaging/consumer: `:portal-sdk:assemblePortalSDKReleaseXCFramework`,
  `scripts/package-ios-xcframework.sh`, `scripts/verify-ios-swift-package.sh`,
  XcodeGen plus `xcodebuild` for `samples/ios/PortalSample.xcodeproj`.
- Temporary UIKit/browser/listener harnesses were used for runtime evidence;
  they must be removed after verification, not retained as compile-only fixtures.

## Exact next actions

1. Commit the verified source and this checkpoint; push `main` normally to
   `git@github.com:gosuda/portal-multiplatform-sdk.git` (no release tag).
2. Install `samples/android/build/outputs/apk/debug/android-debug.apk` with
   `adb -s "$PHONE" install -r ...` on the authorized SM-G977N. The new APK
   signer matches its installed app, so uninstall/data deletion is unnecessary.
   The phone is currently screen-off and securely locked; the user was asked
   to unlock it for interactive verification. Do not bypass the keyguard.
   Observe launch; if unlocked, publish/request the arcade and stop it,
   leaving no public session running on the user's phone.
3. Update this checkpoint with actual phone/push results, commit/push that
   verification record, and remove only owned temporary harnesses/devices/services.

## Remaining release gates (outside this request)

- No public Maven Central or GitHub binary release was performed.
- The old go-ethereum/LGPL dependency was pruned, but the v2.7.1 bundled
  dependency redistribution/notices review is still explicitly UNRESOLVED.
- Full Linux/Windows/macOS release-matrix, provenance/signing and release
  credentials/tag/commit-subject gates remain required. Linux/Windows runtime
  execution was not verified on this macOS host.
