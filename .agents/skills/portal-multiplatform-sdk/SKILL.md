---
name: portal-multiplatform-sdk
description: Kotlin Multiplatform SDK for Portal Tunnel — expose local services and static sites from Android/iOS app processes through Portal relays. Use when adding Portal tunneling to a KMP, Android, or iOS app.
---

# Portal Multiplatform SDK

## What this is

KMP wrapper over the `libportaltunnel` Go engine (v1 C ABI). Public API lives
in `org.gosuda.portal`; the engine boundary is `internal PortalNativeEngine`.
## Start with two steps

First install staged Maven artifacts or a locally packaged XCFramework using
the root README. `0.1.0` is unpublished; do not imply Maven Central or a remote
SwiftPM release is available.

1. Create one long-lived owner: Android `PortalClient(applicationContext)`,
   desktop `PortalDesktop.client("com.example.myapp")`, or Swift
   `PortalIosClient()`.
2. Publish one factory config. Kotlin, inside a coroutine:
   `client.publish(PortalConfig.http("127.0.0.1:8080"))`.
   Swift: `client.publish(config:
   PortalIosConfigFactory.shared.http(targetAddress: "127.0.0.1:8080")) {
   session, failure in /* use session?.primaryPublicUrl or failure.code */ }`.

Start the app's HTTP server before publishing and keep it, plus the client,
alive while serving. The SDK supplies platform identity persistence, waits
for readiness (30 seconds by default), and rolls back failed publish: do not
ask consumers to build identity paths, polling or rollback routines for the
normal path. At owner teardown call `client.close()` in Kotlin or
`client.close { failure in ... }` in Swift; handle structured errors and retry
retained STOPPING cleanup when necessary. Swift callbacks run on Main.

For raw listeners use Kotlin `PortalConfig.tcp("127.0.0.1:9000")` or
`.udp("127.0.0.1:9001")`; Swift uses
`PortalIosConfigFactory.shared.tcp(targetAddress: "127.0.0.1:9000")` or
`.udp(targetAddress: "127.0.0.1:9001")`. Read raw relay endpoints, not browser
URLs. Names, explicit relays, custom timeout and constructor options belong
after this minimal path. Never build TCP configs with a name-only factory
and a later target-field copy.


## Mental model

- `PortalClient` owns sessions. Create once per app; `close()` stops only its
  own tunnels. No global `stopAll` in the public API.
- `client.publish(config)` is the easy path: returns after the tunnel is
  ACTIVE and rolls the session back if readiness fails. `client.open(config)`
  = ownership registered + native start accepted (advanced path); readiness
  is `tunnel.state` / `tunnel.awaitReady(capability)`, never assumed from
  `open` returning.
- `state: StateFlow<PortalSnapshot>` is authoritative (phase, urls, relays,
  lastFailure, hasSecurityWarning, droppedEventCount). `events: SharedFlow`
  is auxiliary, bounded, no replay. `tunnel.publicUrl` reads the first URL
  directly.
- `stop()` is retryable: failure leaves the session in STOPPING, still
  registered. Terminal phases (STOPPED/FAILED) never resurrect on late events.
- ACTIVE means every requested capability is ready. Raw TCP/UDP each require
  their own nonblank endpoint on a ready relay, not just an active HTTP lease.
- HTTP prefix routing selects the longest matching prefix, not entered order;
  `/` can be the catch-all. ACTIVE is not an upstream application-health check.
- Cancelling `open`/`publish` rolls the native handle back; cancelling an
  observation never stops the tunnel.
- Swift cleanup recovery is `PortalIosClient.sessions`: retained STOPPING
  sessions remain reachable after failed publish/cancellation cleanup, and
  `session.stop` can retry even after client close failed.

## Rules

1. Validate before open: identity_json XOR identity_path; discovery=false
   needs >=1 relay; https-only relays (http allowed for loopback);
   loopback targets and HTTP route upstreams unless `allowRemoteTargets`;
   route = upstream XOR static_root. The bundled v2.7.1/protocol-10 engine
   rejects ECH, x402/priced routes, method-constrained routes and overlay with
   UNSUPPORTED_CAPABILITY. Live relay edits use the same normalization.
2. `PortalIdentity.document` is secret — never log it; `toString` redacts.
   Persist via Keystore-wrapped file (Android) / Keychain (iOS).
3. `encodeDefaults=false` on the wire codec is load-bearing. Do not change.
4. Never rename `org.gosuda.portal.android.internal.NativeBridge` — the
   shipped `.so` binds JNI symbols to that exact class.
5. iOS engine archives embed in each published klib/XCFramework slice;
   consumers never build or link `libportaltunnel.a` manually.
6. Android is foreground-first; long-running tunnels need an explicit
   foreground-service owner chosen by the app.
7. All SDK exceptions are `PortalException` with `failure.code` from
   `PortalFailure.Codes`; `CancellationException` always propagates.
8. Samples are runnable SDK workbenches (static, HTTP/webhook, routes, TCP/UDP
   echo). Demo listeners belong only to samples, bind loopback on ephemeral
   ports, and stay alive until their owned tunnel actually stops.
   Browser games are shared static content under `samples/shared/common/site`,
   not SDK game/server code. Reuse that bundle across platforms; see the root
   README's arcade instructions rather than duplicating platform pages.

## Files

- `references/api-reference.md` — full API surface
- `references/troubleshooting.md` — build/link/runtime pitfalls
- `examples/StaticSiteActivity.kt` — end-to-end Android example
