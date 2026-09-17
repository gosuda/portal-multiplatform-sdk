# Work Checkpoint

## Active task
README CI badge status repair — **complete**.

## State (2026-09-18)
- GitHub registered `.github/workflows/gradle.yml` as the active `Gradle CI`
  workflow, but its run history was empty.
- Repository policy intentionally limits Gradle CI to `release-*` tag pushes
  and manual `workflow_dispatch`; ordinary `main` pushes do not run it.
- The README badge additionally filtered runs to `branch=main`. That excluded
  release-tag runs, so the badge could remain `no status` even after a release
  build.
- `README.md` now uses the workflow status endpoint without a branch filter
  and links directly to the Gradle CI workflow.
- Changed files: `README.md`, `CHANGELOG.md`, and this checkpoint.
- Verification:
  - GitHub Actions API reported workflow `360644633` (`Gradle CI`) active.
  - GitHub Actions API reported zero Gradle CI runs on `main`, matching the
    observed `build: no status` badge rather than an endpoint lookup failure.
  - Both the Shields and GitHub-native badge endpoints returned `no status`,
    confirming that changing badge providers would not fix the missing run.
  - The README badge no longer narrows status lookup to `branch=main`.

## Next action
Run `Gradle CI` once via GitHub Actions `workflow_dispatch` (or on the next
valid `release-*` tag) to seed the first build result; the badge will then
display that workflow result. No automatic run was added for ordinary pushes,
preserving the repository CI policy.

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
