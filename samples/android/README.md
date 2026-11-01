# Android SDK workbench (Compose)

A monochrome, light/dark Compose workbench that publishes app-local services
through Portal relays. Five transport demos supply real loopback servers or
static files; **Device-local AI** additionally serves real native CPU inference.
The sample consumes staged Maven artifacts, not SDK `project()` dependencies.
The checked-in SDK version is not published to Maven Central.

## Build and install

Stage the unpublished artifacts, then build and install from the repository root:

```bash
./gradlew publishAndroidSdkToSampleRepository
./gradlew :samples:android:assembleDebug :samples:android:assembleRelease \
  -Pportal.samples.repository="$PWD/build/sample-maven"
adb install -r samples/android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n org.gosuda.portal.sample/.MainActivity
```

The installed Android SDK must support API 36; minSdk is 26. Install NDK
`29.0.14206865` and CMake `3.22.1` through SDK Manager. Native ABIs are
arm64-v8a and x86_64, with 16 KiB-compatible linking. Gradle builds the sample-only
`portal_sample_llm` JNI target from `samples/local-llm/CMakeLists.txt`; its first
configuration fetches the pinned llama.cpp source into an external cache.
This is separate from the SDK's transport library. Model weights are never
downloaded during builds or bundled in the APK.
The APK also includes `assets/local-llm-NOTICE`, generated from the shared native
notice so llama.cpp's MIT license accompanies the redistributed JNI library.

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

To request a shorter HTTP URL, use
`PortalConfig.http("127.0.0.1:8080").withDomainPrefix("my-arcade")`.
`tunnel.publicUrl` (or `tunnel.state.value.primaryPublicUrl`) selects the
relay-granted alias only while ACTIVE, otherwise the canonical URL.


## Five transport demos + device-local AI

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
| Device-local AI | `PortalConfig.http` | Native CPU model and private inference webpage | Select an explicitly downloaded catalog model |

For games, choose **Web games / static site**, set **Domain prefix** to e.g.
`my-arcade`, publish and open the primary HTTPS URL.
Snake, memory pairs, reaction testing and sloshing water fill share the
[arcade source and controls](../../README.md#play-the-arcade). All flat bundle
files are copied from packaged assets; no model, game engine or backend is installed.
For hosting while browsing on this same phone, explicitly opt into the
foreground service before publishing; Android background limits still apply.

**Domain prefix** defaults to `android-demo` and is persisted independently of
identity. Leave it blank to use the SDK's stored identity name. A prefix is a
1–63-character ASCII DNS label (letters, digits and hyphens, no edge hyphens);
the SDK lowercases it and rejects the reserved canonical 40-hex shape.
The relay must grant the short alias: requesting a prefix is not a permanent
reservation, and a collision with another identity falls back to the canonical
identity-bound URL. Session copy/open and HTTP exercise commands use the SDK's
preferred URL, never a synthesized alias. An alias is current only while ACTIVE;
after reconnect or stop the canonical fallback is shown instead. Canonical
identity-bound URLs appear separately only when different from the primary URL.


Disable **Use built-in loopback demo** for your own upstream. Start that service
on the Android device first: a desktop's localhost is not Android localhost.
The sample does not manage user-owned services and never enables remote targets.
The longest matching path prefix wins, not the order entered; `/` is the
catch-all fallback.

### Device-local AI: download once, then publish

1. Select **Device-local AI**. Its catalog is the shared `models.json`, not a
   second Android-specific list. Inspect the model's size, quantization, languages,
   license and source. Smaller candidates reduce storage and memory needs; actual
   latency and answer quality depend on the device.
2. Tap **Download** explicitly. Progress, SHA-256 verification, cancellation and
   errors are visible. Selecting a model, launching the app, and tapping Publish
   never starts a model download. Incomplete or unverified files cannot publish.
3. Wait for **CACHED**. Completed, size- and hash-verified weights live under
   `filesDir/local-llm-models`, survive app restarts/session stops, and are reused.
   Clearing app data or uninstalling removes the cache. Publish is disabled until
   the selected model is cached.
4. In **LOCAL AI / Password**, keep the cryptographically random default (recommended),
   tap **Generate password** for a new one, or type your own password of 12–128
   Unicode code points. The input is masked. **Copy password** copies it separately
   from the public URL; Android's clipboard entry is marked sensitive. Password
   editing and generation are disabled while publishing, serving, stopping, or
   retaining failed cleanup.
5. Set **Domain prefix**, optionally enable the foreground service while visible,
   and publish. Model loading and the loopback server run on IO. No cloud API,
   external daemon, bundled weights, or canned response fallback is involved.
6. Use **Session → Open in browser** or **Copy address** for the ordinary public
   URL. Enter the separately copied password on the webpage. The URL has no
   password, query credential or fragment key; sharing the URL alone does not
   authorize inference. The page keeps the password only in memory, not browser
   storage or history. Reloading the page requires entering it again.
7. Send a single-turn prompt. Requests have no conversation history, one inference
   runs at a time, and generation is bounded to 128 tokens / a 1024-token context.
   Keep the Android process alive while serving; background execution and the
   optional foreground service are not indefinite-hosting guarantees.
8. **Stop publishing** first stops the tunnel, then cancels inference and closes
   the owned server/model on IO. Failed tunnel or local cleanup stays owned and
   offers **Retry stop**. Cached weights are not deleted.

`SampleApp` owns the shared JVM `ModelStore`, its download coroutine, the password
draft, and the active `LocalLlmServer`; rotating the Activity cannot reset the
password, duplicate a download, or discard a live native handle. Session stops
also preserve the configured password for the next publish. Passwords are never
persisted to preferences or disk or included in `PublishRequest`, `PortalConfig`,
SDK metadata, diagnostics or logs. A new app process generates a fresh random
default without redownloading cached models. The app uses shared JVM sources plus
a direct JSON dependency, while R8 keeps the sample's JNI entry points. None of
this adds an inference or model-installation API to the SDK.

The API uses HTTP Basic authorization with fixed username `portal` and the
UTF-8 password. Missing or incorrect credentials return `401`; the webpage shows
its own password entry rather than a browser-native login dialog. Use HTTPS on
the public Portal URL; only the app's loopback listener uses HTTP. For example,
replace the host and password below (avoid saving real passwords in shell history):

```sh
curl -u 'portal:YOUR_PASSWORD' 'https://<public-host>/api/status'
curl -u 'portal:YOUR_PASSWORD' 'https://<public-host>/api/generate' \
  -H 'Content-Type: application/json' \
  --data '{"prompt":"Say hello briefly.","maxTokens":32}'
```

To avoid putting the password in a command or shell history, use `curl -u portal`
instead; curl prompts for it interactively.

The always-visible prefix field and monochrome navigation keep basic publishing
first. Relay/listing configuration and execution policy expand only on demand.

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

For the AI mode, explicitly download a catalog model, publish, open the private
page and verify that a real prompt produces native-generated text. Repeat after
an app restart to check cache reuse; rotate during download and while active;
stop during generation and retry any retained cleanup. Verify the base URL
serves the page, unauthenticated `/api/status` and `/api/generate` are rejected,
and a private link authorizes only its current session. Never paste the key or
prompts into captured diagnostics. Unlike the five transport demos, this check
requires downloaded weights and enough device memory.

## Ownership, identity and capability boundaries

- `SampleApp` owns the process-scoped `PortalClientHolder`, session, bounded SDK
  event history, demo listeners, model cache/download and active native server.
  The Activity only observes and forwards UI actions. Rotation never stops the
  tunnel or download. Nothing uses a global stop operation.
- Identity is the SDK's persistent Android default (`filesDir/identity.json`).
  Domain-prefix edits do not generate or overwrite identity or its keys.
  App uninstall/data clearing removes that identity; no identity reset is needed
  to request a short alias.
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
  reach the transport demos; local inference additionally requires its private
  per-session key. The bundled v2.7.1/protocol-10 engine rejects ECH,
  x402/priced routes, method-constrained routes and overlay with
  `UNSUPPORTED_CAPABILITY`, rather than pretending to apply them. This workbench
  covers the principal transports, not every advanced `PortalConfig` option.
