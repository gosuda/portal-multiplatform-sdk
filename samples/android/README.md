# Android SDK workbench (Compose)

A small app that publishes app-local services through Portal relays. Five
built-in demos supply real loopback servers or static files; the SDK owns
their tunnels. The sample consumes staged Maven artifacts, not SDK `project()`
dependencies. The checked-in SDK version is not published to Maven Central.

## Build and install

Stage the unpublished artifacts, then build and install from the repository root:

```bash
./gradlew publishAndroidSdkToSampleRepository
./gradlew :samples:android:assembleDebug :samples:android:assembleRelease \
  -Pportal.samples.repository="$PWD/build/sample-maven"
adb install -r samples/android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n org.gosuda.portal.sample/.MainActivity
```

The installed Android SDK must support API 36; minSdk is 26. Shipped native ABIs
are arm64-v8a and x86_64. No game engine, model runtime or model download is used.
## The SDK path: create, then publish

With your own HTTP server already listening on the device:

```kotlin
// 1. Create once in an app/long-lived owner, not on every screen redraw.
val client = PortalClient(applicationContext)
// 2. From a coroutine, publish one factory config.
val tunnel = client.publish(PortalConfig.http("127.0.0.1:8080"))
println(tunnel.publicUrl)
```

Keep the server and client alive while serving. At tunnel teardown call
`tunnel.stop()`; at owner teardown call `client.close()`. Catch
`PortalException` and inspect `failure.code`, `operation`, and `retryable`;
let coroutine cancellation propagate. The SDK persists identity, waits for
ACTIVE (30 seconds by default), and rolls back failed publish: the normal
path needs no app-supplied identity path, readiness polling or rollback code.
Failed native cleanup remains owned in STOPPING for stop/close retry.

The sample uses the process-owned `PortalClientHolder` form of this same
publish operation so Activity rotation cannot discard ownership.


## Five runnable modes

Choose a mode on **Publish**, then publish. Relay discovery is enabled by default;
you can instead supply explicit HTTPS relay URLs. Internet access and a compatible
reachable Portal relay are needed for public forwarding. The built-in listeners
start automatically and bind only to `127.0.0.1` on ephemeral ports.

| Mode | SDK configuration | Built-in demonstration | Custom mode |
|---|---|---|---|
| Web games / static site | `PortalConfig.staticSite` | Shared four-game arcade copied to app-private files; no local HTTP server | Not needed |
| HTTP upstream | `PortalConfig.http` | JSON API and webhook receiver | Loopback host:port |
| HTTP prefix routes | `PortalConfig.routes` | `/api` selects the JSON API; `/` selects a second webhook receiver | Two prefix/upstream URL pairs |
| TCP forward | `PortalConfig.tcp(targetAddress)` | Raw TCP echo listener | Loopback host:port |
| UDP forward | `PortalConfig.udp` | Datagram echo listener | Loopback host:port |

For games, choose **Web games / static site**, publish and open the HTTPS URL.
Snake, memory pairs, reaction testing and sloshing water fill share the
[arcade source and controls](../../README.md#play-the-arcade). All flat bundle
files are copied from packaged assets; no model, game engine or backend is installed.
For hosting while browsing on this same phone, explicitly opt into the
foreground service before publishing; Android background limits still apply.


Disable **Use built-in loopback demo** for your own upstream. Start that service
on the Android device first: a desktop's localhost is not Android localhost.
The sample does not manage user-owned services and never enables remote targets.
The longest matching path prefix wins, not the order entered; `/` is the
catch-all fallback.

### HTTP demo

- `GET /api` returns JSON identifying the sample and its request count.
- `POST /webhook` returns `202` and `{ "received": true, "bodyStored": false }`.
- Other paths return a small explanatory HTML page.
- The intentionally small HTTP/1.1 receiver closes each connection. Header input
  is limited to 8 KiB, body input to 16 KiB with Content-Length; chunked requests
  are rejected. Connections have a 3-second read timeout. This is a demonstration
  endpoint, not a production HTTP server.
- The bounded activity log retains only known route labels, method, and byte count;
  never request bodies, headers, arbitrary path segments, or query values.

### Raw protocol demos

TCP echoes at most 64 KiB per connection using a 4 KiB buffer and a 3-second idle
timeout. UDP echoes datagrams up to 4 KiB (larger datagrams are truncated). The
listeners handle requests serially; they are deliberately not load generators.
Use the relay's **TCP** or **UDP** address in Session, not an HTTPS public URL.
Relay support for each raw protocol is required.
ACTIVE covers every requested feature: HTTP/static need a ready public URL,
and TCP/UDP each need a nonblank endpoint on a ready relay. It does not prove
the upstream application handled a request; use the real probes below.


## Smoke mechanism

There is no fake relay or no-op readiness path. Use a real relay and exercise each
mode from another device after the snapshot reports readiness. The Session tab
shows copyable public addresses, exact commands with the current endpoint, and
received request/echo activity. Replace example endpoints below with those values:

```bash
# Static site: open the HTTPS URL to play; modules must be served as JavaScript.
curl -fsS 'https://<public-host>/'
curl -I 'https://<public-host>/arcade.js'

# HTTP and routes: run both; /api must be JSON and /webhook must acknowledge.
curl -fsS 'https://<public-host>/api'
curl -i -X POST 'https://<public-host>/webhook' \
  -H 'Content-Type: text/plain' --data 'hello portal'

# Raw echo: expect the exact sent message back, not a synthetic status page.
printf 'hello portal\n' | nc <relay-host> <tcp-port>
printf 'hello portal\n' | nc -u -w 2 <relay-host> <udp-port>
```

Then refresh, await active, update metadata, add/remove an explicit relay, inspect
diagnostics, rotate the Activity, and stop. The stop action becomes **Retry stop**
if the native stop fails. Failed `publish` rollback recovers the still-owned
`client.sessions` handle in STOPPING; its built-in listener and static files remain
available until actual stop. Starting a replacement while it is live is disabled.

## Ownership, identity and capability boundaries

- `SampleApp` owns the process-scoped `PortalClientHolder`, session, bounded SDK
  event history, and demo listener. The Activity only observes and forwards UI
  actions. Rotation never stops the tunnel. Nothing uses a global stop operation.
- Identity is the SDK's persistent Android default (`filesDir/identity.json`).
  Display-name edits do not generate or overwrite identity. App uninstall/data
  clearing removes that identity; editing a name need not change the hostname.
- Foreground service execution is explicitly optional and off by default. Enable
  it while the app is visible for notification-backed execution. Notification
  denial does not create an indefinite background guarantee. Android may stop
  the process and imposes data-sync service time limits; the app requests a stop
  when its foreground-service timeout fires. A native stop failure stays owned
  and retryable in the app, even if Android requires the service to exit.
- State is authoritative; event history is auxiliary and bounded (80 SDK events,
  40 demo entries). Typed failures show code, operation, retryability, terminal
  phase, and original readiness cause for failed publish cleanup.
- Public URLs and raw endpoints are copyable. HTTP(S) URLs can open in a browser.
  Diagnostics expose SDK/engine/ABI versions and per-session state, not identity
  documents. Relay operations and live listing metadata changes are SDK-backed.
- Hide is a listing preference, not authentication. Anyone with the address can
  reach the published demo. The bundled v2.7.1/protocol-10 engine rejects ECH,
  x402/priced routes, method-constrained routes and overlay with
  `UNSUPPORTED_CAPABILITY`, rather than pretending to apply them. This workbench
  covers the principal transports, not every advanced `PortalConfig` option.
