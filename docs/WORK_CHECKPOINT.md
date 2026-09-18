# Work Checkpoint

## Active task
KMP Desktop expansion (D0–D5) — **complete through D5**; D6 (publish) is
gated on `license_review` in `native/source-lock.json`. The Compose Desktop
sample was rebuilt to mirror the Android app (2026-09-18).

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
- CI: `desktop-native` matrix builds/verifies linux-x64 (ubuntu+zig),
  windows-x64 (windows+mingw), macos-universal (macos+clang/lipo);
  `desktop-package` downloads all three and packages with
  `requireComplete=true`, then runs `desktopTest`.
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
