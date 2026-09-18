<p align="center">
  <img src="docs/logo.png" alt="Portal SDK mascot"/>
</p>

<h1 align="center">Portal Multiplatform SDK</h1>

<p align="center">
  Expose app-local <b>HTTP</b> servers, <b>TCP/UDP</b> sockets, and static
  assets from Android and iOS through
  <a href="https://github.com/gosuda/portal-tunnel">Portal</a> relays.
</p>

<p align="center">
  <a href="https://github.com/gosuda/portal-multiplatform-sdk/actions/workflows/gradle.yml"><img src="https://img.shields.io/github/actions/workflow/status/gosuda/portal-multiplatform-sdk/gradle.yml" alt="CI"/></a>
  <img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4"/>
  <img src="https://img.shields.io/badge/ABI-v1-448AFF" alt="ABI v1"/>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-green" alt="Apache-2.0"/></a>
</p>

---

## What you can build with it

Portal gives software running inside an Android or iOS app a public endpoint —
no public IP or port forwarding required:

- **On-device APIs and webhooks** — expose a Ktor, NanoHTTPD, or other
  loopback HTTP server for callbacks, integrations, and device-local APIs.
- **Mobile game and peer services** — publish TCP or UDP listeners for
  multiplayer sessions, co-op lobbies, and custom protocols.
- **Dev tunnels** — reach an app's local service from remote QA, demos, or
  development tooling.
- **Route-based applications** — send different URL prefixes to local HTTP
  upstreams or bundled assets, with optional x402 pricing per route.
- **Static applications** — publish files directly when running an embedded
  HTTP server would add unnecessary weight.
- **Privacy-aware endpoints** — use ECH, unlisted discovery, and MITM
  self-probing across multiple relays.

## What it does

This SDK is a mobile tunnel runtime around `libportaltunnel`. `PortalConfig`
describes which app-local service to expose; `PortalClient` owns its tunnel
sessions and reports their authoritative state:

- **HTTP/TLS upstreams and routes** proxy requests to loopback servers running
  in the app process.
- **Raw TCP and UDP tunnels** expose app-owned sockets and custom protocols.
- **Static directories** are a convenience mode, not a required architecture.
- **One lifecycle API** covers relay discovery, ECH privacy, x402 payments,
  structured failures, and explicit session ownership.
- **`StateFlow` snapshots** are authoritative; bounded events are auxiliary
  notifications.

## Exposure modes

| App-local source | `PortalConfig` shape | Typical use |
|---|---|---|
| HTTP server | `targetAddr = "127.0.0.1:8080"` | APIs, webhooks, dev servers |
| HTTP routes | `httpRoutes = listOf(...)` | Prefix routing, mixed upstreams, x402 |
| TCP listener | `tcp = true` | Custom protocols, game sessions |
| UDP listener | `udp = true, udpAddr = "127.0.0.1:7777"` | Real-time and datagram protocols |
| Static directory | `staticDir = dir` | Serverless assets and bundled web UIs |

## Architecture

```mermaid
flowchart LR
    subgraph app["Android / iOS app"]
        UI["App lifecycle"] --> PC["PortalClient"]
        HTTP["HTTP server<br/>127.0.0.1:8080"]
        TCP["TCP listener"]
        UDP["UDP listener"]
        FILES["Static assets"]
    end
    PC --> HUB["PortalEventHub<br/>(session routing)"]
    HUB --> ENG["libportaltunnel runtime"]
    ENG --> HTTP & TCP & UDP & FILES
    ENG --> R["Portal relays"]
    R --> PUBLIC["Public HTTP/TCP/UDP endpoint"]
```

| Layer | Where | Contract |
|---|---|---|
| `PortalClient` / `PortalTunnel` | `commonMain` | configuration, snapshots, failures, ownership |
| `PortalEventHub` | `commonMain` | one native callback → correct session owner |
| `PortalNativeEngine` | platform adapters | raw string/JSON ABI seam |
| JNI bridge | `portal-native-android` | `org.gosuda.portal.android.internal.NativeBridge` |
| cinterop | `nativeMain` | `portaltunnel.def` → `native/include/portaltunnel.h` |
| Swift facade | `iosMain` | `PortalIosClient` / `PortalIosSession` callbacks |

## Platform matrix

| Target | Status | Native engine |
|---|---|---|
| `android` (arm64-v8a, x86_64) | ✅ shipped | prebuilt `libportaltunnel.so`, 16 KB-page aligned |
| `iosArm64` / `iosSimulatorArm64` / `iosX64` | ✅ verified | `libportaltunnel.a` built from `native/bridge` via `scripts/build-ios-engine.sh` — see [native/README.md](native/README.md) |
| `linuxX64` | 🧪 experimental | C stub for tests; link the real `.so` for production |

## Install

```kotlin
// settings.gradle.kts — include the modules in your build, or consume the
// published coordinates once a release is cut.
implementation("io.github.gosuda:portal-sdk:0.1.0")
```

iOS additionally needs the `PortalSDK` XCFramework plus `libportaltunnel.a`
linked into the app target — see [samples/ios/README.md](samples/ios/README.md).
The engine archive is rebuilt from `native/bridge` (clean-room Go bridge over
portal-tunnel v2.4.3 `sdk.Exposure`) and is gitignored.

## Quick start: expose a local HTTP server

Start your HTTP server on a loopback address, then publish it:

### Kotlin (Android / common)

```kotlin
val client = PortalClient(applicationContext)

// Opens the tunnel, waits until it is ACTIVE, and rolls back on failure.
val tunnel = client.publish(
    PortalConfig.http("127.0.0.1:8080", name = "device-api")
)

println(tunnel.state.value.primaryPublicUrl)

tunnel.stop()      // retryable on native failure
client.close()     // stops only the sessions this client owns
```

`publish` is the easy path: it returns only after the tunnel reports ACTIVE
and stops the session if readiness fails. Use `open` when you need
accepted-before-ready semantics — e.g. to render CONNECTING or await a single
capability:

```kotlin
val tunnel = client.open(PortalConfig.http("127.0.0.1:8080"))
tunnel.state.collect { snapshot ->
    println("${snapshot.phase} ${snapshot.primaryPublicUrl}")
}
val ready = tunnel.awaitReady(Capability.HTTP_TLS)
// or: tunnel.awaitActive(15_000)
```

### Swift (iOS)

```swift
let client = PortalIosClient(allowRemoteTargets: false, defaultIdentityPath: nil)
let config = PortalIosConfigFactory.shared.http(
    targetAddress: "127.0.0.1:8080", name: "device-api"
)
operation = client.publish(config: config, timeoutMillis: 30_000) {
    session, failure in
    // session?.snapshot.primaryPublicUrl, or a structured PortalFailure
}
```

The Android and iOS sample apps use static assets because that makes the demo
self-contained. Static serving is optional; the same lifecycle API owns HTTP,
TCP, and UDP tunnels.

See [samples/android](samples/android) for the complete Compose app and
[samples/ios](samples/ios) for the SwiftUI equivalent.


## Usage

### Configuration

`PortalConfig` selects the app-local service and optional relay features.
Intent factories cover the common exposure modes; the raw constructor and
`PortalConfig.Builder` remain for advanced combinations:

```kotlin
// Proxy a local HTTP server
PortalConfig.http("127.0.0.1:8080", name = "api")

// Route prefixes to one or more local HTTP services
PortalConfig.routes(
    listOf(
        PortalHTTPRoute(prefix = "/api", upstream = "http://127.0.0.1:8080"),
        PortalHTTPRoute(prefix = "/admin", upstream = "http://127.0.0.1:9090")
    ),
    name = "routes"
)

// Raw TCP / UDP listeners
PortalConfig.tcp(name = "tcp-service")
PortalConfig.udp("127.0.0.1:7777", name = "game")

// Static assets without an embedded HTTP server
PortalConfig.staticSite(dir, index = "index.html", name = "site")

// Advanced: raw constructor for combinations the factories don't cover
PortalConfig(name = "private", discovery = false,
             relays = listOf("https://portal.example.com"))

// Paid route (x402)
PortalConfig(
    name = "paid",
    httpRoutes = listOf(
        PortalHTTPRoute(prefix = "/premium", upstream = "http://127.0.0.1:8080",
                        amount = "0.01")
    ),
    x402 = PortalX402Config(payTo = "0x…", network = "sui", asset = "USDC")
)
```

Key rules: `identity_json` XOR `identity_path`; `discovery=false` needs ≥1
relay; relays are `https`-only (`http` allowed for loopback); targets are
loopback-only unless `PortalClient(allowRemoteTargets = true)`.

### Lifecycle & ownership

- One `PortalClient` per app (or long-lived component). It owns every
  `PortalTunnel` it opens.
- `client.publish(config)` returns only after the tunnel reports ACTIVE and
  stops the session if readiness fails — the easy path for "give me a URL".
- `client.open(config)` returns once the native runtime accepted the start;
  readiness is observed via `state`/`awaitReady`/`awaitActive`.
- `client.close()` stops exactly the sessions it owns — never another
  client's. Safe to call twice; concurrent calls serialize.
- `tunnel.stop()` is idempotent and retryable: a native failure leaves the
  session in `STOPPING`, not `STOPPED`.
- Cancelling `open` or `publish` rolls the native handle back — no orphaned
  sessions.

### Observing state

```kotlin
// Authoritative snapshot — render this in UI.
tunnel.state.collect { snap ->
    snap.phase               // IDLE → STARTING → CONNECTING → ACTIVE → …
    snap.primaryPublicUrl    // first public URL, if any
    snap.relays              // per-relay state/failure/error
    snap.hasSecurityWarning  // sticky MITM flag
    snap.isActive / isTerminal
}

// Auxiliary events (bounded, not replayed).
tunnel.events.collect { event -> /* Started / StatusChanged / … */ }

// Aggregate stream across all sessions this client owns.
client.events.collect { event -> … }

// Live session list (open order).
client.sessions.collect { list -> … }
```

### Live updates without restart

```kotlin
tunnel.updateMetadata(PortalMetadata(description = "new", tags = listOf("x")))
tunnel.addRelay("https://portal.example.com")
tunnel.removeRelay("https://portal.example.com")
tunnel.refresh()   // pull authoritative native status now
```

### Identity

```kotlin
val identity = PortalIdentity.generate("my-app")   // native-generated keys
val restored = PortalIdentity.parse(savedJson)      // validate + redacted toString
config = PortalConfig(identityJson = identity.document, ...)
// or let the engine persist one:
PortalConfig(identityPath = File(filesDir, "identity.json").absolutePath, ...)
```

`PortalIdentity.document` contains key material — redacted from `toString`;
store it in Keystore-wrapped storage / Keychain, never log it.

When a config sets neither `identityJson` nor `identityPath`, the SDK supplies
a platform-owned default: `filesDir/identity.json` on Android (via
`PortalClient(context)`) and `Application Support/Portal/identity.json` on iOS.

### Kotlin DSL + Android Context

```kotlin
val config = portalConfig {
    setName("device-api")
    setTargetAddress("127.0.0.1:8080")
    setDiscovery(true)
}

// Android: defaults identity_path to filesDir/identity.json
val client = PortalClient(context)
```

### Android: surviving background & rotation

`portal-android-lifecycle` keeps a tunnel alive past the screen:

```kotlin
// Application.onCreate
PortalClientHolder.init(this)

// Foreground service for tunnels that must run while backgrounded
class TunnelService : PortalTunnelService() {
    override fun buildNotification(): Notification = …
}
// manifest: <service android:name=".TunnelService"
//   android:foregroundServiceType="dataSync"/>
```

### Diagnostics

```kotlin
val d = client.diagnostics()
// d.sdkVersion, d.abiVersion, d.activeSessions, d.droppedOrphanEvents,
// d.droppedAggregateEvents, d.sessions[]
```



## Design rules (the short version)

- `identity_json` XOR `identity_path`; `discovery=false` requires ≥1 relay.
- Relay URLs are `https` only — bare hosts default to `https`, `http` is
  accepted only for loopback (the engine upgrades it). Mirrors
  `utils.NormalizeRelayURL` in portal-tunnel.
- `target_addr`/`udp_addr` are loopback-only unless `allowRemoteTargets`.
- `hide=true` hides the listing; it is not access control. MITM suspicion is a
  sticky `hasSecurityWarning`, not proof of compromise.
- `overlay` is not in the v1 capability set → `UNSUPPORTED_CAPABILITY`.

Full contract: [docs/DESIGN_RULES.md](docs/DESIGN_RULES.md) ·
Troubleshooting: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) ·
Known gaps & release gates: [TASKS.md](TASKS.md),
[native/source-lock.json](native/source-lock.json).

## Updating the native engine

The bundled `libportaltunnel.so` comes from
[gosuda/portal-tunnel](https://github.com/gosuda/portal-tunnel) releases.
To sync to a new release:

```bash
./scripts/sync-portal-tunnel.sh          # latest
./scripts/sync-portal-tunnel.sh v1.2.3   # specific tag
```

The script downloads the Android ABI artifacts, swaps them into
`portal-native-android/src/main/jniLibs/`, and updates `portal-tunnel.version`.
Rebuild and run the ABI smoke test after syncing.

## Repository layout

```
portal-sdk/               KMP library (commonMain / androidMain / nativeMain / iosMain)
portal-native-android/    JNI bridge + prebuilt libportaltunnel.so
portal-android-lifecycle/ Process-scoped client + foreground-service base
native/                   portaltunnel.h, C test stub, source provenance
samples/android/          Compose sample (config, identity, relays, events, diagnostics)
                          — see samples/android/README.md for the on-device model API
samples/ios/              SwiftUI sample + XCFramework instructions
docs/                     DESIGN_RULES, TROUBLESHOOTING, WORK_CHECKPOINT
.agents/skills/           agent skill: API reference, examples, troubleshooting
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: `./gradlew build` must stay
green, commits follow `type(scope): subject`, and CI runs on `release-*` tags
or manual dispatch.

## License

Apache-2.0 — see [LICENSE](LICENSE).
