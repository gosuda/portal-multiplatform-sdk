# Work Checkpoint

## Active task
KMP Desktop expansion (D0–D5) — **complete through D5**; D6 (publish) is
gated on `license_review` in `native/source-lock.json`.

## State (2026-09-18)
- Added a `desktop` (JVM 17+) target to `portal-sdk` with a JNA engine
  adapter (`PortalNativeLibrary`, `DesktopPortalEngine`, lazy native load)
  matching `native/include/portaltunnel.h` (11 symbols).
- `PortalDesktop.client(applicationId, storageDirectory?, nativeLibraryPath?,
  allowRemoteTargets)` validates `applicationId` eagerly (`INVALID_CONFIG`)
  and resolves the identity path lazily inside `open`/`publish`
  (`PERMISSION_DENIED` on directory failure, matching iOS). Per-OS defaults:
  `$XDG_STATE_HOME/<app>/portal` (Linux), `%LOCALAPPDATA%/<app>/Portal`
  (Windows), `~/Library/Application Support/<app>/Portal` (macOS).
- `PortalClient.defaultIdentityPath` changed `String?` → `(() -> String?)?`
  provider so desktop resolves lazily; Android factory and the builder wrap
  values as lambdas. iOS resolves via `IosIdentityPath` unchanged.
- `portal-native-desktop` JAR packages `META-INF/portal-native/{index.json,
  <target>/lib}` deterministically (reproducible JAR). `GenerateNativeIndex`
  emits sha256 index and fails the build unless all three targets exist
  (`-Pportal.native.requireComplete=true`, release gate).
- `DesktopNativeLibraryLoader` extracts to a content-addressed cache
  `<cache>/portal-sdk/<ver>/<sha256>/<file>` under a file lock with fsync +
  atomic move; hash mismatch → `NATIVE_UNAVAILABLE`, no silent fallback.
- Built `native/desktop/linux-x64/libportaltunnel.so` from `native/bridge`
  via `scripts/build-desktop-engine.sh` (go1.27.1 + zig 0.16.0 cc, glibc 2.17
  target; artifact requires only GLIBC_2.14). Verified by
  `scripts/verify-desktop-engine.sh`.
- `samples/desktop`: Compose Desktop app publishing a loopback HTTP server
  plus a headless `:samples:desktop:smoke` real-relay publish check.
- CI: `desktop-native` matrix builds/verifies linux-x64 (ubuntu+zig),
  windows-x64 (windows+mingw), macos-universal (macos+clang/lipo);
  `desktop-package` downloads all three and packages with
  `requireComplete=true`, then runs `desktopTest`.
- `source-lock.json`: windows-x64 and macos-universal entries marked
  `built_by` the CI release matrix; `desktop_release_gate` documents the
  requireComplete enforcement.

## Verification
- `./gradlew :portal-sdk:desktopTest` — 68 tests green (engine, loader,
  packaged-load, identity).
- `./gradlew :portal-sdk:linuxX64Test` — 39 tests green (commonTest +
  native stub) after the `defaultIdentityPath` signature change.
- `./gradlew :samples:desktop:smoke` — real tunnel through relay discovery;
  `https://desktop-smoke.portal.thumbgo.kr` served the loopback page (200,
  marker round-tripped), clean stop.
- Clean Maven Local consumer (`/tmp/portal-consumer`) resolved
  `io.github.gosuda:portal-sdk:0.1.0` → `portal-sdk-desktop` variant, loaded
  the packaged `.so` offline, `PortalDesktop.client` worked.
- `./gradlew :portal-native-desktop:jar` — JAR contains
  `META-INF/portal-native/index.json` + `linux-x64/libportaltunnel.so`.

## Environment notes (this host)
- Linux x86_64, JDK 17, Gradle 9.6.1 wrapper, Kotlin 2.4.10.
- Go 1.27.1 linux/amd64, zig 0.16.0 at `~/tools/zig/zig`.
- **No Android SDK** — `:portal-sdk:publishToMavenLocal` (full, incl. AAR)
  fails on `extractAndroidMainAnnotations`; the desktop variant publishes
  fine via `publishDesktopPublicationToMavenLocal` +
  `publishKotlinMultiplatformPublicationToMavenLocal`.
- iOS targets disabled on this host (no cinterop toolchain).

## Blockers
- `license_review` still PENDING in `native/source-lock.json` — gates D6
  publish (portal-android-sdk ships no LICENSE; needs upstream grant or a
  rebuild from MIT-licensed portal-tunnel source).
- windows-x64 / macos-universal engines are produced by the CI release
  matrix, not this host.

## Next action
D6 publish gate: resolve `license_review`, then run the release matrix
(`release-*` tag) to build windows/macos engines and package the complete
`portal-native-desktop` runtime.
