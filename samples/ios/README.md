# iOS SDK workbench

A black-and-white SwiftUI host for the real `PortalSDK.xcframework`, with all
five transport demonstrations plus device-local LLM inference. Transport demos
need no installed upstream when the built-in demo is selected (the default).
Local LLM requires an explicit, verified model download first. A reachable
Portal relay is required for public endpoints; there is no simulated connection,
cloud inference or external inference-daemon fallback.
## The SDK path: create, then publish

After installing the local framework (see build instructions below), start
your own loopback HTTP server and use two SDK steps:

```swift
// 1. Keep one client in the application/model owner.
let client = PortalIosClient()
// 2. Publish a factory config with an optional domain prefix.
_ = client.publish(
    config: PortalIosConfigFactory.shared.http(targetAddress: "127.0.0.1:8080")
        .withDomainPrefix(prefix: "ios-demo")
) { session, failure in
    if let failure = failure {
        print("Publish failed: \(failure.code)")
        return
    }
    print(session?.primaryPublicUrl ?? "No HTTP URL")
}
```

The always-visible **Domain prefix** field defaults to `ios-demo` and works
without enabling **Advanced settings**. Clear it to use the stored identity's
name. Prefixes are normalized to lowercase and must be 1–63 ASCII letters,
digits or hyphens, with no leading/trailing hyphen or reserved 40-hex canonical
identity shape. Changing the prefix never replaces the persisted identity or keys.

A prefix requests a short, lease-scoped alias; it does not reserve the name
permanently. If another identity owns the prefix, the primary URL falls back to
the canonical identity-bound URL. The live session's display/copy/share/open
actions and HTTP exercise commands use `snapshot.primaryPublicUrl`: an actual
relay-granted alias only while ACTIVE, otherwise the canonical fallback.
Canonical URLs, when different, are labelled secondary identity-bound details.
Re-read the live snapshot after reconnecting; never reuse an old alias as the
current address. Stopping hides primary actions, and terminal URLs are hidden.
Raw TCP/UDP endpoints are unchanged.

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

## Five transport demonstrations + local LLM


| Mode | Built-in demonstration | Custom alternative |
| --- | --- | --- |
| Web games / static site | Engine serves the shared four-game arcade directly | None in this sample |
| HTTP API / webhook | Loopback API on an automatically assigned port; GET returns JSON, POST receives a webhook | Any running loopback HTTP service, `host:port` |
| HTTP prefix routes | `/api` → local API; `/hooks` → a second local webhook listener, with distinct JSON service labels | Two editable prefixes and loopback upstream URLs; longest prefix wins |
| TCP | Loopback byte echo on an automatically assigned port | A running loopback TCP service, `host:port` |
| UDP | Loopback datagram echo on an automatically assigned port | A running loopback UDP service, `host:port` |
| Local LLM / private webpage | Native CPU inference using an explicitly downloaded, verified catalog model | No cloud or external daemon |

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

## Device-local LLM

Choose **Local LLM / private webpage**. The model library reads the shared
`samples/shared/common/models.json` catalog: size, quantization, languages,
license and source are shown for each curated model. Small models are listed
first; CPU speed and memory requirements depend on the device, not a promised
benchmark.

1. Select a model and explicitly tap **Download**. Selection, launch and Publish
   never start a download. Progress and cancellation are visible.
2. The download must match the pinned exact byte count and SHA-256 before it
   is atomically installed under Application Support/PortalSample/models.
   Failed/cancelled transfers do not replace completed cache files. Weights are
   excluded from device backups and are not bundled in the app.
3. In **LOCAL LLM / PASSWORD**, keep the default cryptographically generated
   password, tap **Generate password**, or enter your own 12–128 Unicode code
   points. Generated passwords are recommended. The app keeps the chosen
   password only in its app-owned memory and reuses it until you change it;
   relaunching creates a new default without deleting cached models.
4. Tap **Publish to the web** after the model is cached. Loading and generation
   run off Main using the sample-only `PortalSampleLlm` C ABI. Both simple and
   advanced settings use the chosen password. Editing and regeneration are
   disabled while loading, publishing, active or stopping. Delete is disabled
   while a session owns a model.
5. Use the normal public URL **Copy**, **Share** or **Open** actions, then enter
   the password separately on the webpage. **Copy password** copies only the
   password to this device's clipboard with a two-minute expiration. Neither
   the URL nor SDK configuration, metadata or logs contain the password.
   Sharing the URL alone does not grant inference; share the password only
   with trusted people. The webpage keeps it only in page memory.
6. Keep the app foregrounded. Stop when finished. Session cleanup retains the
   listener/model on SDK Stop failure so **Retry Stop** remains meaningful;
   terminal cleanup cancels native inference before destroying its handle.

The loopback-only HTTP server exposes only the three shared webpage assets,
authenticated `/api/status`, and authenticated `/api/generate`. It supports one
independent single-turn generation at a time (409 for concurrent generation),
1024 context tokens, at most 128 generated tokens and a 16 KiB JSON body.
Connections are capped at 16 with bounded headers and a 180-second connection
lifetime. Prompts, outputs, passwords and authorization headers are not logged.
The catalog and cache directory are never served as a filesystem root.
The native model applies its own chat template; context-budget failures return
400 rather than truncating the prompt. Backend details stay in the native UI,
not the public HTTP error response. Background execution is not guaranteed.

API authentication is HTTP Basic with fixed username `portal` and the exact
UTF-8 password: `Authorization: Basic base64(UTF8("portal:" + password))`.
The server compares the complete expected header in constant time and returns
401 for missing or incorrect credentials, without a `WWW-Authenticate` browser
login challenge. Public access uses the Portal HTTPS URL; plain HTTP is only
used by the loopback listener. For example (replace the placeholders):

```sh
curl -u 'portal:YOUR_PASSWORD' 'https://PUBLIC_HOST/api/status'
curl -u 'portal:YOUR_PASSWORD' 'https://PUBLIC_HOST/api/generate' \
  -H 'Content-Type: application/json' \
  --data '{"prompt":"Say hello briefly.","maxTokens":32}'
```

After relaunch, select the same cached model and publish again to verify cache
reuse. Open the public URL from another device, enter the password and request a short answer;
verify real generated text, then Stop during generation and verify a clean
restart. No model should be downloaded again unless explicitly requested after
deletion or cache loss.

## Build and launch

From the repository root on macOS, with Xcode, Go, JDK and XcodeGen installed:
There is no published SDK release or remote SwiftPM package to resolve;
these commands build and package the local checkout.


```bash
./gradlew :portal-sdk:assemblePortalSDKReleaseXCFramework
./scripts/package-ios-xcframework.sh
./scripts/build-sample-llm.sh ios
xcodegen generate --spec samples/ios/project.yml
xcodebuild -project samples/ios/PortalSample.xcodeproj -scheme PortalSample \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  -derivedDataPath /tmp/portal-ios-workbench CODE_SIGNING_ALLOWED=NO build
xcrun simctl install booted /tmp/portal-ios-workbench/Build/Products/Debug-iphonesimulator/PortalSample.app
xcrun simctl launch booted org.gosuda.portal.sample
```

The SDK XCFramework embeds the matching Go engine archives; never link a
manually located `libportaltunnel.a`. Separately, the sample links the static
CPU-only `build/sample-llm/ios/PortalSampleLlm.xcframework`; inference code is not
an SDK dependency. The build script builds pinned llama.cpp for device and
Simulator and exports the `PortalSampleLlm` C module. Device builds require your
signing team (`DEVELOPMENT_TEAM`). iOS 16+ is required. Regenerate the Xcode
project from `project.yml` after source/resource/dependency changes; the spec
includes shared `site`, `llm` and `models.json` resources but never model weights.

## Smoke each capability

Keep the app foregrounded. Select a mode with **Use built-in loopback demo**
enabled, choose **Domain prefix**, then publish and wait for ACTIVE. Copy the
live session's primary public URL (`snapshot.primaryPublicUrl`) or relay TCP/UDP
endpoint. Stop before changing modes. On a second machine or terminal:

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
  Domain prefixes use `.withDomainPrefix(prefix:)` on any factory config.
  Other custom config fields stay behind **Advanced settings**;
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
- The primary URL has copy/share/open actions and HTTP exercise commands;
  raw relay TCP/UDP endpoints have copy actions. Alias selection follows the
  current snapshot, not a cached requested prefix. Stopping hides primary
  actions; terminal public URLs are hidden. Live refresh, metadata edits,
  relay add/remove, sticky security warnings and secret-free SDK/engine
  diagnostics are available.
- SDK-managed identity persists under Application Support/Portal/identity.json.
  Changing the domain prefix never generates or overwrites identity material.
- iOS sessions are foreground-only in practical lifetime: backgrounding or
  locking can suspend networking. The sample does not promise background
  execution or immediate shutdown on backgrounding. Explicitly stop when done.
- Hide is a listing preference, not access control. MITM banning requests a
  relay-dependent self-probe, not blanket anonymity. The bundled
  v2.7.1/protocol-10 engine rejects ECH, x402/priced routes,
  method-constrained routes and overlay with `UNSUPPORTED_CAPABILITY`
  rather than silently ignoring them.
