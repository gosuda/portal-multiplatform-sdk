# iOS SDK workbench

A SwiftUI host for the real `PortalSDK.xcframework`, with five runnable SDK
modes. Every mode works without installing an upstream server when the built-in
demo is selected (the default). A reachable Portal relay is still required for
public endpoints; there is no simulated connection or relay fallback.
## The SDK path: create, then publish

After installing the local framework (see build instructions below), start
your own loopback HTTP server and use two SDK steps:

```swift
// 1. Keep one client in the application/model owner.
let client = PortalIosClient()
// 2. Publish one factory config.
_ = client.publish(
    config: PortalIosConfigFactory.shared.http(targetAddress: "127.0.0.1:8080")
) { session, failure in
    if let failure = failure {
        print("Publish failed: \(failure.code)")
        return
    }
    print(session?.primaryPublicUrl ?? "No HTTP URL")
}
```

Keep the server and client alive while serving. At owner teardown:

```swift
client.close { failure in
    if let failure = failure { print("Cleanup failed: \(failure.code)") }
}
```

The SDK handles identity persistence, waiting for ACTIVE (30 seconds by
default), and failed-publish rollback. Your app does not need an identity
file path, polling or its own rollback sequence. Failures arrive as
`PortalFailure` on Main; inspect `code`, `operation` and `retryable`.
Retain a session to stop one publication. Retry retained STOPPING cleanup
through `client.sessions` if native stop fails.

## Five runnable modes


| Mode | Built-in demonstration | Custom alternative |
| --- | --- | --- |
| Web games / static site | Engine serves the shared four-game arcade directly | None in this sample |
| HTTP API / webhook | Loopback API on an automatically assigned port; GET returns JSON, POST receives a webhook | Any running loopback HTTP service, `host:port` |
| HTTP prefix routes | `/api` → local API; `/hooks` → a second local webhook listener, with distinct JSON service labels | Two editable prefixes and loopback upstream URLs; longest prefix wins |
| TCP | Loopback byte echo on an automatically assigned port | A running loopback TCP service, `host:port` |
| UDP | Loopback datagram echo on an automatically assigned port | A running loopback UDP service, `host:port` |

Choose **Web games / static site**, publish and open the HTTPS URL on another
device while this app stays foregrounded. The [shared arcade](../../README.md#play-the-arcade)
contains Snake, memory pairs, reaction testing and sloshing water fill.
XcodeGen includes `../shared/common/site` as the `site` bundle folder, including
its CSS and JavaScript modules. No local game/HTTP server or external library is needed.


`Contents.swift` contains only the used loopback demo listeners. HTTP requests
have a 16 KiB total header/body limit, support Content-Length (not chunked
transfer or Expect), and close after one response. Bodies are counted and
discarded, not stored. TCP uses 4096-byte chunks; UDP echoes datagrams up to
4096 bytes. Each listener allows 16 concurrent connections/peers with a
10-second connection/peer lifetime. These intentionally bounded servers are
sample code, not general-purpose production servers.
Listener addresses are displayed after startup. Ephemeral ports avoid collisions
with other apps and stop/start port-reuse races.

The screen displays receive counts and the latest 40 method/path or echo-size
entries. It does not retain headers, query strings or bodies. Treat even URL
paths and public metadata as public; do not send credentials to the demo.
Static requests are served inside the engine and are not in this local log.
Custom services have their own logging and are not counted by the demo.

## Build and launch

From the repository root on macOS, with Xcode, Go, JDK and XcodeGen installed:
There is no published SDK release or remote SwiftPM package to resolve;
these commands build and package the local checkout.


```bash
./gradlew :portal-sdk:assemblePortalSDKReleaseXCFramework
./scripts/package-ios-xcframework.sh
xcodegen generate --spec samples/ios/project.yml
xcodebuild -project samples/ios/PortalSample.xcodeproj -scheme PortalSample \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  -derivedDataPath /tmp/portal-ios-workbench CODE_SIGNING_ALLOWED=NO build
xcrun simctl install booted /tmp/portal-ios-workbench/Build/Products/Debug-iphonesimulator/PortalSample.app
xcrun simctl launch booted org.gosuda.portal.sample
```

The XCFramework embeds the matching Go engine archives; the app links only
`dist/PortalSDK.xcframework`, never a manually located `libportaltunnel.a`.
Device builds require your signing team (`DEVELOPMENT_TEAM`). iOS 16+ is
required. Both `project.yml` and the generated project's source/resource
references reflect the workbench files.

## Smoke each capability

Keep the app foregrounded. Select a mode with **Use built-in loopback demo**
enabled, then publish and wait for ACTIVE. Copy its actual public URL or relay
TCP/UDP endpoint. Stop before changing modes. On a second machine or terminal:

```bash
# Static mode: expect the bundled page.
PUBLIC_URL='https://actual-public-address'
curl --fail "$PUBLIC_URL/"

# HTTP mode: expect JSON; POST increments the receive count without retaining its body.
curl --fail "$PUBLIC_URL/"
curl --fail -X POST "$PUBLIC_URL/hooks" -H 'Content-Type: application/json' \
  --data '{"event":"hello"}'

# Routes mode: expect service "api", then service "webhook".
curl --fail "$PUBLIC_URL/api"
curl --fail -X POST "$PUBLIC_URL/hooks" --data 'hello'

# TCP mode: use tcpAddr from the relay card, not the HTTPS public URL.
TCP_HOST='actual-relay-host'; TCP_PORT='actual-tcp-port'
printf 'hello\n' | nc -w 2 "$TCP_HOST" "$TCP_PORT"

# UDP mode: use udpAddr from the relay card; expect the same datagram.
UDP_HOST='actual-relay-host'; UDP_PORT='actual-udp-port'
printf 'hello\n' | nc -u -w 2 "$UDP_HOST" "$UDP_PORT"
```

On Simulator, loopback also reaches services on the Mac. On a physical device,
loopback means that device, not your development computer. Custom services must
already be running there; the SDK does not start or validate application health
for them. Relay discovery requires network access; turning it off requires at
least one explicit relay URL. Raw TCP/UDP availability depends on the relay's
reported endpoints and transport support.
ACTIVE requires all requested features: a ready public URL for HTTP/static
and each requested raw transport's nonblank endpoint on a ready relay. It is
not proof that an upstream request succeeded. Routing uses the longest matching
path prefix, never entered order.


## Ownership, cancellation and capabilities

- Intent factories cover HTTP, routes, TCP, UDP and static files.
  `PortalIosConfigFactory.shared.tcp(targetAddress:)` sets both `tcp` and
  `targetAddr`; `.udp(targetAddress:)` sets both `udp` and `udpAddr`. Named
  forms add `name:`; static's advanced form adds `index:name:`.
  The sample keeps custom config fields behind **Advanced settings**;
  `custom(...)` is not a required first step.
- The app-owned model publishes a config with the ready-on-return API. The SDK
  handles readiness failure and cancellation rollback. `open` followed by
  manual waiting is advanced accepted-before-ready behavior, not required
  sample integration code.
- The normal sample flow does not implement manual cancel-start bookkeeping.
  Failed rollback sessions remain reachable through `client.sessions`;
  cancelling an observation never stops the tunnel. For advanced integrations,
  cancelling an in-flight `PortalOperation` suppresses its completion but not
  SDK cleanup ownership; see the [Swift API reference](../../docs/SWIFT_API.md).
- Readiness failure stops the session. Failed stop keeps the session and its
  demo listeners in `STOPPING`; **Retry Stop** retries it. No subsequent start
  is allowed until cleanup is terminal. Listeners are released only on terminal
  state, or on local preparation failure before native ownership exists.
- State subscriptions stay with the app-owned model, independently of view
  navigation. Snapshot state is authoritative. Events are auxiliary, bounded,
  and not replayed (the screen keeps the latest 50). Cancelling observation does
  not stop a session.
- Public URLs have copy/share/open actions; raw relay TCP/UDP endpoints have
  copy actions. Terminal public URLs are hidden. Live refresh, metadata edits,
  relay add/remove, sticky security warnings and secret-free SDK/engine
  diagnostics are available.
- SDK-managed identity persists under Application Support/Portal/identity.json.
  Changing a display name never generates or overwrites identity material.
- iOS sessions are foreground-only in practical lifetime: backgrounding or
  locking can suspend networking. The sample does not promise background
  execution or immediate shutdown on backgrounding. Explicitly stop when done.
- Hide is a listing preference, not access control. MITM banning requests a
  relay-dependent self-probe, not blanket anonymity. The bundled
  v2.7.1/protocol-10 engine rejects ECH, x402/priced routes,
  method-constrained routes and overlay with `UNSUPPORTED_CAPABILITY`
  rather than silently ignoring them.
