# API Reference — org.gosuda.portal
## Normal publish path

Install the staged Maven artifacts or locally packaged XCFramework from the
root README first; the checked-in `0.1.0` coordinates are not a public release.
Start your app-owned loopback HTTP server, then create one long-lived client
and publish one factory config:

```kotlin
val client = PortalClient(applicationContext) // Android
// Desktop instead: PortalDesktop.client("com.example.myapp")
val tunnel = client.publish(PortalConfig.http("127.0.0.1:8080"))
println(tunnel.publicUrl)
```

Keep the server and client alive while serving, then `tunnel.stop()` or
`client.close()` at the corresponding owner teardown. Handle `PortalException`
through `failure.code`, `operation` and `retryable`; cancellation propagates.
The SDK owns persistent platform identity, readiness waiting and rollback, so
none requires an app-managed path or polling/cleanup sequence.

ACTIVE covers all requested capabilities. HTTP/static require a ready public
URL; TCP/UDP each require a nonblank transport endpoint on a ready relay.
ACTIVE is not an upstream-health check. TCP/UDP endpoints are not browser URLs.


## PortalClient

```kotlin
class PortalClient(
    allowRemoteTargets: Boolean = false
)
// Android: PortalClient(context, allowRemoteTargets) — defaults
// identity_path to context.filesDir/identity.json
fun capabilities(): Set<Capability>
val events: SharedFlow<PortalEvent>      // aggregate across owned sessions
val sessions: StateFlow<List<PortalTunnel>>  // live sessions, open order
val isClosed: Boolean
suspend fun open(config: PortalConfig): PortalTunnel
suspend fun publish(config: PortalConfig, timeoutMillis: Long = 30_000): PortalTunnel
suspend fun close()
fun diagnostics(): PortalDiagnostics
```

Owner of all sessions it creates. `open` throws `PortalException`
(INVALID_CONFIG, UNSUPPORTED_CAPABILITY, NATIVE_UNAVAILABLE, INTERNAL_ERROR)
or `CancellationException`. `close` is idempotent and stops owned sessions.

`publish` is the ready-on-return path: it composes `open` + `awaitActive` and
stops the session if readiness fails or the call is cancelled. A cleanup
failure leaves the session in STOPPING (visible via `sessions`, retryable via
`tunnel.stop()`) and is reported with `operation="publish_cleanup"`.

## PortalTunnel

```kotlin
val tunnelId: String
val config: PortalConfig
val state: StateFlow<PortalSnapshot>
val events: SharedFlow<PortalEvent>
val isActive: Boolean
val name: String?     // native status name, else config.name
val address: String?  // native status address
val publicUrl: String? // first public URL from the latest snapshot
suspend fun awaitReady(capability: Capability, timeoutMillis: Long = 30_000): PortalSnapshot
suspend fun awaitActive(timeoutMillis: Long = 30_000): PortalSnapshot
suspend fun refresh(): PortalSnapshot
suspend fun addRelay(relayUrl: String)
suspend fun removeRelay(relayUrl: String)
suspend fun updateMetadata(metadata: PortalMetadata)
suspend fun stop()
```

`PortalClient.use { … }` (suspend extension) closes the client after the
block — the managed-lifetime pattern for JVM/desktop/common callers.

## PortalSnapshot

```kotlin
data class PortalSnapshot(
    sessionId: String, generation: Int, revision: Long,
    phase: TunnelPhase,                       // IDLE STARTING CONNECTING ACTIVE STOPPING STOPPED FAILED
    requestedCapabilities: Set<Capability>,
    readyCapabilities: Set<Capability>,       // confirmed per ready relay endpoint
    publicUrls: List<String>,                 // primaryPublicUrl = first
    relays: List<PortalRelayStatus>,          // isReady/isConnecting/isFailed
    lastFailure: PortalFailure?,
    hasSecurityWarning: Boolean,              // sticky MITM flag
    droppedEventCount: Long,
    nativeStatus: PortalStatus?
)
```

## PortalEvent (sealed)

`Started`, `Stopped`, `StatusChanged(status)`, `MitmSuspected(relayUrl)`,
`Error(message)`, `Unknown(type, rawPayload)` — all carry `tunnelId`.

## PortalConfig (wire v1, snake_case keys)

name, identityJson, identityPath, relays, discovery(true), maxActiveRelays(3),
banMitm, ech, overlay(unsupported), udp, tcp, description, tags, owner,
thumbnail, hide, staticDir, staticIndex, targetAddr, udpAddr,
httpRoutes(prefix, upstream|staticRoot, staticIndex, methods, amount),
x402(payTo, testnet, network, asset, endpoints, facilitatorToken).

The bundled portal-tunnel v2.7.1 uses relay protocol 10. `ech`, `x402`,
nonempty route `methods`, route `amount`, and `overlay` are recognized v1
fields but fail with `UNSUPPORTED_CAPABILITY` before native start. Consult
`client.capabilities()`; never imply these policies are applied.

`PortalConfig.Builder` mirrors portal-android-sdk setters.

Intent factories (companion), with exact Kotlin argument names/defaults:

```kotlin
PortalConfig.http(targetAddress: String, name: String? = null): PortalConfig
PortalConfig.routes(routes: List<PortalHTTPRoute>, name: String? = null): PortalConfig
PortalConfig.tcp(targetAddress: String, name: String? = null): PortalConfig
PortalConfig.udp(targetAddress: String, name: String? = null): PortalConfig
PortalConfig.staticSite(
    directory: String, index: String = "index.html", name: String? = null
): PortalConfig
```

Each sets only its mode's fields; TCP sets `tcp` plus `targetAddr`, UDP sets
`udp` plus `udpAddr`. All other fields retain wire defaults and use the same
validation. Routes choose the longest matching path prefix, not list order;
`/` can be a catch-all. Each route needs exactly one upstream or static root.

## PortalIdentity

```kotlin
PortalIdentity.generate(name = ""): PortalIdentity   // native call
PortalIdentity.parse(identityJson): PortalIdentity
identity.document / .jsonString  // secret — redacted in toString
identity.name / .address
```

## PortalFailure

```kotlin
data class PortalFailure(
    code: String, message: String, retryable: Boolean,
    operation: String?,       // "publish", "publish_cleanup", "identity_path", native op name…
    nativeCode: Int?,
    terminalPhase: TunnelPhase?,      // FAILED/STOPPED when the session ended first
    readinessFailure: PortalFailure?  // original readiness error on publish_cleanup
)
```

## PortalFailure.Codes

NATIVE_UNAVAILABLE, ABI_MISMATCH, INVALID_CONFIG, IDENTITY_INVALID,
RELAY_UNAVAILABLE, NETWORK_UNAVAILABLE, PERMISSION_DENIED,
UNSUPPORTED_CAPABILITY, PROTOCOL_ERROR, STOP_TIMEOUT, SECURITY_WARNING,
CLIENT_CLOSED, TUNNEL_CLOSED, INTERNAL_ERROR.

## PortalDiagnostics

```kotlin
data class PortalDiagnostics(
    sdkVersion: String, engineVersion: String,
    abiVersion: Int, wireSchemaVersion: Int,
    activeSessions: Int,
    droppedOrphanEvents: Long, droppedAggregateEvents: Long,
    sessions: List<SessionDiagnostics>  // sessionId, generation, phase,
                                      // revision, droppedEvents,
                                      // activeRelay, lastFailure
)
```

Never contains identity documents, keys, tokens, or request bodies.
## Desktop facade (desktopMain, JVM 17+)

```kotlin
object PortalDesktop {
    fun client(
        applicationId: String,
        storageDirectory: Path? = null,
        nativeLibraryPath: Path? = null,
        allowRemoteTargets: Boolean = false
    ): PortalClient
    fun runtime(nativeLibraryPath: Path? = null): PortalDesktopRuntime
}
data class PortalDesktopRuntime(
    os: DesktopOs, architecture: DesktopArchitecture,
    source: NativeLibrarySource,       // PACKAGED | OVERRIDE
    nativeLibraryPath: Path, nativeSha256: String
)
```

`PortalDesktop.client` loads the verified `libportaltunnel` from the
`portal-native-desktop` runtime JAR (linux-x64 / windows-x64 /
macos-universal) via JNA, extracting to a content-addressed cache
(`<cache>/portal-sdk/<version>/<sha256>/<file>`) under a file lock with an
atomic move. `applicationId` is validated eagerly (`INVALID_CONFIG`); the
identity directory is created lazily inside `open`/`publish` so filesystem
failures surface as `PERMISSION_DENIED`. Per-OS identity defaults:
`$XDG_STATE_HOME/<app>/portal` (Linux), `%LOCALAPPDATA%/<app>/Portal`
(Windows), `~/Library/Application Support/<app>/Portal` (macOS).
`storageDirectory`/`nativeLibraryPath` override the defaults.


## iOS facade (iosMain)

```kotlin
class PortalIosClient {
    constructor()
    constructor(allowRemoteTargets: Boolean = false, defaultIdentityPath: String? = null)
    val sessions: List<PortalIosSession> // owned snapshot, including failed STOPPING cleanup
    fun diagnostics(): PortalDiagnostics
    fun open(config, completion: (PortalIosSession?, PortalFailure?) -> Unit): PortalOperation
    fun publish(config, completion: (PortalIosSession?, PortalFailure?) -> Unit): PortalOperation
    fun publish(config, timeoutMillis, completion: (PortalIosSession?, PortalFailure?) -> Unit): PortalOperation
    fun close(completion: (PortalFailure?) -> Unit)
// PortalIosSession adds: refresh, addRelay, removeRelay, updateMetadata,
// awaitReady — all completion-based.
}
class PortalIosSession {
    val sessionId: String; val snapshot: PortalSnapshot
    val primaryPublicUrl: String?   // first public URL from the snapshot
    fun observeState(cb: (PortalSnapshot) -> Unit): PortalSubscription
    fun observeEvents(cb: (PortalEvent) -> Unit): PortalSubscription
    fun stop(completion: (PortalFailure?) -> Unit)
}
```

Completions fire once on the main dispatcher. `PortalOperation.cancel()`
cancels an in-flight open/publish; `PortalSubscription.cancel()` ends
observation only.
Failed publish/cancellation rollback remains reachable via `client.sessions`;
retry `session.stop()` rather than losing the native handle.

When a config sets neither `identityJson` nor `identityPath`, the client
resolves `Application Support/Portal/identity.json` at open/publish time
(filesystem failures surface as PERMISSION_DENIED through the completion).
`defaultIdentityPath` overrides that platform default.
Swift's normal path is `PortalIosClient()` followed by
`client.publish(config: PortalIosConfigFactory.shared.http(targetAddress:
"127.0.0.1:8080")) { session, failure in … }`. The no-timeout overload uses
30 seconds. `PortalIosConfigFactory.shared` exports these one-argument forms:
`http(targetAddress:)`, `tcp(targetAddress:)`, `udp(targetAddress:)`,
`routes(routes:)`, and `staticSite(directory:)`.
`httpRoute(prefix:upstream:)` and `staticRoute(prefix:directory:)` construct
route entries without the generated all-fields initializer.

Named forms are `http(targetAddress:name:)`, `tcp(targetAddress:name:)`,
`udp(targetAddress:name:)`, `routes(routes:name:)`; static's advanced form is
`staticSite(directory:index:name:)`. `.custom(...)` covers the advanced
config fields without a generated all-fields initializer or `KotlinBoolean`.
See `docs/SWIFT_API.md` for every exported facade signature, including the
explicit constructor and configurable-timeout publish overload.

## Android lifecycle (portal-android-lifecycle)

```kotlin
PortalClientHolder.init(context)                    // Application.onCreate
PortalClientHolder.open(config) { result -> … }     // accepted-before-ready
PortalClientHolder.publish(config, timeoutMillis) { result -> … }  // ACTIVE-on-return
PortalClientHolder.client / .isInitialized / .shutdown()

abstract class PortalTunnelService : Service()      // foreground owner
    .startTunnel(config) { result -> … } / .stopAllTunnels()
```
