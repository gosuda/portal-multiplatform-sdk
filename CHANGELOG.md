# Changelog

## 2026-09-18

- fix(ci): removed the `main` branch filter from the README build badge so
  release-tag and manually dispatched Gradle CI runs could supply its status

- fix(publishing): migrated Maven coordinates, POM metadata, Go module path,
  documentation, CI badge, and sample links from the former `kimmandoo`
  namespace to the repository's `gosuda` owner

## 2026-09-17

- feat(ios): clean-room Go mobile bridge (`native/bridge`) over
  portal-tunnel v2.4.3 `sdk.Exposure` — replaces the removed upstream
  `portal-tunnel/mobile`; `scripts/build-ios-engine.sh` builds
  `libportaltunnel.a` per target (iosArm64 / iosSimulatorArm64 / iosX64)
- feat(sdk): iOS engine linked end-to-end — per-target `linkerOpts`
  (`-lportaltunnel` + `-framework Security`), archive declared as link
  input, `ios*Test` tasks re-enabled when archives exist
- fix(bridge): `PortalStop` raced the serve-exit goroutine on the
  capacity-1 `serveErr` channel and timed out despite a clean shutdown —
  waiters now use a broadcast `done` channel
- test(ios): `IosEngineSmokeTest` exercises the real bridge through
  cinterop (identity round-trip, start/stop error paths);
  `iosSimulatorArm64Test` 29/29 green; real-relay tunnel verified on the
  simulator (public URLs issued, clean stop)
- feat(sample): iOS app builds and installs — xcodegen `project.yml`,
  `PortalSampleApp` entry point, `site/`/`site-explainer/` bundle
  resources; verified on iPhone 15 Pro
- fix(sample): iOS Swift sources compile — `PortalIosClient` needs
  explicit args (K/N exports no default-arg init), missing `id`/`listener`
  fields restored, `htonl` → `INADDR_LOOPBACK.bigEndian`
- fix(sample): public name now controls the address — the saved identity's
  name was silently kept, so editing the name never changed the URL; when
  the configured name differs from the saved identity's, a fresh identity
  is generated and written over the file (Android + iOS). Verified on
  device: `snake-game` → `portaltest` → `https://portaltest.*`
- fix(sample): model re-download offered after every app kill — the file
  persisted but `ModelDownload.refresh()` was never called, so state reset
  to NotDownloaded; now re-scanned on screen entry. Partial downloads also
  resume via HTTP Range instead of restarting
- feat(sample): model picker — 5 non-gated `.litertlm` models (SmolLM2
  135M/1.7B, LFM2.5-VL 450M, OLMo 2 1B, Qwen2.5 1.5B) in Settings →
  '05 / On-device model'; selection persisted, engine restarts on switch,
  per-model "on device" badge. Verified on device (olmo2-1b persisted)
- fix(sample): truncated model responses — `max_tokens` defaulted to 128
  and the playground never passed it; default raised to 256, playground
  requests 512, KV cache `maxNumTokens` raised to 2048
- feat(sample): settings persist across force-stops — all publish config
  (name, description, tags, relays, discovery, protocols, visibility,
  content, keep-alive) now writes to SharedPreferences via
  `rememberPersisted*`; `rememberSaveable` only survived rotation
- docs(sample): `samples/android/README.md` — on-device model API
  (`/v1/generate`, `/v1/model`, `/v1/health`), parameters, error codes,
  curl examples
- feat(sample): app icon + splash — gopher-explorer logo on both platforms;
  Android adaptive icon (foreground + `#080F1D` background), legacy
  mipmaps, `windowSplashScreen*` (API 31+) + `windowBackground` splash;
  iOS `AppIcon` asset catalog + `LaunchScreen.storyboard`





- feat(sample): GPU inference toggle in Settings → '05 / On-device model'
  — persisted via SharedPreferences, default off on emulators (GPU path
  compiles ~90 s then fails), on elsewhere; toggling while the engine is
  running restarts it on the new backend

- fix(sample): model download — switched to non-gated
  litert-community/SmolLM2-135M-Instruct (~140 MB); Gemma repos are
  gated (HF auth required) and returned 401
- feat(sample): bounded request concurrency — Semaphore(8) bounds
  queue+execution, excess gets 429 + Retry-After; per-request 120 s
  deadline → 504; inference errors → 500 (was 200 with error text);
  Markov fallback runs in parallel (stateless); /v1/health exposes
  inflight/rejected/max_inflight

- fix(sample): LiteRT-LM backend selection — init success is not enough;
  a 1-token smoke test proves inference works, else fall back (emulator
  GPU compiles but fails with 'Can not find OpenCL library')
- fix(sample): skip GPU backend on emulators — WebGPU→Vulkan→host path
  wastes ~90 s compiling then fails; CPU/XNNPACK is the only working path
- feat(sample): EngineStatus StateFlow — Loading/Ready/LowMemory/Failed
  surfaced on the picker card so the 90 s init is no longer invisible
- feat(sample): default model switched to Gemma 3 270M q8 (~300 MB) —
  smaller download, faster CPU inference than Qwen3-0.6B

- fix(sdk): SessionRegistry race — register/unregister now use
  MutableStateFlow.update for atomic read-modify-write
- fix(sdk): PortalClient.open — engine.start moved to non-cancellable
  cleanupScope so cancellation after native start cannot orphan the handle
- fix(sdk): PortalTunnel.refresh — records PortalFailure on snapshot when
  getStatus throws, so UI sees the error
- fix(sdk): PortalEventHub.drainOrphans — counts dropped orphans in
  droppedOrphans metric instead of silently discarding
- fix(sdk): ConfigValidation — rejects unbracketed IPv6 relay hosts
- feat(sdk): PortalEvent.RelayAdded / RelayRemoved — relay set changes
  now surface as typed events and update snapshot.relays
- feat(sample): LiteRT-LM init failures logged per backend; engine name
  shown in content detail

- feat(sample): four publishable contents as packages — snake (static),
  explainer (static), ondevice (LiteRT-LM LLM + Markov fallback), minecraft
  (hybrid TCP/HTTP server); each owns its payload + PortalConfig fields
- feat(sample): LiteRT-LM integration per Google's official tutorial —
  cascading GPU→CPU fallback, Engine/Conversation/sendMessageAsync flow,
  model download on demand (~500 MB), OOM/ANR guards (memory check,
  maxNumTokens cap, thread cap, 120s inference timeout, largeHeap)
- feat(sample): ModelDownload manager — on-demand fetch, progress StateFlow,
  disk-space guard, partial-file cleanup
- feat(sample): iOS Contents.swift — same content protocol, POSIX socket
  servers, Markov fallback (LiteRT-LM is Android-only)

- feat(sample): site picker on Publish screen — choose between Snake game
  and "How Portal works" explainer page; both bundled as assets
- feat(sample): Portal explainer page — static site explaining the device→
  relay→visitor flow, SDK architecture, and use cases

- feat(sample): redesigned Android + iOS UIs — three destinations (Publish,
  Settings, Activity), Korean→English strings, vertical config with
  explanations, URL copy/open, keep-alive toggle, relay editor, busy
  guards, session retention across tabs/rotation
- feat(sample): KeepAliveService with notification actions; PortalClientHolder
  for process-scoped ownership; SampleApp for shared state
- docs(readme): mascot logo (PNG), upstream-sync section

- feat(sdk): `SessionRegistry` extracted from `PortalClient`;
  `Portal.client()`/`Portal.builder()` entry points
- feat(sample): relay list editor, keep-alive foreground-service toggle,
  vertical config list; `PortalClientHolder` for process-scoped ownership
- chore(scripts): `sync-portal-tunnel.sh` for upstream binary updates
- docs(readme): mascot logo, upstream-sync section

- feat(sample): per-config explanations on both UIs; copy button on public
  URL card; URL-rotation note

- feat(sample): redesigned Compose UI (dark theme, gradient hero, rounded
  cards); bundled Snake game served via staticDir

- feat(sdk): `PortalClient(context)` on Android defaults `identity_path` to
  `filesDir/identity.json`; relay URLs normalized at `open` mirroring
  `utils.NormalizeRelayURL`; `PortalClient.sessions` StateFlow;
  `portalConfig { }` DSL; `PortalClientHolder.open` callback on Main
- test(sdk): sessions flow tracking, awaitActive timeout, isClosed, relay
  normalization

- feat(sdk): add `portal-android-lifecycle` module — `PortalClientHolder`
  (process-scoped client) + `PortalTunnelService` (foreground-service base)
- feat(sdk): `PortalIosSession` parity — refresh/addRelay/removeRelay/
  updateMetadata/awaitReady; `PortalIosClient.diagnostics()`
- feat(sdk): `PortalClient.isClosed`, `PortalTunnel.awaitActive`,
  `isActive`/`name`/`address`, `PortalSnapshot.isActive`
- fix(sdk): non-cancellable cleanup scope so close-during-start cannot orphan
  a native handle; close() serialized via mutex; hub dispatch guards against
  reducer throws; aggregate event drops counted in diagnostics
- feat(sample): Compose sample with config editor, identity, session
  controls, live metadata, relay management, event log, diagnostics
- feat(sample): SwiftUI iOS sample with the same feature set
- build: R8 release build verified; Maven Local publish + clean-consumer
  resolution; signing gated on key presence

- fix(sdk): route native events through a process-global `PortalEventHub` so
  multiple `PortalClient`s can coexist; add `PortalClient.events` aggregate
  stream and keep STOPPING stable against late status merges
- fix(sdk): align relay URL rules with `utils.NormalizeRelayURL` upstream
  (https-only, loopback http upgrade, port range); drop
  `allowInsecureLocalRelays`; allow `maxActiveRelays=0`
- feat(sdk): add `PortalRelayStatus.isMitm`/`isActive` matching upstream
  `RelayStatus` semantics
- build(sdk): enable `explicitApi()` mode across the public surface
- fix(sample): observe the tunnel via `StateFlow`/`flatMapLatest`; add
  `kotlinx-coroutines-android`
- docs: add logo, CONTRIBUTING.md, and expand README

- feat(sdk): add `PortalClient`/`PortalTunnel` common API with `StateFlow`
  snapshots, bounded event stream, `awaitReady`, typed metadata, and
  per-client session ownership
- feat(sdk): add Android engine via `:portal-native-android` JNI bridge with
  prebuilt `libportaltunnel.so` (arm64-v8a, x86_64; 16KB-page aligned)
- feat(sdk): add Kotlin/Native cinterop engine shared by iOS
  (arm64/simulatorArm64/x64) and experimental linuxX64 targets
- feat(ios): add `PortalIosClient`/`PortalIosSession` callback facade with
  explicit subscriptions and main-dispatcher completions
- feat(sdk): add config validation (identity XOR, relay scheme/host rules,
  loopback targets, route exclusivity, capability pre-check)
- fix(sdk): defensive-copy Builder lists, redact identity document from
  `toString`, buffer pre-registration events, keep failed stops retryable,
  roll back native handles on `open` cancellation
- build: add `native/source-lock.json` provenance and linuxX64 C stub test
- docs: add DESIGN_RULES, TROUBLESHOOTING, TASKS, agent skill
- ci: run Gradle workflow only on `release-*` tags and manual dispatch
