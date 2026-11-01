# Desktop SDK workbench

A Compose Desktop consumer of `io.github.gosuda:portal-sdk`. Local demo
listeners belong to this sample; the SDK only publishes and owns tunnels.
No game engine, model download, application-runtime installer, or fake
connection path is included.

## Run an unpublished checkout

Requires JDK 17+, Go 1.27.3, and the native host toolchain described in the
[root README](../../README.md#install). Gradle itself uses JDK 21.
Packaging also needs a full JDK 17 with `jpackage` (for example Temurin).
The sample selects its compiler toolchain for packaging rather than Android
Studio's stripped JBR. Compose rejects Homebrew JDK packaging by default;
use a supported full distribution instead of disabling its vendor check.

```bash
./scripts/build-desktop-engine.sh
./gradlew publishDesktopSdkToSampleRepository
./gradlew :samples:desktop:run \
  -Pportal.samples.repository="$PWD/build/sample-maven"
```

The sample resolves staged Maven artifacts, never SDK project substitution.
The checked-in SDK version is not assumed to be published to Maven Central.
## The SDK path: create, then publish

With your HTTP server already listening on loopback:

```kotlin
// 1. Keep one client in the application owner.
val client = PortalDesktop.client("com.example.myapp")
// 2. Publish one factory config from a coroutine.
val tunnel = client.publish(PortalConfig.http("127.0.0.1:8080"))
println(tunnel.publicUrl)
```

Keep the upstream server and client alive for the publication lifetime.
`tunnel.stop()` ends one publication; `client.close()` at owner teardown ends
all sessions it owns. Catch `PortalException` and inspect `failure.code`,
`operation`, and `retryable`; coroutine cancellation propagates. Platform
identity storage, readiness waiting (30 seconds by default), and failed-publish
rollback belong to the SDK, not app-written paths or polling routines.
If cleanup fails, retry stop/close on the retained STOPPING session.


## Runnable demonstrations

Choose a mode, leave **built-in demo** enabled, and publish. No separate local
server is needed for the built-in modes. Discovery still needs a reachable
Portal relay; raw transport availability depends on relay support.

| Mode | Local payload | Public exercise |
|---|---|---|
| Web games / static site | Shared four-game arcade served directly by the native engine | Open the HTTPS URL and play |
| HTTP | Loopback API and webhook receiver | `GET /api/status` returns JSON; `POST /webhook` acknowledges the request |
| Routes | `/api/` selects one API listener; `/` selects a second webhook listener | Compare `GET /api/status` and `POST /webhook` |
| TCP | Loopback byte echo | Send bytes to the relay's `tcpAddr`; expect identical bytes |
| UDP | Loopback datagram echo | Send a datagram to `udpAddr`; expect the same datagram |

The [shared arcade](../../README.md#play-the-arcade) includes Snake, memory
pairs, reaction testing and sloshing water fill. Leave the static directory
blank to use the bundled games. Compose packages `samples/shared/common/site`
as real application resources; the static demo does not create or delete a
temporary copy. Headless smoke uses the same source directory.


The UI supplies request commands. TCP/UDP addresses are not browser URLs.
Built-in listeners bind `127.0.0.1` on ephemeral ports; their resolved targets
are displayed. HTTP request bodies are discarded and limited to 16 KiB. Echo
buffers are 4096 bytes. The latest 40 activity entries omit request bodies,
headers and query strings; do not put secrets in URL paths.

Disable built-in mode to forward an existing loopback service. Route prefixes
and upstream URLs are editable in custom mode; static mode also accepts a
local directory. Your service must already be listening before you publish.
An ACTIVE tunnel is not an upstream-health check.
Routing chooses the longest matching prefix, not entered order; `/` is the
fallback. ACTIVE means every requested feature is ready: HTTP/static need a
public URL and TCP/UDP each need their own nonblank endpoint on a ready relay.


## Session operations

- Inspect authoritative phase, revision, capabilities, URLs, relay state and
  sticky security warnings; copy/open HTTPS URLs separately from raw endpoints.
- Refresh or await ACTIVE; update live metadata; add/remove relays; inspect
  bounded auxiliary events and SDK diagnostics.
- Stop is retryable. Failed publish cleanup retains the owned STOPPING handle
  and its local demo, rather than hiding a live native session.
- Closing the window closes only this application's sessions. Cleanup failure
  leaves the window open for a stop/close retry; there is no hidden tray owner.
- The SDK persists identity at its platform-default path. Editing a display
  name does not rotate or overwrite private identity material. Hide controls
  listing visibility, not access control.
- The bundled v2.7.1/protocol-10 engine supports the five modes and discovery.
  ECH, x402/priced routes, method-constrained routes and overlay fail with
  `UNSUPPORTED_CAPABILITY`; they are not silently applied security policy.


## Runtime verification

```bash
# Real local HTTP/routes/TCP/UDP, request/log limits and listener stop/restart.
# Static serving is checked by the native real-relay smoke below.
./gradlew :samples:desktop:workbenchSmoke \
  -Pportal.samples.repository="$PWD/build/sample-maven"

# Real SDK publish and public round-trips for all five modes.
./gradlew :samples:desktop:smoke -Pportal.smoke.mode=ALL \
  -Pportal.samples.repository="$PWD/build/sample-maven"
```

`portal.smoke.mode` also accepts `STATIC`, `HTTP`, `ROUTES`, `TCP`, or `UDP`;
the default is `HTTP`. `PORTAL_SMOKE_RELAYS` supplies explicit relay URLs;
discovery remains enabled unless `-Pportal.smoke.discovery=false` is set.
For a controlled protocol probe, provide compatible relay URLs and disable
discovery explicitly. An allocated endpoint is not proof its public port is
reachable from every caller network. Smoke fails visibly if the selected
endpoint or response is unavailable; it never substitutes local success.
