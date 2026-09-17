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
- Native start runs on the owner scope so caller cancellation cannot orphan a
  handle; cancellation triggers a `NonCancellable` rollback `PortalStop`.
- Events arriving before registration are held in a bounded orphan buffer
  (32 sessions x 64 events) and drained on registration.
- `stop()` failure keeps the handle registered in `STOPPING` and is
  retryable; terminal states (`STOPPED`, `FAILED`) never resurrect on late
  events.
- `state` is authoritative and monotonically revised; `events` is a bounded
  auxiliary stream — undelivered events increment `droppedEventCount`.
- `MITM_SUSPECTED` sets the sticky `hasSecurityWarning` on the snapshot.

## 4. Memory & threads (native)

- Every `char*` out-param from the C ABI is freed exactly once with
  `PortalFreeString`.
- Event-callback arguments are NOT freed (iOS SDK contract; freeing static
  buffers crashes — verified by the linuxX64 stub test). If the Go bridge is
  recovered and shown to allocate per call, revisit.
- The `staticCFunction` callback never throws and never blocks.
- All engine calls are serialized per session (`opsMutex`) and dispatched off
  the caller's thread.

## 5. Validation & security

- `identity_json` XOR `identity_path`; `discovery=false` requires >=1 relay.
- Relay URLs: `https` only; bare hosts default to `https`; `http` is
  accepted only for loopback hosts (the engine upgrades it); no user-info;
  port 1-65535. Mirrors `utils.NormalizeRelayURL` in portal-tunnel.
- `target_addr`/`udp_addr` are loopback-only unless `allowRemoteTargets`.
- `static_dir` rejects `..` segments; `static_index` is a plain file name.
- A route needs exactly one of `upstream`/`static_root`; `amount` is a
  decimal string (never Float/Double).
- `overlay` is not in the v1 capability set; requesting it fails with
  `UNSUPPORTED_CAPABILITY` rather than being silently ignored.
- `PortalIdentity.document` is secret: redacted from `toString`, never
  logged; store via Keystore-wrapped file (Android) or Keychain (iOS).
- `hide=true` is a listing preference, not access control.

## 6. Platform contracts

- Android: foreground-first. Long-running tunnels belong to an explicit
  service owner; the SDK never auto-starts services. Shipped ABIs:
  arm64-v8a, x86_64 (both 16KB-page aligned). minSdk 26.
- iOS: foreground sessions; no promise of indefinite background execution.
  `PortalIosClient`/`PortalIosSession` provide callback-based APIs on the
  main dispatcher; cancelling an observation never stops the tunnel. The
  engine archive comes from `native/bridge` (clean-room Go bridge over
  `sdk.Exposure`), built per target by `scripts/build-ios-engine.sh` and
  linked via per-target `linkerOpts` plus `-framework Security`.
- linuxX64: experimental; exists to exercise the shared `nativeMain` adapter
  and cinterop path on CI/desktop.

## 7. Provenance & release gates

- `native/source-lock.json` tracks core/bridge commits, toolchains, and
  artifact SHA-256s. `UNRESOLVED`/`PENDING`/`MISSING` fields block releases.
- The Go mobile bridge is reimplemented in `native/bridge` (upstream
  `portal-tunnel/mobile` was removed); iOS archives are built locally and
  gitignored. iOS engine support is verified: `iosSimulatorArm64Test` links
  and runs against the real archive, and a real-relay tunnel was observed
  end-to-end on the simulator.
- CI runs only for `release-*` tags and `workflow_dispatch` (AGENTS.md).
