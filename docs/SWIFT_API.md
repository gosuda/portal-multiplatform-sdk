# Swift API Reference — PortalSDK

Reviewed against the generated `PortalSDK.h` umbrella header of the real
`PortalSDK.xcframework` (Kotlin/Native export, ABI v1). Names below are the
exact `swift_name` spellings a Swift caller sees; Kotlin-only machinery
(`PSDKBase`, `Kotlin*`, serializers, `doCopy`) is omitted.

## PortalIosClient

```swift
class PortalIosClient {
    init(allowRemoteTargets: Bool, defaultIdentityPath: String?)
    var isClosed: Bool { get }
    func capabilities() -> Set<Capability>
    func diagnostics() -> PortalDiagnostics
    func open(config: PortalConfig,
              completion: (PortalIosSession?, PortalFailure?) -> Void) -> PortalOperation
    func publish(config: PortalConfig, timeoutMillis: Int64,
                 completion: (PortalIosSession?, PortalFailure?) -> Void) -> PortalOperation
    func close(completion: (PortalFailure?) -> Void)
}
```

Completions fire exactly once on the main dispatcher. `PortalOperation`
has one method: `cancel()` — it cancels the in-flight open/publish and the
completion never fires. `close` stops only sessions this client opened.

## PortalIosSession

```swift
class PortalIosSession {
    var sessionId: String { get }
    var snapshot: PortalSnapshot { get }        // authoritative current state
    var primaryPublicUrl: String? { get }       // first public URL
    var name: String? { get }
    var address: String? { get }
    var isActive: Bool { get }

    func observeState(callback: (PortalSnapshot) -> Void) -> PortalSubscription
    func observeEvents(callback: (PortalEvent) -> Void) -> PortalSubscription
    func stop(completion: (PortalFailure?) -> Void)
    func refresh(completion: (PortalFailure?) -> Void)
    func addRelay(relayUrl: String, completion: (PortalFailure?) -> Void)
    func removeRelay(relayUrl: String, completion: (PortalFailure?) -> Void)
    func updateMetadata(metadata: PortalMetadata, completion: (PortalFailure?) -> Void)
    func awaitReady(capability: Capability, timeoutMillis: Int64,
                    completion: (PortalSnapshot?, PortalFailure?) -> Void)
    func awaitActive(timeoutMillis: Int64,
                     completion: (PortalSnapshot?, PortalFailure?) -> Void)
}
```

`PortalSubscription.cancel()` ends observation only — it never stops the
tunnel.

## PortalIosConfigFactory

```swift
PortalIosConfigFactory.shared.http(targetAddress: String, name: String?) -> PortalConfig
PortalIosConfigFactory.shared.routes(routes: [PortalHTTPRoute], name: String?) -> PortalConfig
PortalIosConfigFactory.shared.tcp(name: String?) -> PortalConfig
PortalIosConfigFactory.shared.udp(targetAddress: String, name: String?) -> PortalConfig
PortalIosConfigFactory.shared.staticSite(directory: String, index: String, name: String?) -> PortalConfig

// Advanced escape hatch — every PortalConfig field, plain Bool/String?
// parameters, no KotlinBoolean and no generated all-fields initializer:
PortalIosConfigFactory.shared.custom(
    name: String?, identityJson: String?, identityPath: String?,
    relays: [String]?, discovery: Bool, maxActiveRelays: Int32,
    banMitm: Bool, ech: Bool, udp: Bool, tcp: Bool,
    description: String?, tags: [String]?, owner: String?,
    thumbnail: String?, hide: Bool, staticDir: String?,
    staticIndex: String?, targetAddr: String?, udpAddr: String?,
    httpRoutes: [PortalHTTPRoute]?, x402: PortalX402Config?
) -> PortalConfig
```

## PortalFailure

```swift
class PortalFailure {
    var code: String { get }              // PortalFailure.Codes.shared.*
    var message: String { get }
    var retryable: Bool { get }
    var operation: String? { get }        // "publish", "publish_cleanup", "identity_path", …
    var nativeCode: KotlinInt? { get }
    var terminalPhase: TunnelPhase? { get }
    var readinessFailure: PortalFailure? { get }
}
PortalFailure.Codes.shared.STOP_TIMEOUT / .TUNNEL_CLOSED / .INVALID_CONFIG / …
```

## PortalSnapshot / PortalStatus / PortalRelayStatus

```swift
class PortalSnapshot {
    var sessionId: String { get }
    var phase: TunnelPhase { get }        // .idle .starting .connecting .active .stopping .stopped .failed
    var publicUrls: [String] { get }
    var primaryPublicUrl: String? { get }
    var relays: [PortalRelayStatus] { get }
    var lastFailure: PortalFailure? { get }
    var hasSecurityWarning: Bool { get }
    var isActive: Bool { get }
    var isTerminal: Bool { get }
    var droppedEventCount: Int64 { get }
}
class PortalRelayStatus {
    var relayUrl: String { get }
    var publicUrl: String? { get }
    var udpAddr: String? { get }
    var tcpAddr: String? { get }
    var state: String { get }             // "ready" "connecting" "failed" …
    var failure: String? { get }
    var error: String? { get }
    var isReady: Bool { get }
    var isMitm: Bool { get }
    var isActive: Bool { get }
}
```

## PortalEvent (sealed)

`PortalEvent.Started(name)`, `.Stopped`, `.StatusChanged(status)`,
`.MitmSuspected(relayUrl)`, `.RelayAdded(relayUrl)`, `.RelayRemoved(relayUrl)`,
`.Error(message)`, `.Unknown(type, rawPayload)` — all carry `tunnelId`.

## PortalIdentity

```swift
try PortalIdentity.companion.generate(name: String) -> PortalIdentity   // throws
try PortalIdentity.companion.parse(identityJson: String) -> PortalIdentity
identity.document   // secret — redacted from description, never log it
identity.name / .address / .jsonString
```

## PortalDiagnostics

```swift
class PortalDiagnostics {
    var sdkVersion: String { get }
    var engineVersion: String { get }     // portal-tunnel core version
    var abiVersion: Int32 { get }
    var wireSchemaVersion: Int32 { get }
    var activeSessions: Int32 { get }
    var droppedOrphanEvents: Int64 { get }
    var droppedAggregateEvents: Int64 { get }
    var sessions: [SessionDiagnostics] { get }
    // SessionDiagnostics: sessionId, generation, phase, revision,
    //                     droppedEvents, activeRelay, lastFailure
}
```

Never contains identity documents, keys, tokens, or request bodies.

## PortalConfig / PortalHTTPRoute / PortalMetadata / PortalX402Config

Plain data classes; every field is a readable property. Prefer
`PortalIosConfigFactory` over the all-fields `PortalConfig` initializer —
it avoids `KotlinBoolean` and keeps the call site short. `PortalMetadata`
still takes `KotlinBoolean(bool:)` for its optional `hide` field.

## Capability / TunnelPhase

`Capability.httpTls .staticSite .tcp .udp .ech .discovery .x402 .overlay`
(`.overlay` is unsupported in v1 → `UNSUPPORTED_CAPABILITY`).
`TunnelPhase.idle .starting .connecting .active .stopping .stopped .failed`.
