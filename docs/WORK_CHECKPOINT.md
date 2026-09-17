# Work Checkpoint

## Active task
Repository-owner metadata migration — **complete**.

## State (2026-09-18)
- `origin` points to `git@github.com:gosuda/portal-multiplatform-sdk.git`;
  GitHub API lookup confirmed `gosuda/portal-multiplatform-sdk`.
- Maven group and published coordinates now use `io.github.gosuda` in
  `portal-sdk`, `portal-native-android`, and `portal-android-lifecycle`.
- Generated POM metadata now uses the `gosuda` repository, SCM URLs, and
  developer identity. Inter-module POM dependencies also resolve under
  `io.github.gosuda`.
- README dependency examples and CI badge, sample-site links, and the Go
  bridge module path now match the remote owner.
- Changed files: `README.md`, `native/bridge/go.mod`,
  `portal-sdk/build.gradle.kts`,
  `portal-native-android/build.gradle.kts`,
  `portal-android-lifecycle/build.gradle.kts`,
  `samples/android/src/main/assets/site-explainer/index.html`,
  `samples/ios/site-explainer/index.html`, `CHANGELOG.md`,
  `docs/TROUBLESHOOTING.md`, and this checkpoint.
- Verification:
  - `go test ./...` in `native/bridge` — passed (package compiled; no tests).
  - `ANDROID_HOME=C:\Users\mingy\AppData\Local\Android\Sdk cmd.exe /c
    gradlew.bat publishToMavenLocal` — passed; 107 actionable tasks.
  - Generated POMs for all three published modules contain
    `io.github.gosuda` and `https://github.com/gosuda/portal-multiplatform-sdk`.
  - Repository-wide stale-owner scan found the former owner only in the
    historical changelog entry describing this migration.

## Next action
Exercise a real tunnel from the on-device app UI (manual — no UI automation
available), then resolve `license_review` and `android_ndk_revision` in
`native/source-lock.json` before any release. See TASKS.md "Blocked / next".

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
