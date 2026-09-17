# API Reference — org.gosuda.portal

## PortalClient

```kotlin
class PortalClient(
    allowInsecureLocalRelays: Boolean = false,
    allowRemoteTargets: Boolean = false
)
fun capabilities(): Set<Capability>
suspend fun open(config: PortalConfig): PortalTunnel
suspend fun close()
fun diagnostics(): PortalDiagnostics
```

Owner of all sessions it creates. `open` throws `PortalException`
(INVALID_CONFIG, UNSUPPORTED_CAPABILITY, NATIVE_UNAVAILABLE, INTERNAL_ERROR)
or `CancellationException`. `close` is idempotent and stops owned sessions.

## PortalTunnel

```kotlin
val tunnelId: String
val config: PortalConfig
val state: StateFlow<PortalSnapshot>
val events: SharedFlow<PortalEvent>
suspend fun awaitReady(capability: Capability, timeoutMillis: Long = 30_000): PortalSnapshot
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
class PortalIosClient(allowInsecureLocalRelays, allowRemoteTargets) {
    fun open(config, completion: (PortalIosSession?, PortalFailure?) -> Unit): PortalOperation
    fun close(completion: (PortalFailure?) -> Unit)
}
class PortalIosSession {
    val sessionId: String; val snapshot: PortalSnapshot
    fun observeState(cb: (PortalSnapshot) -> Unit): PortalSubscription
    fun observeEvents(cb: (PortalEvent) -> Unit): PortalSubscription
    fun stop(completion: (PortalFailure?) -> Unit)
}
```

Completions fire once on the main dispatcher. `PortalOperation.cancel()`
cancels an in-flight open; `PortalSubscription.cancel()` ends observation
only.
