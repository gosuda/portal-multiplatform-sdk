# Work Checkpoint

## Active task
SDK defect/repair sweep implemented and locally verified on Linux x86_64.
Release publication remains deliberately blocked by unresolved bundled
dependency redistribution review plus missing release credentials/hosts.

## State (2026-09-21)
- Common/native fixes: explicit identities bypass default-path resolution;
  failed cancellation rollback retains STOPPING ownership; close is
  retryable/non-cancellable; outer readiness timeouts stay cancellation;
  event registration/drain races and drop counters fixed; config strings
  redact identity/token secrets; C API out-pointers are initialized; JNI
  uses standard UTF-8; Go exposure teardown precedes terminal events.
- Lifecycle/sample fixes: failed foreground-service stops remain retryable;
  Android payload cleanup moved to process scope; desktop jlink includes
  `java.prefs`, `java.net.http`, `jdk.httpserver`, `jdk.crypto.ec`; iOS TCP
  toggle honored and personal signing team removed; staged gosuda Maven
  dependency resolution is exclusive.
- Release fixes: reusable verification matrix precedes publication; manual
  dispatch is verification-only unless `publish=true`; matching
  `release-v<version>` tag + `release(scope):` subject + credentials are
  enforced; Dokka upload path fixed; complete local Swift package ZIP
  replaces the unusable loose manifest; Android ABI/16KiB checks added;
  desktop publication cannot bypass the complete runtime matrix; per-binary
  provenance sidecars are collected into `dist/native-source-lock.json`.
- Build fixes: Apple engine producer is skipped on non-macOS metadata
  staging; Windows desktop test stub uses GCC; SDK/native/lifecycle versions
  resolve from `libs.versions.portal.sdk`; Dokka publication generation is
  marked incompatible with configuration cache; Android SDK injection is
  lazy.
- `native/source-lock.json` now contains an intentional UNRESOLVED
  `bundled_dependency_license_review`: `go list -deps` links
  `go-ethereum v1.17.1` LGPL-3.0-or-later packages. Publication must remain
  blocked until notices/source/static-link obligations are resolved.

## Verification (2026-09-21 Linux)
- Original common sources reproduced 4 targeted failures; corrected SDK:
  `desktopTest` 80/80, `testAndroidHostTest` 49/49, `linuxX64Test` 51/51.
- `go test ./...` in `native/bridge` passed (no test files).
- Rebuilt and verified linux-x64 `.so` and windows-x64 `.dll`; all 11 ABI
  exports, generated headers, dependencies, glibc <=2.17, SHA/size sidecars.
- Host JNI smoke: original adapter corrupted Korean+supplementary Unicode;
  corrected adapter round-tripped it, received STOPPED callback, and
  `PortalGetStatus` rejected the cleaned-up tunnel.
- Foreground-service smoke: original service left failed stop STOPPING;
  corrected service retried to STOPPED.
- `actionlint` passed both workflows; original workflow reproduced missing
  upload `path`. All shell/Python scripts parse.
- Release gate: manual verification allowed; branch/tag/subject/version and
  unresolved redistribution review rejected; resolved fixture accepted.
- Android staging produced verified AAR + debug/release APKs with both
  shipped ABIs and 16KiB alignment. Desktop staging produced the sample app;
  a hard-linked copy of the real packaged launcher passed on-device HTTP
  smoke and real-relay HTTP 200 marker round-trip with invalid JAVA_HOME.
- Incomplete desktop publication was rejected because macos-universal is
  absent on this host.

## Environment notes (this host)
- Linux x86_64, JDK 17, Go `/home/mgkim/tools/go/bin/go` 1.27.1, Zig
  `/home/mgkim/tools/zig/zig` 0.16.0, Android SDK `/tmp/sajudang-android-sdk`,
  NDK `/tmp/android-ndk-r29` 29.0.14206865, MinGW GCC installed.
- No macOS/Xcode/XcodeGen, Android device/emulator, or GUI display. iOS
  compile/link and UI checks remain release-matrix/manual work.

## Blockers
- Bundled LGPL dependency redistribution review blocks public binary
  publication (see `native/source-lock.json`).
- macos-universal engine and all Apple sample/Swift consumer verification
  require macOS/Xcode.
- Maven Central/GitHub release require credentials/secrets and a matching
  `release-v0.1.0` tag on a `release(scope):` commit.
- Android runtime recreation/notification proof requires a device/emulator.

## Next action
Commit and push this repair set. Then resolve the bundled dependency
redistribution review or replace the LGPL dependency before creating a
release commit/tag. After legal/provenance resolution, run the release
workflow; macOS verification must prove the iOS/Swift package path.
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
- `samples/desktop`: Compose Desktop app rebuilt to mirror the Android
  sample — Publish / Settings / Activity destinations, the same four
  publishable contents (Snake game, explainer, on-device model, Minecraft
  ping), live metadata/relay/identity/diagnostics panels, and a system-tray
  keep-alive. The on-device model is backed by a local Ollama daemon
  (`OllamaClient`/`OllamaModels`, pull-managed tags) with a Markov fallback
  instead of LiteRT-LM. Headless `:samples:desktop:smoke` (real-relay
  publish) and `:samples:desktop:ondeviceSmoke` (endpoint check) tasks.
- 2026-09-18 follow-up: the desktop UI was redesigned away from the mobile
  layout — a permanent left sidebar (destination nav + phase badge +
  publish/stop action) with a LazyColumn content area, landscape 1080×720
  window, denser controls, and desktop copy (system-tray keep-alive,
  clipboard/browser actions via AWT).
- Minecraft content is now a real playable server, not a ping mock:
  `MinecraftProtocol` (767 codec: VarInt, strings, positions, UUIDs, NBT),
  `MinecraftRegistries` (vanilla 1.21.1 registry keys, values omitted →
  client defaults), and `MinecraftServer` — handshake → status → login →
  configuration (11 registries + feature_flags) → play (login, 9×9 flat
  creative chunks, spawn teleport, keep-alives, movement broadcast,
  creative block edits, chat, player entities). Same port answers HTTP.
  `:samples:desktop:minecraftSmoke` drives a fake client through the full
  join path.
- Ollama is now set up in-app: `OllamaSetup` downloads the official
  archive per-OS into the app dir, extracts `ollama`, and runs
  `ollama serve` as a managed child (isolated `OLLAMA_MODELS`, log tail).
  `ensureRunning` adopts a system daemon or starts the managed one —
  never auto-downloads; `install` is explicit from Settings. The engine
  re-probes when the daemon comes up.
- 2026-09-18 follow-up 2: the desktop app icon is the Android launcher
  asset (`icon.png` in resources for the window/tray, generated
  `icon.ico`/`icon.icns`/`icon.png` under `packaging/` for native
  distributions). Ollama download URLs moved to GitHub release assets —
  Linux is `.tar.zst` (decompressed via `zstd-jni`, no `zstd` binary
  needed), macOS is `Ollama-darwin.zip` (capital O), Windows extracts the
  full install tree so `ollama.exe` finds its bundled libs.
- 2026-09-18 follow-up 3: `GenerateNativeIndex.nativeDesktopDir` is now
  `@Optional` — a fresh clone without `native/desktop/` (gitignored,
  engine binaries are CI-built) no longer fails Gradle input validation;
  the task emits a partial-matrix warning instead. The Windows engine
  (`native/desktop/windows-x64/portaltunnel.dll`) was copied to the
  Windows checkout at `D:\my\portal-multiplatform-sdk` so
  `packageMsi`/`createDistributable` can package a working runtime.
- 2026-09-18 follow-up 4: Android and desktop samples now depend on the
  released Maven coordinates (`portal-sdk` and
  `portal-android-lifecycle`) rather than same-build Gradle projects.
  `publishDesktopSdkToSampleRepository` /
  `publishAndroidSdkToSampleRepository` stage the complete transitive graph
  under `build/sample-maven`; `-Pportal.samples.repository=…` makes samples
  consume that isolated repository before Central. Release CI now builds the
  native desktop matrix before publication, exercises both samples against
  staged artifacts, and refuses Maven Central publication with an incomplete
  desktop runtime.
- 2026-09-18 follow-up 5: fixed false failures in
  `verify-desktop-engine.sh`: Windows exports are read from GNU `objdump`'s
  `[Ordinal/Name Pointer] Table`; universal macOS `otool -L` headings and
  `LC_ID_DYLIB` rows are excluded; versioned system `libresolv` is allowed.
  Embedded parser fixtures now run on every desktop-native CI target before
  the engine build.
- 2026-09-18 follow-up 6: the Windows CI runner exposed another GNU
  `objdump` layout not matching the indexed-row fixture. Windows verification
  now scans complete PE metadata for exact `Portal*` tokens and tolerates
  extra columns, annotations, and CRLF without weakening the required-symbol
  check.
- 2026-09-18 follow-up 7: release workflow is now named
  `Publish Multiplatform SDK`. Its first job prints exact unresolved
  provenance/licensing fields before any platform build. The desktop runtime
  matrix remains a clearly named platform-specific prerequisite; the final
  job verifies and publishes Android, KMP, and desktop Maven artifacts.
- 2026-09-18 follow-up 8: deleted externally sourced Android `.so` files.
  `native/bridge/jni_android.c` implements the exact `NativeBridge` JNI ABI,
  and `build-android-engine.sh` builds arm64-v8a/x86_64 outputs with Go 1.27.1
  plus NDK r29 into `portal-native-android/build/generated/jniLibs`. Gradle
  runs the build before AAR merge/publication; release CI installs both pinned
  toolchains. No Android native binary is downloaded or committed.
- 2026-09-18 follow-up 9: `GenerateNativeIndex` now recreates its output
  directory after cleanup before writing `index.json`; native hashes stream
  through `DigestInputStream` instead of allocating each full binary.
- 2026-09-18 follow-up 10: fixed matrix artifact reconstruction by
  downloading each desktop artifact into its exact target directory.
  iOS cinterop now generates target-specific definitions that embed
  `libportaltunnel.a` and Security linkage in every published klib; Gradle
  builds all archives automatically. The release workflow stages all three
  iOS Maven variants, asserts their klibs contain the archive, builds a
  self-contained XCFramework/Swift package, and uploads that distribution.
- 2026-09-18 follow-up 11: expanded `TASKS.md` with the remaining SDK
  usability work: canonical released installs, clean external consumers,
  real-relay first-success checks, compatibility documentation, direct ready
  URL access, managed lifetimes, typed failures, safe diagnostics, Swift
  export review, troubleshooting/API references, and measurable onboarding
  acceptance gates. The immediate next action remains the macOS release
  verification recorded under P4.
- 2026-09-18 follow-up 12: audited sample dependency sources. Android and
  Desktop declare Maven coordinates, not `project()` dependencies, but current
  CI proves them with artifacts staged from the same checkout before
  publication. The iOS sample still links `../../dist/PortalSDK.xcframework`.
  `TASKS.md` and the usability plan now require a post-publication cutover:
  exact Maven Central versions for Android/Desktop, the versioned remote
  Swift package for iOS, and an independent clean workflow that rejects local
  project, staging-repository, Maven Local, and `dist/` SDK sources.
- CI: `build-desktop-runtime` matrix builds/verifies linux-x64 (ubuntu+zig),
  windows-x64 (windows+mingw), and macos-universal (macos+clang/lipo). The
  final publish job reconstructs target directories, stages Android/desktop/
  iOS Maven graphs, packages the complete runtime matrix, and runs consumer
  checks before publication.
- `source-lock.json`: windows-x64 and macos-universal entries marked
  `built_by` the CI release matrix; `desktop_release_gate` documents the
  requireComplete enforcement.

## Verification
- `./gradlew :samples:desktop:smoke` — real tunnel through relay discovery;
  `https://desktop-smoke.portal.thumbgo.kr` served the loopback page (200,
  marker round-tripped), clean stop.
- `./gradlew :samples:desktop:minecraftSmoke` — fake 1.21.1 client through
  the full join path: status ping, login, configuration (11 registries),
  play (login + 81 chunks + spawn teleport). All asserted green.
- `./gradlew :samples:desktop:build` — compiles clean after the sidebar
  redesign, Minecraft server, and Ollama setup.
- `./gradlew :portal-sdk:linuxX64Test` — 39 tests green (commonTest +
  native stub) after the `defaultIdentityPath` signature change.

- Clean Maven Local consumer (`/tmp/portal-consumer`) resolved
  `io.github.gosuda:portal-sdk:0.1.0` → `portal-sdk-desktop` variant, loaded
  the packaged `.so` offline, `PortalDesktop.client` worked.
- `./gradlew :portal-native-desktop:jar` — JAR contains
  `META-INF/portal-native/index.json` + `linux-x64/libportaltunnel.so`.
- `./gradlew publishDesktopSdkToSampleRepository` followed by
  `./gradlew :samples:desktop:clean :samples:desktop:build
  -Pportal.samples.repository=file://…/build/sample-maven --offline` —
  resolved `portal-sdk` → `portal-sdk-desktop` →
  `portal-native-desktop`; no SDK/native project task entered the sample
  build graph.
- `./gradlew :samples:desktop:smoke
  -Pportal.samples.repository=file://…/build/sample-maven --offline` —
  loaded the staged native runtime, opened a real relay tunnel, fetched
  `https://desktop-smoke.portal.thumbgo.kr` with HTTP 200, round-tripped the
  marker, and stopped cleanly (`SMOKE OK`).
- `./gradlew :portal-native-desktop:publishToMavenCentral --offline` —
  publication was rejected because the macOS runtime was absent, proving the
  Central task forces the complete native matrix (credentials were also
  intentionally absent).
- `./scripts/verify-desktop-engine.sh --self-test` — representative GNU
  `objdump` and universal `otool -L` fixtures passed.
- `./scripts/verify-desktop-engine.sh
  native/desktop/windows-x64/portaltunnel.dll` — all 11 exports, header, and
  dependencies passed with the corrected Windows parser.
- `./scripts/verify-desktop-engine.sh
  native/desktop/linux-x64/libportaltunnel.so` — existing Linux verification
  remained green.

- `PATH=/tmp/go/bin:$PATH ANDROID_NDK_HOME=/tmp/android-ndk-r29
  ./scripts/build-android-engine.sh` — both Android ABIs built from source.
  Each generated library exported all 10 required JNI entry points and used
  `0x4000` alignment for every ELF LOAD segment.
- `PATH=/tmp/go/bin:$PATH ANDROID_NDK_HOME=/tmp/android-ndk-r29
  ./gradlew :portal-native-android:buildAndroidEngine` — Gradle producer task
  rebuilt both ABI outputs successfully.
- Clean-output `:portal-native-desktop:generateNativeIndex` and
  `publishDesktopSdkToSampleRepository` both passed after deleting their
  generated/staging directories.
- `:portal-sdk:cinteropPortaltunnelIosArm64 --dry-run` — task graph includes
  `buildIosEngine` and `prepareIosInteropDefs` before cinterop; execution is
  correctly disabled on this Linux host because Apple cinterop requires macOS.
- `:portal-sdk:prepareIosInteropDefs` — generated configuration-cache-safe
  per-target definitions with `staticLibraries`, `libraryPaths`, and Security
  `linkerOpts`.

## Environment notes (this host)
- macOS arm64 (M1 Pro), Xcode 27.0, Android SDK at `~/Library/Android/sdk`,
  Go 1.27.1 darwin/arm64, JDK 17 at `~/tools/jdk-17.0.20.1+1` (or the
  Android Studio JBR 21 — both build the project).
- iOS targets build and test here: `iosSimulatorArm64Test` 47/47 green,
  `package-ios-xcframework.sh` produces a verified XCFramework.
- `linuxX64Test` cannot run on macOS (Kotlin/Native linuxX64 target is
  Linux-only); `desktopTest` (JVM) covers the same suite: 65/65 green.
- Prior session ran on Linux x86_64 (JDK 17, zig 0.16.0); its notes are in
  git history.

## Session 2026-09-19 (macOS) — usability roadmap implemented
- `PortalTunnel.publicUrl` + `PortalIosSession.primaryPublicUrl`
  (ready-result convenience surface).
- `PortalClient.use {}` managed-lifetime for JVM/desktop/common;
  `PortalClientHolder.publish` on Android.
- Typed publish failures: `PortalFailure.operation` (`publish` /
  `publish_cleanup` / `identity_path`), `terminalPhase`, `readinessFailure`.
- `PortalDiagnostics.engineVersion` + per-session `activeRelay`/`lastFailure`.
- `PortalIosConfigFactory.custom` — full-config Swift escape hatch without
  `KotlinBoolean` or the all-fields initializer.
- README: publish-first quick starts, Recipes section, Compatibility table,
  API-reference links; `docs/SWIFT_API.md` reviewed against the real
  `PortalSDK.h`; `docs/TROUBLESHOOTING.md` decision tree.
- Dokka plugin added (`:portal-sdk:dokkaGenerate`); publish workflow now
  attaches `PortalSDK.xcframework.zip`, `Package.swift`, and
  `dokka-html.zip` to the tag's GitHub release after Maven Central succeeds.
- Sample apps cut to `publish` + factory configs; `AdvancedContract` fixtures
  keep raw `PortalConfig` + `open` compiled.
- Verified: all 3 staged iOS cinterop klibs embed `libportaltunnel.a`;
  `verify-ios-maven-consumer.sh` links `iosArm64` clean;
  `desktopTest` 65/65, `iosSimulatorArm64Test` 47/47 green.

## Blockers
- windows-x64 / macos-universal engines are produced by the CI release
  matrix, not this host.
- Maven Central publish + GitHub release require the release workflow run
  (credentials/secrets not on this host).
- On-device tunnel exercise from the app UI remains a manual step.

## Next action
Run a new `Publish Multiplatform SDK` workflow on the release commit. Confirm
the Android source build, desktop runtime matrix, signing, Maven Central
upload, and the new release-asset attach (XCFramework zip, Package.swift,
dokka-html.zip) all complete. After the artifacts are externally available,
cut all samples over to those exact released artifacts and run the new
independent post-release consumer checks.
