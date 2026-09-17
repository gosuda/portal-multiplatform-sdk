# Portal Multiplatform SDK (Kotlin)

Kotlin Multiplatform SDK for [Portal Tunnel](https://github.com/gosuda/portal-tunnel) — expose local services, in-app servers, and static websites directly from Android and iOS application processes to the public internet through self-hosted or public relay pools.

One common Kotlin API drives the same `libportaltunnel` engine that powers the platform SDKs ([Android](https://github.com/gosuda/portal-android-sdk), [iOS](https://github.com/gosuda/portal-ios-sdk), [Flutter](https://github.com/gosuda/portal-flutter-sdk)) — identical config JSON, status model, and event stream.

---

## Features

- **Coroutines-first**: `StateFlow<PortalSnapshot>` authoritative state + bounded `SharedFlow<PortalEvent>`; callback-based facade for Swift.
- **Client-owned sessions**: `PortalClient` owns tunnels; `close()` stops only what it opened. Cancellation-safe start with native rollback.
- **Process-native tunnel**: the Go engine runs in-process via JNI (Android) or C ABI (iOS/Linux).
- **Portal feature set**: tenant TLS & ECH, relay MITM self-probe events, multi-relay pool & discovery, static/SPA hosting, HTTP route aggregation & reverse proxy, x402 micropayments, TCP & UDP proxying.
- **Fail-fast validation**: config errors surface as `PortalException` with stable codes before any native call.

## Modules

| Module | Contents |
|---|---|
| `:portal-sdk` | KMP library: common API + Android/iOS/linuxX64 actuals |
| `:portal-native-android` | `libportaltunnel.so` (arm64-v8a, x86_64) + JNI bridge |
| `:samples:android` | Minimal static-site sample app |

## Installation

```kotlin
// settings.gradle.kts — include the modules, or consume from Maven:
dependencies {
    implementation("io.github.kimmandoo:portal-sdk:0.1.0")
}
```

The Android variant pulls in `portal-native-android` automatically. iOS consumers must link `libportaltunnel` (see *iOS engine archive* below).

## Quick Start (Kotlin)

```kotlin
import kotlinx.coroutines.launch
import org.gosuda.portal.*

val client = PortalClient()          // one per app; outlives screens

scope.launch {
    val tunnel = client.open(
        PortalConfig(
            name = "my-node",
            staticDir = siteDir.absolutePath,
            staticIndex = "index.html",
            discovery = true
        )
    )

    // Authoritative state — safe to collect from UI
    launch {
        tunnel.state.collect { s ->
            println("${s.phase} ${s.primaryPublicUrl}")
        }
    }

    // Or suspend until a capability is confirmed ready
    val ready = tunnel.awaitReady(Capability.STATIC_SITE)

    tunnel.stop()                    // retryable on failure
    client.close()                   // stops owned sessions
}
```

`PortalConfig.Builder` is available for Java-style call sites and parity with `portal-android-sdk`.

## Quick Start (Swift)

```swift
let client = PortalIosClient()
var session: PortalIosSession?
var observation: PortalSubscription?

let op = client.open(config: config) { newSession, failure in
    if let failure { show(failure); return }
    session = newSession
    observation = newSession?.observeState { snapshot in
        render(snapshot.phase, snapshot.primaryPublicUrl)
    }
}
// op.cancel() rolls back an in-flight start; observation.cancel() only
// stops observing — the tunnel keeps running until session.stop { }.
```

## iOS engine archive

The Go mobile bridge source is not yet recovered (`native/source-lock.json`), so no `libportaltunnel.a` ships for iOS. To use the iOS target today, build the archive from the Portal Tunnel mobile sources and link it into the final app (`-lportaltunnel`); the cinterop bindings are already generated from `native/include/portaltunnel.h`. The `PortalSDK` static XCFramework is produced by `./gradlew :portal-sdk:assemblePortalSDKXCFramework` on macOS.

## Platform notes

- **Android**: `arm64-v8a` + `x86_64` only (the shipped `.so`s; both are 16KB-page aligned). `minSdk 26`. Foreground-first: for long-running tunnels, own the `PortalClient` in a properly-typed foreground service — the SDK never auto-starts one.
- **iOS**: foreground sessions; no indefinite-background guarantee. `Dispatchers.Main` completions; observation cancel ≠ tunnel stop.
- **linuxX64**: experimental; used to verify the shared native adapter on desktop CI.

## Security model (short)

- `PortalIdentity.document` contains key material — redacted from `toString`, store it in Keystore-wrapped storage / Keychain, never log it.
- Relay URLs require `https`/`wss` (opt-in `allowInsecureLocalRelays` for local dev); proxy targets are loopback-only unless `allowRemoteTargets`.
- `hide=true` hides the listing, it is not access control. MITM suspicion surfaces as a sticky `hasSecurityWarning`, not proof of compromise.

Full contract: [docs/DESIGN_RULES.md](docs/DESIGN_RULES.md). Known gaps and release gates: [TASKS.md](TASKS.md), [native/source-lock.json](native/source-lock.json).

## AI Agents & Vibe Coding (Skills)

An Agent Skill lives in [`.agents/skills/portal-multiplatform-sdk`](.agents/skills/portal-multiplatform-sdk) with API reference, troubleshooting, and a full Android example.

## License

Apache-2.0 — see [LICENSE](LICENSE). Native binary provenance: `native/source-lock.json`.
