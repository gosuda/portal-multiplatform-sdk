---
name: portal-multiplatform-sdk
description: Kotlin Multiplatform SDK for Portal Tunnel — expose local services and static sites from Android/iOS app processes through Portal relays. Use when adding Portal tunneling to a KMP, Android, or iOS app.
---

# Portal Multiplatform SDK

## What this is

KMP wrapper over the `libportaltunnel` Go engine (v1 C ABI). Public API lives
in `org.gosuda.portal`; the engine boundary is `internal PortalNativeEngine`.

## Mental model

- `PortalClient` owns sessions. Create once per app; `close()` stops only its
  own tunnels. No global `stopAll` in the public API.
- `client.open(config)` = ownership registered + native start accepted.
  Readiness is `tunnel.state` / `tunnel.awaitReady(capability)`, never
  assumed from `open` returning.
- `state: StateFlow<PortalSnapshot>` is authoritative (phase, urls, relays,
  lastFailure, hasSecurityWarning, droppedEventCount). `events: SharedFlow`
  is auxiliary, bounded, no replay.
- `stop()` is retryable: failure leaves the session in STOPPING, still
  registered. Terminal phases (STOPPED/FAILED) never resurrect on late events.
- Cancelling `open` rolls the native handle back; cancelling an observation
  never stops the tunnel.

## Rules

1. Validate before open: identity_json XOR identity_path; discovery=false
   needs >=1 relay; https/wss relays unless `allowInsecureLocalRelays`;
   loopback targets unless `allowRemoteTargets`; route = upstream XOR
   static_root; `overlay` is unsupported in v1 (UNSUPPORTED_CAPABILITY).
2. `PortalIdentity.document` is secret — never log it; `toString` redacts.
   Persist via Keystore-wrapped file (Android) / Keychain (iOS).
3. `encodeDefaults=false` on the wire codec is load-bearing. Do not change.
4. Never rename `org.gosuda.portal.android.internal.NativeBridge` — the
   shipped `.so` binds JNI symbols to that exact class.
5. iOS needs `libportaltunnel` linked by the consumer until the Go bridge is
   recovered (native/source-lock.json). Do not claim iOS works end-to-end.
6. Android is foreground-first; long-running tunnels need an explicit
   foreground-service owner chosen by the app.
7. All SDK exceptions are `PortalException` with `failure.code` from
   `PortalFailure.Codes`; `CancellationException` always propagates.

## Files

- `references/api-reference.md` — full API surface
- `references/troubleshooting.md` — build/link/runtime pitfalls
- `examples/StaticSiteActivity.kt` — end-to-end Android example
