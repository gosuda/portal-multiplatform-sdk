# native/ — engine contract and provenance

- `include/portaltunnel.h` — the v1 C ABI every platform binds to
  (Android JNI symbols wrap the same functions; iOS/Linux use it via
  cinterop).
- `stub/portaltunnel_stub.c` — C stub implementing the ABI, linked into the
  `linuxX64` test binary to exercise the Kotlin/Native adapter without the
  real engine.
- `source-lock.json` — provenance: artifact SHA-256s, ELF page alignment,
  and the fields that must be resolved before any release.

## Missing pieces (release gate G0)

The Go mobile bridge that produced `libportaltunnel` is not publicly
available (`portal-tunnel/mobile` no longer exists upstream). Until it is
recovered or rebuilt:

- iOS has no `libportaltunnel.a`; consumers must supply it.
- ABI v2 (create/attach/start split, observer quiescence, native revisions)
  cannot be implemented — the SDK wraps v1 and documents the gaps in
  `docs/DESIGN_RULES.md`.
- Android `.so`s are reused as-is from `gosuda/portal-android-sdk`
  (checksums in `source-lock.json`).

## Rebuilding (once the bridge exists)

- Android: `GOOS=android go build -buildmode=c-shared` per ABI with the NDK
  toolchain; verify `readelf -lW` LOAD alignment is 0x4000 (16KB).
- iOS: `GOOS=ios go build -buildmode=c-archive` per target triple, then
  embed via `staticLibraries`/`libraryPaths` in
  `portal-sdk/src/nativeInterop/cinterop/portaltunnel.def`.
