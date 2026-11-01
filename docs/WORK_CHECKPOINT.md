# Work Checkpoint

## Session outcome — 2026-11-01

The initial SDK cleanup, easy APIs, five-mode workbenches and four-game arcade
were implemented, verified and pushed as `04d3144`; retained JOSE/QUIC
dependency fixes were then rebuilt/verified and pushed as `a1db914`.
The completed additions were committed as `c0449bf`
(`feat(samples): add private local AI and monochrome workbenches`) and pushed
to `origin/main`; the push advanced `a1db914..c0449bf`. No release tag was created.
The final prefix/monochrome/local-AI/password APK was updated on the connected
Galaxy S10 5G (SM-G977N, Android 12 / API 31) with `adb install -r`.
Launch returned `Status: ok`, the app process ran, and the installed APK's
SHA-256 matched the build. The phone remains securely locked: interactive
phone-screen verification was not performed and its keyguard was not bypassed.
No model weights were seeded or automatically downloaded on the phone.

The additions were implemented and exercised: runtime domain prefixes,
BLACK & WHITE native/web UI, real device-local LLM hosting, four pinned models
with explicit downloads/persistent caches, and separately configured/generated
passwords. Links contain no credentials. Implementation, runtime verification,
ordinary-main push and final phone installation are complete. Binary publication
remains outside this request.

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
- `domainPrefix` changes only the in-memory listener name, not persisted keys
  or identity files. Preferred public URLs use a ready relay's granted alias;
  canonical URLs remain available and are used when the alias is unavailable.
- Shared native llama.cpp CPU inference is sample-only, pinned by commit/archive
  hash and built for desktop, Android and iOS. No cloud API, external daemon,
  fake response or bundled GGUF is used.
- A single model catalog records exact download bytes, SHA-256, revision,
  source and Apache-2.0 license for SmolLM2 135M/360M and Qwen2.5 0.5B/1.5B.
  Download is explicit; completed verified files survive stop/relaunch/update.
- Native secure password controls allow custom 12–128-code-point passwords,
  secure random generation and explicit copy. Passwords stay in owner memory,
  reset on relaunch and cannot change while serving. Web login uses UTF-8
  Basic authentication; no private link, fragment, cookie or browser storage.
- Android system-bar contrast and iOS scrolled-card safe-area clipping were
  verified on the actual redesigned surfaces.

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
- `samples/local-llm`, `scripts/build-sample-llm.sh`: shared pinned CPU inference,
  standard-UTF-8 JNI/C interfaces, platform archives and packaged license notice.
- `scripts/native-provenance.py`, `verify-desktop-engine.sh`,
  `package-ios-xcframework.sh`, `verify-ios-swift-package.sh`: portable hashing,
  architecture checks and real-workbench Swift-package consumer verification.
- README/platform READMEs, design/API/plan/troubleshooting docs, changelog,
  skill references, workflow and dependency catalog were updated with the cutover.

## Observed verification

| SDK suite | Tests | Failures/errors/skips |
|---|---:|---:|
| `:portal-sdk:desktopTest` | 85 | 0 |
| `:portal-sdk:testAndroidHostTest` | 55 | 0 |
| `:portal-sdk:iosSimulatorArm64Test` | 73 | 0 |

- Original common sources reproduced 10 failures out of 53 targeted cases.
  The closed-owner iOS callback regression also failed before its fix.
- `go test ./...` in `native/bridge` passed, including prefix/identity safety
  regressions. Real native builds/export/header/architecture checks passed for
  macOS universal, Android arm64/x86_64 and all three iOS slices.
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
- Security patch: go-jose v4.1.6 and quic-go v0.59.1 were verified in both
  the rebuilt Android ELF and the actual final APK's embedded arm64 library,
  not just `go.mod`. macOS universal ABI/dependency verification, Android
  two-ABI builds, three iOS archives, KMP staging, XCFramework packaging and
  real Swift-package simulator/device consumer builds passed again.
- With refreshed consumer dependencies, final debug/R8 APKs and desktop
  distribution built; local listener smoke passed twice and all five public
  modes passed again on the explicit relay. UDP exercised a real QUIC backhaul.
- Final debug APK SHA-256:
  `b4f97b766813e225c61f4c1bd33e0bbf5d505f83e2ab545efb8c0ea643f6c7f9`.
- Prefix regression suites passed all 213 SDK executions. All five public
  transports passed again using `https://mono-sdk-1101.kakashit.org:443`,
  with discovery disabled only in that explicit reproducible relay fixture.
- Final password-enabled Android debug/R8 APKs, packaged desktop distribution,
  actual Swift-package simulator/device consumer and production iOS app built.
  Desktop local/public LLM smoke passed with UTF-8 passwords; missing/wrong
  passwords returned 401 and real generation returned 200.
- All four catalog models were explicitly downloaded, size/hash verified and
  generated real UTF-8 responses through the native CPU ABI. Oversized-context
  and permanent-cancellation errors were observed for each model.
- A fresh JVM reused the cached model with an unreachable SOCKS proxy and
  performed no download. Android's explicit Download action produced verified
  cache, preserved through `install -r`; iOS's explicit download and relaunch
  cache reuse were exercised in the actual app.
- Android native UI: configured a password, published a short alias, logged in
  through real Chromium, rejected a wrong password and generated a real answer
  on the emulator CPU. Browser fragment, local/session storage and cookies
  remained empty. Mobile dark mode had no 390px horizontal overflow.
- Actual Android HTTP API rejected blank/invalid-limit/context-overflow inputs
  with 400 and an over-16-KiB body with 413. Two concurrent valid requests
  returned 200/409. Native password editing/generation stayed disabled while
  serving; after owned stop, Generate produced a new 32-character password.
- Actual iOS UI workflow passed: custom secure-field input, Copy action,
  public unauthenticated 401/authenticated generation 200, edit lock, owned
  stop and cache reuse after relaunch. Final production-source run passed in
  50.029 seconds; its screenshot confirmed status-bar overlap was fixed.
- Final APK contained both LLM JNI ABIs and the engine notice, no GGUF weights.
  Both native LLM libraries had 16-KiB LOAD alignment; embedded Go modules
  remained go-jose v4.1.6 / quic-go v0.59.1.
- Owned desktop preview closed normally with exit code 0; owned browser tabs,
  preview server, Android emulator and iOS simulator were stopped after proof.

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
- Temporary UIKit/browser/listener harnesses and the baseline copy were removed
  after verification. Only generated build outputs/result bundles were retained.

## Exact next actions

None for this request. The connected phone already has the final APK with its
data preserved. Interactive phone checks require the user's normal unlock;
do not bypass keyguard or clear app data. Owned verification devices, preview
services and throwaway source/control files were removed; host model caches,
generated builds and runtime evidence were retained.

## Remaining release gates (outside this request)

- No public Maven Central or GitHub binary release was performed.
- The old go-ethereum/LGPL dependency was pruned, but the v2.7.1 bundled
  dependency redistribution/notices review is still explicitly UNRESOLVED.
- Full Linux/Windows/macOS release-matrix, provenance/signing and release
  credentials/tag/commit-subject gates remain required. Linux/Windows runtime
  execution was not verified on this macOS host.
