# native/ — engine contract and provenance

- `include/portaltunnel.h` — the v1 C ABI every platform binds to
  (Android JNI symbols wrap the same functions; iOS/Linux use it via
  cinterop).
- `bridge/` — clean-room Go bridge over `portal-tunnel` v2.4.3
  `sdk.Exposure`. Produces Android JNI shared libraries, iOS static archives,
  and desktop shared libraries from the same ABI implementation.
- `stub/portaltunnel_stub.c` — C stub implementing the ABI, linked into the
  `linuxX64` test binary to exercise the Kotlin/Native adapter without the
  real engine.
- `source-lock.json` — provenance: artifact SHA-256s, ELF page alignment,
  and the fields that must be resolved before any release.

## Android JNI engine

`scripts/build-android-engine.sh` compiles `native/bridge` plus
`jni_android.c` with Go and Android NDK r29 for `arm64-v8a` and `x86_64`.
Gradle runs it automatically before merging native libraries or publishing
`portal-native-android`. Outputs live under
`portal-native-android/build/generated/jniLibs/`; no prebuilt `.so` is stored
or downloaded.

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r29 \
  ./scripts/build-android-engine.sh
```

The linker uses a 16 KB maximum page size, matching Android's current native
library requirement.

## iOS engine archive

`scripts/build-ios-engine.sh` compiles `native/bridge` per target into
`native/ios/<target>/libportaltunnel.a` (gitignored):

```bash
./scripts/build-ios-engine.sh                 # all three targets
./scripts/build-ios-engine.sh iosArm64        # one target
```

| Target | SDK | GOARCH |
|---|---|---|
| `iosArm64` | iphoneos | arm64 |
| `iosSimulatorArm64` | iphonesimulator | arm64 |
| `iosX64` | iphonesimulator | amd64 |

Minimum iOS version is 16.0. The script diffs the cgo-generated header
against `include/portaltunnel.h` — the extern signatures must stay
identical or cinterop and the archive drift.

`portal-sdk` links the archive into the `PortalSDK` framework and the
`ios*Test` binaries via per-target `linkerOpts` when
`native/ios/<target>/` exists; without the archives the iOS link/test
tasks stay disabled and `compileTestKotlinIos*` still type-checks.
Consumers additionally need `-framework Security` (Go runtime TLS).

## Remaining gaps

- ABI v2 (create/attach/start split, observer quiescence, native revisions)
  is not implemented — the SDK wraps v1 and documents the gaps in
  `docs/DESIGN_RULES.md`.
