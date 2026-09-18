# Work Checkpoint

## Active task
SDK usability roadmap — **planning complete; implementation not started**.

## State (2026-09-18)
- The current low-level contract is sound: `PortalClient` owns sessions,
  `PortalConfig` preserves the v1 wire DTO, and `PortalTunnel.state` remains
  authoritative.
- The easiest documented Android path is unsafe/inconsistent: the README starts
  with context-free `PortalClient()`, while the Android overload is needed to
  default identity storage to a writable app directory.
- `PortalClientHolder` and `PortalTunnelService` also construct context-free
  clients, so their advertised easy lifecycle path does not supply that safe
  Android identity default.
- Swift callers currently fill the generated all-fields `PortalConfig`
  initializer, bridge nullable booleans through `KotlinBoolean`, resolve an
  identity path themselves, and manually link `libportaltunnel.a`.
- The planned approach is additive: retain `PortalConfig`, `open`, and all
  lifecycle invariants; layer intent-oriented config factories and a
  rollback-safe `publish` operation above them.
- Distribution is part of usability, not a documentation follow-up: Android
  needs a released Maven artifact, while iOS needs one package/artifact that
  includes the native engine.
- The phased plan and exit criteria are recorded in `TASKS.md` under
  `Planned — SDK usability roadmap`.
- Changed files: `TASKS.md` and this checkpoint.
- Planning verification:
  - Compared the README quick starts with the exported common, Android, iOS,
    and lifecycle APIs.
  - Checked both sample apps' real configuration/start flows.
  - Kept the proposed API layers compatible with `docs/DESIGN_RULES.md`.

## Next action
Implement P0 and P1 together: add consumer compile fixtures, then make Android
and iOS identity defaults platform-safe before introducing convenience
factories. Treat Android context propagation as the first RED/GREEN behavior
change.

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
