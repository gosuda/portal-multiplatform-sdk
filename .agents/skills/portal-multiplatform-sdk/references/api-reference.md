# API Reference — org.gosuda.portal

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
suspend fun awaitReady(capability: Capability, timeoutMillis: Long = 30_000): PortalSnapshot
suspend fun awaitActive(timeoutMillis: Long = 30_000): PortalSnapshot
suspend fun refresh(): PortalSnapshot
suspend fun addRelay(relayUrl: String)
suspend fun removeRelay(relayUrl: String)
suspend fun updateMetadata(metadata: PortalMetadata)
suspend fun stop()
```

## PortalSnapshot

```kotlin
data class PortalSnapshot(
    sessionId: String, generation: Int, revision: Long,
    phase: TunnelPhase,                       // IDLE STARTING CONNECTING ACTIVE STOPPING STOPPED FAILED
    requestedCapabilities: Set<Capability>,
    readyCapabilities: Set<Capability>,       // requested ∩ confirmed-active
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

`PortalConfig.Builder` mirrors portal-android-sdk setters.

Intent factories (companion): `PortalConfig.http(targetAddr, name)`,
`.routes(routes, name)`, `.tcp(name)`, `.udp(addr, name)`,
`.staticSite(dir, index, name)` — each sets only its mode's fields; all other
fields keep wire defaults and flow through the same validation.

## PortalIdentity

```kotlin
PortalIdentity.generate(name = ""): PortalIdentity   // native call
PortalIdentity.parse(identityJson): PortalIdentity
identity.document / .jsonString  // secret — redacted in toString
identity.name / .address
```

## PortalFailure.Codes

NATIVE_UNAVAILABLE, ABI_MISMATCH, INVALID_CONFIG, IDENTITY_INVALID,
RELAY_UNAVAILABLE, NETWORK_UNAVAILABLE, PERMISSION_DENIED,
UNSUPPORTED_CAPABILITY, PROTOCOL_ERROR, STOP_TIMEOUT, SECURITY_WARNING,
CLIENT_CLOSED, TUNNEL_CLOSED, INTERNAL_ERROR.

## iOS facade (iosMain)

```kotlin
class PortalIosClient(allowRemoteTargets, defaultIdentityPath) {
    fun diagnostics(): PortalDiagnostics
    fun open(config, completion: (PortalIosSession?, PortalFailure?) -> Unit): PortalOperation
    fun publish(config, timeoutMillis, completion: (PortalIosSession?, PortalFailure?) -> Unit): PortalOperation
    fun close(completion: (PortalFailure?) -> Unit)
// PortalIosSession adds: refresh, addRelay, removeRelay, updateMetadata,
// awaitReady — all completion-based.
}
class PortalIosSession {
    val sessionId: String; val snapshot: PortalSnapshot
    fun observeState(cb: (PortalSnapshot) -> Unit): PortalSubscription
    fun observeEvents(cb: (PortalEvent) -> Unit): PortalSubscription
    fun stop(completion: (PortalFailure?) -> Unit)
}
```

Completions fire once on the main dispatcher. `PortalOperation.cancel()`
cancels an in-flight open/publish; `PortalSubscription.cancel()` ends
observation only.

When a config sets neither `identityJson` nor `identityPath`, the client
resolves `Application Support/Portal/identity.json` at open/publish time
(filesystem failures surface as PERMISSION_DENIED through the completion).
`defaultIdentityPath` overrides that platform default.

`PortalIosConfigFactory` gives Swift callers the intent factories with a
stable spelling: `PortalIosConfigFactory.shared.http(targetAddress:name:)`,
`.routes`, `.tcp`, `.udp`, `.staticSite`.
