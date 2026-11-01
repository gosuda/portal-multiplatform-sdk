# native/ — engine contract and provenance

- `include/portaltunnel.h` — the v1 C ABI every platform binds to
  (Android JNI symbols wrap the same functions; iOS/Linux use it via
  cinterop).
- `bridge/` — clean-room Go bridge over `portal-tunnel` v2.7.1 (protocol 10)
  `sdk.Exposure`. Produces Android JNI shared libraries, iOS static archives,
  and desktop shared libraries from the same ABI implementation.
- `stub/portaltunnel_stub.c` — C stub implementing the ABI, linked into the
  `linuxX64` test binary to exercise the Kotlin/Native adapter without the
  real engine.
- `source-lock.json` — expected provenance and publication prerequisites.
  Build-generated `.provenance.json` sidecars contain measured hashes;
  release collection emits the complete `dist/native-source-lock.json`.

## Dependency updates

`bridge/go.mod` pins go-jose v4.1.6 and quic-go v0.59.1 above the versions
selected by portal-tunnel v2.7.1. Keep those patched indirect selections
when updating the engine. Rebuild the native binaries and consuming apps;
a module-file change alone does not update an installed app.

## Android JNI engine

`scripts/build-android-engine.sh` compiles `native/bridge` plus
`jni_android.c` with Go 1.27.3 and Android NDK `29.0.14206865` (r29) for
`arm64-v8a` and `x86_64`. Native build/artifact verification uses Python 3.9+;
the release gate separately requires Python 3.11+ (`tomllib`).
Gradle runs it automatically before merging native libraries or publishing
`portal-native-android`. Outputs live under
`portal-native-android/build/generated/jniLibs/`; no prebuilt `.so` is stored
or downloaded.

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r29 \
  ./scripts/build-android-engine.sh
```

The linker uses a 16 KB maximum page size. The build verifies both
architectures, C/JNI exports, the generated header, and every ELF LOAD
segment; CI checks the resulting AAR and debug/release APK contents too.

## iOS engine archive

`portal-sdk:buildIosEngine` invokes `scripts/build-ios-engine.sh` automatically
when an Apple cinterop is compiled or published on macOS. The producer is
skipped on non-Apple hosts so Android/Desktop Maven metadata can be staged
without `xcrun`. It compiles `native/bridge`
per target into gitignored `native/ios/<target>/libportaltunnel.a` archives.
Direct script invocation remains available for diagnostics:

```bash
./scripts/build-ios-engine.sh iosArm64
```

| Target | SDK | GOARCH |
|---|---|---|
| `iosArm64` | iphoneos | arm64 |
| `iosSimulatorArm64` | iphonesimulator | arm64 |
| `iosX64` | iphonesimulator | amd64 |

Minimum iOS version is 16.0. The script compares all generated `Portal*`
declarations with `include/portaltunnel.h` and fails on ABI drift.

Gradle generates one target-specific cinterop definition with
`staticLibraries = libportaltunnel.a`, the matching `libraryPaths`, and
`linkerOpts = -framework Security`. Kotlin/Native embeds the archive into each
published klib. KMP consumers resolve the normal Maven coordinate; Swift
consumers use the generated self-contained XCFramework/Swift package. Neither
path requires a consumer-managed archive or linker search path.

## Remaining gaps

- Public binary publication is blocked on the bundled-dependency
  notices/source/static-link redistribution review recorded in
  `source-lock.json`. The engine migration pruned the old go-ethereum/LGPL
  dependency, but that does not complete the remaining native-graph review.
  The core repository's MIT license is not a substitute for reviewing
  bundled dependency obligations.

- ABI v2 (create/attach/start split, observer quiescence, native revisions)
  is not implemented — the SDK wraps v1 and documents the gaps in
  `docs/DESIGN_RULES.md`.
