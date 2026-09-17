# Work Checkpoint

## Active task
README tunnel-runtime repositioning — **complete**.

## State (2026-09-18)
- The SDK implementation already supported loopback HTTP upstreams,
  route-based HTTP forwarding, TCP, UDP, and static directories, but the
  README hero and primary quick start made static serving look like the
  product's main purpose.
- The README now presents Portal as a mobile tunnel runtime for app-local
  HTTP/TCP/UDP services. Static directories remain documented as an optional
  convenience mode.
- The primary quick start now exposes `127.0.0.1:8080` with `targetAddr` and
  waits for `Capability.HTTP_TLS`.
- Added an exposure-mode matrix and an architecture diagram that show local
  HTTP servers, TCP/UDP listeners, static assets, the native runtime, relays,
  and public endpoints.
- Configuration examples now lead with HTTP upstreams and routes, followed by
  TCP/UDP and then static serving.
- Changed files: `README.md`, `CHANGELOG.md`, and this checkpoint.
- Verification:
  - Examples were checked against `PortalConfig`, `PortalHTTPRoute`, and
    `Capability` definitions in `commonMain`.
  - `git diff --check` passed.
  - README code fences were balanced, every local Markdown link resolved, the
    HTTP quick start preceded static configuration, and static-first wording
    from the previous quick start was absent.
  - GitHub rendered the new section order, exposure table, Mermaid diagram,
    and loopback HTTP quick start; the hero/runtime copy was present and no
    rendered images were broken.

## Next action
Run `Gradle CI` once via GitHub Actions `workflow_dispatch` (or on the next
valid `release-*` tag) to seed the README build badge. Product documentation
work is otherwise complete.

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
