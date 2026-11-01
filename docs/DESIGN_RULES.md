# Portal Multiplatform SDK — Design Rules

Binding implementation contract, distilled from `portal-kmp-sdk-design.md`
(2026-09-16). Code, tests, and future changes must stay consistent with this
document; update it when an intentional contract changes.

## 1. Architecture

```text
app (Kotlin / Swift)
  -> portal-sdk commonMain public API
     PortalClient -> PortalTunnel -> PortalSnapshot / PortalEvent
  -> internal PortalNativeEngine (v1 C ABI)
     androidMain: PortalJni -> JNI -> libportaltunnel.so
     nativeMain:  cinterop  -> libportaltunnel (iOS .a / Linux .so)
     desktopMain: JNA       -> libportaltunnel (packaged .so/.dll/.dylib)
```

- The Go tunnel engine stays native. Kotlin owns config validation, identity
  wrapping, lifecycle, event dispatch, and diagnostics — never the transport.
- `portal-native-android` is a plain `com.android.library` module because the
  `com.android.kotlin.multiplatform.library` plugin has no
  `externalNativeBuild`; it carries the `.so`s and the JNI-bound
  `NativeBridge` (package `org.gosuda.portal.android.internal` is pinned by
  the binary's exported symbols — never rename it).
- `commonMain` must not reference `java.*`, Android `Context`, JNI, or C
  pointers. Platform differences live behind `PortalNativeEngine`.

## 2. Wire contract (v1)

- `PortalConfig`/`PortalStatus`/`PortalRelayStatus`/`PortalHTTPRoute`/
  `PortalX402Config`/`PortalMetadata` serialize with the exact snake_case
  keys of the Android/iOS/Flutter SDKs.
- `PortalJson` uses `encodeDefaults = false`: absent means "native default".
  Never flip this without a golden-fixture review.
- Event types: `STARTED`, `STOPPED`, `STATUS_CHANGED`, `MITM_SUSPECTED`,
  `ERROR`; unknown types surface as `PortalEvent.Unknown`.
- The v1 ABI merges create+start into `PortalStart` and provides a single
  process-global event callback. Stronger guarantees (observer quiescence,
  native revisions, per-session callbacks, `portal_*` v2 ABI) are future work.

## 3. Lifecycle invariants

- One session, one owner: `PortalClient`. `close()` stops only sessions it
  opened; there is no public process-global `stopAll`.
- `open()` returns after ownership is registered; readiness is observed via
  `state`/`awaitReady`, not implied by a successful `open`.
- Native start runs on the owner scope and adopts the returned handle before
  caller cancellation can interrupt ownership registration. Cancellation
  waits for non-cancellable rollback; if native stop fails, the session stays
  observable in `client.sessions` as STOPPING and can be retried.
- Events arriving before registration are held in a bounded orphan buffer
  (32 sessions x 64 events) and drained on registration.
- `stop()` failure keeps the handle registered in `STOPPING` and is
  retryable; terminal states (`STOPPED`, `FAILED`) never resurrect on late
  events.
- `close()` rejects new sessions immediately, performs non-cancellable
  cleanup, and may be called again to retry failed native stops. Managed
  lifetime cleanup preserves caller cancellation.
- `state` is authoritative and monotonically revised; `events` is a bounded
  auxiliary stream — undelivered events increment `droppedEventCount`.
- `MITM_SUSPECTED` sets the sticky `hasSecurityWarning` on the snapshot.
- `publish(config)` is the ready-on-return operation: it composes
  `open` + `awaitActive` and stops the session when readiness fails or the
  call is cancelled. A cleanup failure leaves the session registered in
  STOPPING (retryable via `stop()`) and is reported with
  `operation="publish_cleanup"`; cancellation still propagates as
  `CancellationException`.
- ACTIVE requires every requested capability. A ready HTTP lease cannot imply
  raw transport readiness: TCP requires a nonblank `tcp_addr` on a ready relay,
  UDP requires `udp_addr`, and HTTP/static require a ready public URL. Endpoint
  withdrawal removes that capability and returns the session to CONNECTING.
- Publish failures are typed: `PortalFailure.operation` is `publish` or
  `publish_cleanup`, `terminalPhase` records the phase that ended the
  session, and `readinessFailure` carries the original readiness error when
  cleanup itself failed — no message parsing.
- `tunnel.publicUrl` is the ready-result convenience surface (first URL of
  the authoritative snapshot); `PortalIosSession.primaryPublicUrl` mirrors
  it for Swift.
- Managed lifetime: `PortalClient.use {}` (suspend extension) closes the
  client after the block; `PortalClientHolder.publish` is the process-owned
  ready-on-return callback; iOS teardown is `PortalIosClient.close` +
  `PortalOperation.cancel`. Each pattern stops only its owner's sessions.
- iOS exposes a point-in-time `PortalIosClient.sessions` ownership snapshot.
  Failed publish/cancellation cleanup remains reachable in STOPPING; `stop`
  can retry cleanup even after the client has closed.
- `PortalDiagnostics` reports `engineVersion` (portal-tunnel core), and
  per-session relay origin/last structured failure. It redacts configured
  inline identity material from diagnostic failure messages,
  including nested readiness failures. Raw snapshots/native errors are not
  sanitized logging exports.

## 4. Memory & threads (native)

- Every `char*` out-param from the C ABI is freed exactly once with
  `PortalFreeString`.
- Event-callback arguments are borrowed for the duration of the callback.
  The Go bridge allocates/frees its buffers around the call; Kotlin copies
  the strings without freeing them. Conversion and dispatch never throw
  across a native callback.
- The `staticCFunction` callback never throws and never blocks.
- All engine calls are serialized per session (`opsMutex`) and dispatched off
  the caller's thread.

## 5. Validation & security

- `identity_json` XOR `identity_path`; `discovery=false` requires >=1 relay.
- Relay URLs: `https` only; bare hosts default to `https`; `http` is
  accepted only for loopback hosts (the engine upgrades it); no user-info;
  port 1-65535. Mirrors `utils.NormalizeRelayURL` in portal-tunnel.
  The same normalization and validation apply to live relay additions/removals;
  bracketed IPv6 literals are accepted.
- `target_addr`/`udp_addr` and HTTP route upstreams are loopback-only unless
  `allowRemoteTargets`; wildcard addresses also require that explicit opt-in.
- Static directories/route roots reject `..` segments; static indexes are plain
  file names. Live metadata is limited by its UTF-8 byte size, not UTF-16 length.
- A route needs exactly one of `upstream`/`static_root`.
- The bundled portal-tunnel v2.7.1 uses relay protocol 10. ECH, x402,
  priced routes, method-constrained routes and overlay are not supported by
  this bridge. Recognized v1 fields fail with `UNSUPPORTED_CAPABILITY` before
  native start; they must never be silently ignored or advertised as applied.
- `PortalIdentity.document` is secret: redacted from `toString`, never
  logged; store via Keystore-wrapped file (Android) or Keychain (iOS).
- `PortalConfig.toString()` and `PortalX402Config.toString()` redact inline
  identity documents and facilitator tokens without changing wire values.
- JNI strings cross the C ABI as standard UTF-8, including supplementary
  Unicode characters; embedded NUL is rejected rather than truncated.
- Platform identity defaults: when a config sets neither `identity_json` nor
  `identity_path`, Android resolves `filesDir/identity.json` via
  `PortalClient(context)` (the lifecycle holder/service pass the application
  context) and iOS resolves `Application Support/Portal/identity.json` inside
  `open`/`publish` — filesystem failures surface as PERMISSION_DENIED through
  the operation, never as a silent fallback to a temporary path.
- `hide=true` is a listing preference, not access control.

## 6. Platform contracts

- Ordinary onboarding is one application-owned client plus
  `publish(intentFactory(...))`; identity persistence, readiness waiting and
  failed-start rollback belong to the SDK. Kotlin `PortalConfig.tcp(address)`
  sets both transport and target. Swift exports a real `PortalIosClient()`,
  one-argument intent factories, two-argument route-entry factories and a
  no-timeout-argument `publish` overload using the default 30 seconds.
- Android: foreground-first. Long-running tunnels belong to an explicit
  service owner; the SDK never auto-starts services. Shipped ABIs:
  arm64-v8a, x86_64 (both 16KB-page aligned). minSdk 26.
- iOS 16+: foreground sessions; no promise of indefinite background execution.
  `PortalIosClient`/`PortalIosSession` provide callback-based APIs on the
  main dispatcher; cancelling an observation never stops the tunnel. Each
  published iOS klib embeds its matching engine archive built from
  `native/bridge` and propagates `Security.framework` linkage. KMP and Swift
  consumers never build or manually link `libportaltunnel.a`.
  `PortalIosConfigFactory` is the stable Swift config surface: intent
  factories plus `custom(...)`, the advanced escape hatch covering every
  `PortalConfig` field without the generated all-fields initializer or
  `KotlinBoolean`.
  Completion callbacks run on Main even when the owner has already closed;
  explicit operation cancellation suppresses completion. User callback
  exceptions are never delivered again as operation failures.
- linuxX64: experimental; exists to exercise the shared `nativeMain` adapter
  and cinterop path on CI/desktop.
- Desktop (JVM 17+): `PortalDesktop.client(applicationId)` loads the verified
  `libportaltunnel` from the `portal-native-desktop` runtime JAR via JNA.
  The engine is extracted to a content-addressed cache
  (`<cache>/portal-sdk/<version>/<sha256>/<file>`) under a file lock with an
  atomic move; a hash mismatch fails `NATIVE_UNAVAILABLE` — no silent
  fallback. `applicationId` is validated eagerly (`INVALID_CONFIG`); the
  identity directory is created lazily inside `open`/`publish` so filesystem
  failures surface as `PERMISSION_DENIED` through the operation (matching
  iOS). Per-OS defaults: `$XDG_STATE_HOME/<app>/portal` (Linux),
  `%LOCALAPPDATA%/<app>/Portal` (Windows),
  `~/Library/Application Support/<app>/Portal` (macOS). The linux-x64 engine
  targets glibc 2.17 via zig cc (built artifact requires only GLIBC_2.14).

## 7. Provenance & release gates

- `native/source-lock.json` tracks core/bridge commits, toolchains, and
  artifact SHA-256s. `UNRESOLVED`/`PENDING`/`MISSING` fields block releases.
  Review every bundled dependency, not only Portal Tunnel's MIT license.
  The v2.7.1 SDK graph no longer links the previous `go-ethereum`/LGPL
  dependency. The full notices/source/redistribution review remains unresolved
  and blocks public binary publication, not local build verification.
- The Go mobile bridge is reimplemented in `native/bridge` (upstream
  `portal-tunnel/mobile` was removed); iOS archives are built locally and
  gitignored. iOS engine support is verified: `iosSimulatorArm64Test` links
  and runs against the real archive, and a real-relay tunnel was observed
  end-to-end on the simulator.
- Desktop engines are built per-OS on the release matrix
  (`scripts/build-desktop-engine.sh`), verified against the v1 ABI
  (`scripts/verify-desktop-engine.sh`), and packaged into
  `portal-native-desktop`. The `GenerateNativeIndex` task fails the build
  unless all three targets are present
  (`-Pportal.native.requireComplete=true`) — a published runtime JAR can
  never silently ship an incomplete matrix.
- CI runs only for `release-*` tags and manual dispatch (AGENTS.md), with the
  same verification workflow reusable by publication. Automatic runs require
  a `release(scope):` commit. Manual dispatch verifies by default; explicit
  publication additionally requires `release-v<catalog version>`, resolved
  provenance/licensing, credentials, and the full platform/consumer matrix.
- Swift releases contain a complete downloadable local package ZIP, including
  wrapper sources and the XCFramework. A loose `Package.swift` release asset
  is not a remotely resolvable SwiftPM package.

## 8. Sample applications

- Samples demonstrate the SDK, not a separate game engine, model manager, or
  server-installation product. Protocol listeners and request inspection belong
  to sample modules, never `portal-sdk`.
- Client-side web games are static-site examples. Keep one flat shared bundle
  under `samples/shared/common/site` for Android assets, the iOS bundle and
  desktop application resources. Snake, memory pairs, reaction testing and
  volume-based water sloshing run in the browser, with keyboard/touch input,
  local-tab scores, bounded work and paused/released input when hidden.
  Do not add game state, installers, sensor permissions or frontend dependencies
  to the SDK, or imply multiplayer/global score persistence.
- Offer runnable static-site, HTTP API/webhook, HTTP prefix-route, TCP echo,
  and UDP echo demonstrations, plus explicit custom loopback upstream inputs.
  Built-in listeners bind only to loopback and have bounded input and logs.
- Explain which SDK API each mode exercises and how to send a real request.
  ACTIVE is tunnel readiness, not proof of upstream application success.
  Show raw TCP/UDP relay addresses separately from browser URLs.
- Configuration drafts apply to the next session; metadata/relay commands
  target the current owned session. A display-name edit never rotates or
  overwrites a persisted private identity.
- Observation belongs to the view; sessions and built-in listeners belong to
  the application owner. Failed rollback/stop keeps the owned session and its
  payload reachable for retry. Stop listeners only after terminal cleanup.
- Request inspection records bounded method/path or transport counters, not
  authorization headers, identity documents, tokens, or request bodies.
- Android background operation requires explicit foreground-service ownership;
  iOS promises foreground operation only; desktop ends with its process unless
  the user explicitly keeps the application alive.
