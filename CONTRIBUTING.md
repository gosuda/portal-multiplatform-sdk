# Contributing

Thanks for helping improve the Portal Multiplatform SDK. This document covers
setup, the build/test workflow, and the conventions this repository follows.

## Prerequisites

- JDK 17+
- Android SDK (for `portal-native-android` and `samples:android`) — set
  `ANDROID_HOME` or `sdk.dir` in `local.properties`
- A C toolchain (`cc`) for the linuxX64 stub test
- macOS + Xcode only for iOS compilation/linking; iOS targets are disabled on
  Linux and CI compiles them on macOS runners

## Build & test

```bash
./gradlew build                              # everything reachable on this host
./gradlew :portal-sdk:testAndroidHostTest    # JVM-side unit tests
./gradlew :portal-sdk:linuxX64Test           # native tests against the C stub
./gradlew :samples:android:assembleDebug     # sample APK
```

iOS test-link tasks are disabled: the real `libportaltunnel.a` is not shipped
in this repository (see `native/source-lock.json`). `compileTestKotlinIos*`
still type-checks the test sources on macOS.

## Conventions

- **Commits**: `type(scope): subject` — e.g. `fix(sdk): route native events
  through a process-global hub`. One logical change per commit.
- **Changelog**: user-visible features, fixes, and breaking changes go into
  `CHANGELOG.md` under a `## YYYY-MM-DD` heading, past tense, same
  `type(scope):` format.
- **CI**: GitHub Actions runs only on `release-*` tags pointing at
  `release(scope):` commits, or via `workflow_dispatch`. Ordinary commits do
  not trigger it (see `AGENTS.md`).
- **API surface**: `explicitApi()` is on — every public declaration needs an
  explicit `public` modifier and return type. Keep the public surface minimal;
  internals stay `internal`.
- **Wire compatibility**: JSON field names and defaults must stay in lockstep
  with the Android/iOS/Flutter Portal SDKs and `gosuda/portal-tunnel`. Check
  `sdk/expose.go` and `utils/utils.go` upstream before changing validation.
- **Secrets**: never log or commit identity documents, keys, or tokens.
  `PortalIdentity.document` is redacted from `toString` — keep it that way.

## Where things live

| Area | Path |
|---|---|
| Public API | `portal-sdk/src/commonMain/kotlin/org/gosuda/portal/` |
| Engine seam + validation + event hub | `portal-sdk/src/commonMain/.../internal/` |
| Android JNI bridge | `portal-native-android/` |
| iOS Swift facade | `portal-sdk/src/iosMain/` |
| C ABI header + stub | `native/` |
| Design contract | `docs/DESIGN_RULES.md` |
| Work log / resume point | `docs/WORK_CHECKPOINT.md`, `TASKS.md` |

## Reporting issues

Include the platform target, the `PortalFailure.code` you observed, and a
minimal `PortalConfig` that reproduces the problem. For native crashes, attach
the `PortalDiagnostics` snapshot (`client.diagnostics()`).
