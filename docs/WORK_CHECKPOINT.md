# Work Checkpoint

## Active task
SDK usability implementation plan — **complete; implementation not started**.

## State (2026-09-18)
- The current low-level contract remains the foundation: `PortalClient` owns
  sessions, `PortalConfig` remains the v1 wire DTO, `open()` means accepted
  rather than ready, and `PortalTunnel.state` stays authoritative.
- The implementation contract is now recorded in
  `docs/SDK_USABILITY_IMPLEMENTATION_PLAN.md`.
- The planned common API adds intent factories on `PortalConfig` and
  `PortalClient.publish(config, timeoutMillis)`. `publish` composes
  `open`/`awaitActive`/`stop`; it does not introduce a second session type.
- Android makes a clean cutover to `PortalClientHolder.init(context)` and
  context-backed service clients. No context-free compatibility overload is
  planned because it preserves the unsafe identity default.
- iOS resolves an Application Support identity path inside `open/publish`,
  reports filesystem failures through the existing completion contract, and
  exposes a small config factory facade for Swift.
- Publication cleanup behavior is specified for timeout, terminal failure,
  cancellation, and stop failure. Cancellation continues to propagate as
  `CancellationException`.
- Release work includes Maven consumer verification, a self-contained Apple
  artifact, provenance gate resolution, and correction of the publish workflow
  to the repository's `release-*` tag plus `release(scope):` subject rule.
- The plan names exact files, API signatures, phase commits, RED/GREEN checks,
  runtime smokes, verification commands, risks, and definition of done.
- `TASKS.md` links the detailed plan and retains the phase-level checklist.
- Changed files in this planning session:
  `docs/SDK_USABILITY_IMPLEMENTATION_PLAN.md`, `TASKS.md`, and this checkpoint.

## Next action
Start P0 with the Android and Swift quick-start compile contracts. Then execute
P1 as the first behavioral RED/GREEN change: propagate Android application
context and add the iOS Application Support identity resolver. Do not begin P2
factories until the platform-default runtime smokes pass.

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
