# Work Checkpoint

## Active task
Multiplatform hardening + docs pass — **complete for this session's scope**.

## State (2026-09-17)
- Branch `main`, no prior commits; this session produces the first commit.
- All modules compile and test green locally:
  - `./gradlew :portal-sdk:testAndroidHostTest` — 21 tests pass (incl.
    multi-client event routing regression)
  - `./gradlew :portal-sdk:linuxX64Test` — native stub tests pass
  - `./gradlew :samples:android:assembleDebug` — APK with both `.so`s
  - `explicitApi()` enabled; public surface fully declared
- Session 2 changes: PortalEventHub routing, upstream-aligned relay rules,
  isMitm/isActive helpers, sample StateFlow rewrite, logo + CONTRIBUTING.
- Not verified locally (needs macOS): `compileKotlinIos*`, XCFramework.

## Next action
If continuing: recover the Go mobile bridge (native/source-lock.json
`UNRESOLVED` fields), then build the iOS archive and re-enable ios test
links. See TASKS.md "Blocked / next".

## Environment notes
- Android SDK at `~/Android/Sdk` (platform 36, build-tools 36.0.0) via
  `local.properties` (gitignored).
- JDK 17, Gradle 9.6.1 wrapper, Kotlin 2.4.10, AGP 9.1.0.
- AGP 9: no `org.jetbrains.kotlin.android` plugin; use `android {}` not
  `androidLibrary {}` in the KMP block. See docs/TROUBLESHOOTING.md.

## Blockers
- iOS engine archive and Go bridge source unavailable (documented in
  native/source-lock.json); iOS consumers must supply `libportaltunnel`.
