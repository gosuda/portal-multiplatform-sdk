# Swift API Reference — PortalSDK

Swift-facing API for the Kotlin/Native `PortalSDK` framework (C ABI v1).
Signatures below use Swift labels; Kotlin-only machinery (`PSDKBase`,
serializers, `doCopy`) is omitted.

## Start with two steps

Install the locally packaged framework first: no public SDK release or remote
SwiftPM package is available yet. Follow the
[local package instructions](../README.md#install), or build the
[iOS workbench](../samples/ios/README.md). The XCFramework includes the native
engine; do not build or manually link `libportaltunnel.a`.

Start an app-owned HTTP server on loopback, then:

```swift
// 1. Keep one client in your application/model owner.
let client = PortalIosClient()

// 2. Publish a factory config; the completion receives an ACTIVE session.
let operation = client.publish(
    config: PortalIosConfigFactory.shared.http(targetAddress: "127.0.0.1:8080")
) { session, failure in
    if let failure = failure {
        print("Publish failed: \(failure.code)")
        return
    }
    print(session?.primaryPublicUrl ?? "No HTTP URL")
}
```

The ordinary call uses a 30-second readiness timeout. Identity persistence,
readiness waiting and failed-start rollback belong to the SDK: no app-managed
identity path, polling loop or rollback sequence is needed. Your app owns its
server and the client lifetime. The workbench supplies loopback demo servers;
the SDK does not.

Keep the server and client alive while serving. Retain the returned session
if you need to stop only that tunnel. At owner teardown:

```swift
client.close { failure in
    if let failure = failure { print("Cleanup failed: \(failure.code)") }
}
```

Errors are structured `PortalFailure` values, not thrown Swift errors for
these callback operations. Inspect `code`, `operation` and `retryable`.
If cleanup fails, the STOPPING session remains in `client.sessions`; keep
its upstream alive and retry stop/close. iOS offers foreground sessions, not
indefinite background execution.

ACTIVE means every requested feature is ready, not that an upstream request
succeeded. HTTP/static need a ready public URL; TCP/UDP each need their own
nonblank endpoint on a ready relay. `primaryPublicUrl` is a browser URL;
`snapshot.relays[].tcpAddr` and `.udpAddr` are raw protocol endpoints.

The remaining sections cover optional names, explicit relays, custom timeout,
manual observation and advanced identity/constructor control.

## PortalIosClient

```swift
class PortalIosClient {
    init()
    init(allowRemoteTargets: Bool, defaultIdentityPath: String?)
    var isClosed: Bool { get }
    var sessions: [PortalIosSession] { get }    // currently owned, in open order
    func capabilities() -> Set<Capability>
    func diagnostics() -> PortalDiagnostics
    func open(config: PortalConfig,
              completion: (PortalIosSession?, PortalFailure?) -> Void) -> PortalOperation
    func publish(config: PortalConfig,
                 completion: (PortalIosSession?, PortalFailure?) -> Void) -> PortalOperation
    func publish(config: PortalConfig, timeoutMillis: Int64,
                 completion: (PortalIosSession?, PortalFailure?) -> Void) -> PortalOperation
    func close(completion: (PortalFailure?) -> Void)
}
```

Completions fire exactly once on the main dispatcher. `PortalOperation`
has one method: `cancel()` — it cancels the in-flight open/publish and the
completion never fires. `close` stops only sessions this client opened.

`sessions` is a point-in-time ownership snapshot, not an observation stream.
If publish/cancellation rollback cannot stop the native session, its STOPPING
handle remains here. Retry `session.stop`; cancelling a completion or an
observer never discards ownership. Calling `close` rejects new sessions and
can be retried if native cleanup fails.
Use `init()` for platform-managed persistent identity in
`Application Support/Portal/identity.json`. The explicit constructor is for
opting into remote targets or overriding the default identity path; a config's
explicit identity takes precedence. `open` is an advanced accepted-before-ready
operation: observe state or call `awaitActive` yourself only when that behavior
is intentional.


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
// Minimal factories: one argument each, SDK defaults for everything else.
PortalIosConfigFactory.shared.http(targetAddress: String) -> PortalConfig
PortalIosConfigFactory.shared.routes(routes: [PortalHTTPRoute]) -> PortalConfig
PortalIosConfigFactory.shared.tcp(targetAddress: String) -> PortalConfig
PortalIosConfigFactory.shared.udp(targetAddress: String) -> PortalConfig
PortalIosConfigFactory.shared.staticSite(directory: String) -> PortalConfig

// Route entries without the generated all-fields initializer.
PortalIosConfigFactory.shared.httpRoute(prefix: String, upstream: String) -> PortalHTTPRoute
PortalIosConfigFactory.shared.staticRoute(prefix: String, directory: String) -> PortalHTTPRoute

// Optional display names and the explicit static index.
PortalIosConfigFactory.shared.http(targetAddress: String, name: String?) -> PortalConfig
PortalIosConfigFactory.shared.routes(routes: [PortalHTTPRoute], name: String?) -> PortalConfig
PortalIosConfigFactory.shared.tcp(targetAddress: String, name: String?) -> PortalConfig
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
TCP sets both `tcp` and `targetAddr`; UDP sets both `udp` and `udpAddr`.
Routes use the **longest matching path prefix**, never input order, with `/`
as a possible fallback. Each route needs an upstream or a static root, not both.

Names are display/listing metadata, not a request to regenerate persisted keys.
Defaults enable relay discovery; explicit relay control is an advanced
`custom(...)` configuration. `discovery = false` requires at least one relay;
HTTPS relays and loopback targets are the default security boundary.
`hide` affects listings, not access control. The bundled portal-tunnel
v2.7.1/protocol-10 engine supports HTTP, static files, prefix routes, TCP, UDP
and discovery; ECH, x402/priced routes, method-constrained routes and overlay
fail with `UNSUPPORTED_CAPABILITY` before native start.


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
    var generation: Int32 { get }
    var revision: Int64 { get }
    var phase: TunnelPhase { get }        // .idle .starting .connecting .active .stopping .stopped .failed
    var requestedCapabilities: Set<Capability> { get }
    var readyCapabilities: Set<Capability> { get }
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
(`.ech`, `.x402` and `.overlay` are unsupported by the bundled v2.7.1 engine
→ `UNSUPPORTED_CAPABILITY`; priced/method-constrained routes are rejected too).
`TunnelPhase.idle .starting .connecting .active .stopping .stopped .failed`.
