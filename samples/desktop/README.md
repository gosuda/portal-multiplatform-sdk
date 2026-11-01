# Desktop SDK workbench

A black-and-white Compose Desktop consumer of `io.github.gosuda:portal-sdk`.
Five transport demonstrations, a shared four-game arcade, and a private
**device-local AI webpage** run on your own computer. The SDK owns tunnel
transport and lifecycle; model downloads, the CPU inference engine, and local
listeners belong to this sample, not the SDK.

## Run an unpublished checkout

Requires JDK 17+, Go 1.27.3, and the native host toolchain described in the
[root README](../../README.md#install). Gradle itself uses JDK 21. Local AI also
requires CMake, Ninja and a C++17 compiler; the pinned llama.cpp source is fetched
when building the sample-owned native library, not model weights.
Packaging needs a full JDK 17 with `jpackage` (for example Temurin); Android
Studio's stripped JBR is insufficient. Use a supported JDK rather than disabling
Compose's vendor check.

```bash
./scripts/build-desktop-engine.sh
./gradlew publishDesktopSdkToSampleRepository
./gradlew :samples:desktop:run \
  -Pportal.samples.repository="$PWD/build/sample-maven"
```

The sample consumes staged Maven artifacts, never SDK project substitution.
The checked-in SDK version is not assumed to be published to Maven Central.
Run/packaging builds the sample CPU inference library automatically. This may
fetch pinned **source code**, but never downloads any model weights.

## Choose an experience

The workbench uses an editorial monochrome layout, neutral bordered cards and
system dark-mode inversion. Domain prefix remains visible; relay and metadata
settings are under **Advanced / relays & metadata**. SDK session operations and
diagnostics are under **SDK tools & diagnostics**.

| Mode | Local payload | Public exercise |
|---|---|---|
| Local AI / device CPU | Downloaded catalog GGUF loaded by the sample's native CPU engine | Open the AI webpage, enter the configured password and generate a single-turn response |
| Web games / static site | Four-game arcade served directly by the Portal engine | Open the HTTPS URL and play |
| HTTP | Loopback JSON API and webhook receiver | `GET /api/status`; `POST /webhook` |
| Routes | `/api/` selects one API; `/` selects a separate webhook listener | Compare API and webhook responses |
| TCP | Loopback byte echo | Send bytes to the relay's `tcpAddr` |
| UDP | Loopback datagram echo | Send datagrams to the relay's `udpAddr` |

Built-in transport demos need no external local server. All publications need
Internet and a compatible relay. Raw transport support depends on the relay.
Disable **Use built-in loopback demo** to forward an existing loopback service;
custom route prefixes/upstreams and targets remain editable. ACTIVE means the
requested relay listeners are ready, not that a custom upstream is healthy.
The longest matching route prefix wins; `/` is the fallback.

The [shared arcade](../../README.md#play-the-arcade) contains Snake, memory pairs,
reaction testing and sloshing water fill. Leave Static directory blank to use
it, or supply a local directory. The real shared static bundle remains packaged
as application resources; no temporary arcade copy is created or deleted.
Built-in HTTP bodies are discarded and capped at 16 KiB; echo buffers are
4096 bytes. The last 40 activity entries omit bodies, headers and query strings.
Do not put secrets in URL paths.

## Local AI: explicit download, persistent cache

1. Choose **Local AI / device CPU**.
2. Review the shared catalog cards: model title, parameters, download size,
   quantization, supported languages, license and source. Select a model and
   explicitly press **Download**. Selection and publishing never download it.
3. Watch byte progress and verification; **Cancel download** cancels acquisition
   without deleting previously cached models. Failures are shown rather than
   replaced with a fake or cloud answer.
4. Set the **Password**, or keep the cryptographically random default. A generated
   password is recommended; **Generate password** replaces it and **Copy password**
   copies it explicitly. Custom passwords must contain 12–128 Unicode characters.
5. After the card says **Cached on this computer**, publish. The model is loaded
   on an IO worker, then a loopback-only HTTP listener is published through Portal.
6. In Session, use **Open in browser** or **Copy URL** for the base URL. Enter
   the password separately on the webpage. Sharing the URL alone cannot authorize
   inference; share the password only with trusted recipients.
7. Stop before editing/regenerating the password or removing an active model's
   cache. **Remove cache** is available only while no publication owns its local
   payload. Stop/publish and app restarts preserve cached weights.

The shared catalog includes SmolLM2 135M/360M Instruct and Qwen2.5 0.5B/1.5B
Instruct CPU-compatible GGUF choices. Model cards are sourced from the single
shared `models.json`, not duplicated platform constants. Downloads verify exact
size and SHA-256 before becoming usable; incomplete files are not usable models.
No model is bundled in an installer or silently fetched on first launch.
No Ollama, inference daemon, cloud API or cloud credentials are required.

UI and headless commands share the same default cache:

- macOS: `~/Library/Application Support/org.gosuda.portal.sample.desktop/models`
- Windows: `%LOCALAPPDATA%/org.gosuda.portal.sample.desktop/models`
- Linux: `${XDG_DATA_HOME:-~/.local/share}/org.gosuda.portal.sample.desktop/models`

Override it consistently with Gradle `-Pportal.models.directory=/absolute/path`,
or direct JVM `-Dportal.sample.models.dir=/absolute/path`. Stopping a tunnel does
not delete the cache. Downloads belong to the app/window owner and do not restart
on recomposition. Closing the window cancels an in-progress download.

### Hosting and security limits

Inference is real native **CPU work on this computer**, not browser or cloud
inference. Speed, RAM use and answer quality vary by model and hardware; no
unmeasured performance is promised. Keep the app open and the computer awake.
Closing the app stops serving; this is not unattended production hosting.

The webpage sends independent single-turn requests, with no server-side chat
history. The engine enforces a 1024-token context and at most 128 output tokens;
only one inference can run at a time. Oversized/invalid requests and concurrent
requests are rejected explicitly. The loopback listener does not expose the
model cache or arbitrary files.

The window owns the configured password in memory only: it is reused for the
next publish until edited, never stored in preferences or SDK configuration,
and replaced with a new random default on app relaunch. Editing and regeneration
are disabled while starting, serving or stopping. Passwords are never attached
to URLs, SDK metadata, events or diagnostics, and are never printed automatically.
The webpage asks for the password separately and keeps it only in page memory,
not cookies, local storage or history. API requests use HTTP Basic authentication
with fixed username `portal` and the UTF-8 password. Anyone holding the password
and URL may use your CPU while the publication lives. Hiding a listing is not
authentication. Prompts and generated text are not printed to sample logs.
Public Portal URLs use HTTPS; only the loopback listener uses HTTP. HTTPS
transport does not make an untrusted relay an end-to-end encrypted inference
service.

To call a running publication directly, replace the base URL and enter the
configured password when curl prompts (the password is not placed in shell history):

```bash
curl -u portal https://YOUR_PUBLIC_BASE_URL/api/status
curl -u portal https://YOUR_PUBLIC_BASE_URL/api/generate \
  -H 'Content-Type: application/json' \
  --data '{"prompt":"Write one short sentence about the moon.","maxTokens":48}'
```

## Domain prefix and session ownership

The always-visible **Domain prefix** defaults to `desktop-demo`. It requests a
short alias for this publication, not a permanent reservation or identity
rename. Use a 1–63 character ASCII DNS label (letters, digits, internal hyphens).
The SDK normalizes case and rejects reserved canonical-name shapes. Blank uses
the stored identity name. If the prefix belongs to another identity, the
canonical identity-bound URL remains the fallback.

The UI copies/opens `snapshot.primaryPublicUrl`: only an actual ready,
relay-granted alias is preferred while ACTIVE. Canonical URLs remain separately
labelled; a stopped/reconnecting session does not claim a stale alias. Raw
TCP/UDP endpoints are unchanged. AI sharing uses only this current base URL,
with the separately configured password entered on the webpage.

Refresh, await ACTIVE, live metadata updates, relay add/remove, bounded events
and diagnostics remain available. Stop is retryable. Failed publish cleanup or
stop retains the owned STOPPING handle **and local server/model** until cleanup
succeeds. Window close closes only this app's sessions; failure keeps the window
open for retry. Editing prefix or metadata never rotates identity keys.
ECH, x402, method-constrained routes and overlay remain unsupported capabilities,
not silently applied policy.

## Minimal SDK integration

With an HTTP server already listening on loopback:

```kotlin
val client = PortalDesktop.client("com.example.myapp")
val tunnel = client.publish(
    PortalConfig.http("127.0.0.1:8080").withDomainPrefix("my-workbench")
)
println(tunnel.publicUrl) // Base transport URL only; never an inference password.
```

Keep upstream and client alive for the publication lifetime. `tunnel.stop()`
ends one publication; `client.close()` ends all sessions it owns. Catch
`PortalException` and inspect `failure.code`, `operation`, and `retryable`;
coroutine cancellation propagates. Retry failed stop/close on the retained owner.

## Explicit CLI downloads and real smoke

```bash
# Show exact shared catalog IDs and cached state; does not download.
./gradlew :samples:desktop:modelCatalog \
  -Pportal.samples.repository="$PWD/build/sample-maven"

# Explicit acquisition only. Replace MODEL_ID with one listed above.
./gradlew :samples:desktop:downloadModel -Pportal.model.id=MODEL_ID \
  -Pportal.samples.repository="$PWD/build/sample-maven"

# Real public inference + missing/wrong-password checks, with that cached model.
./gradlew :samples:desktop:smoke -Pportal.smoke.mode=LOCAL_LLM \
  -Pportal.smoke.model=MODEL_ID -Pportal.smoke.domainPrefix=my-local-ai \
  -Pportal.samples.repository="$PWD/build/sample-maven"

# Real local HTTP/routes/TCP/UDP, bounds and stop/restart (no model load/download).
./gradlew :samples:desktop:workbenchSmoke \
  -Pportal.samples.repository="$PWD/build/sample-maven"

# All five original transports only; no model acquisition or inference.
./gradlew :samples:desktop:smoke -Pportal.smoke.mode=ALL \
  -Pportal.samples.repository="$PWD/build/sample-maven"
```

The smoke CLI (`org.gosuda.portal.sample.SmokeKt`) accepts `--list-models`,
`--download-model=<catalog-id>`, or `--mode=LOCAL_LLM --model=<cached-id>`.
Add `--relay` for public verification; without it LOCAL_LLM exercises real
loopback inference twice with close/reopen. Each local AI run generates its own
caller-known random password (including Unicode coverage), passes it explicitly
to the server, and checks missing/wrong credentials plus real generation using
the shared Basic authorization helper. The password is never printed.
`--mode=ALL` deliberately stays the five original modes. There is no implicit
download in any smoke mode.

`portal.smoke.mode` defaults to HTTP and also accepts STATIC/HTTP/ROUTES/TCP/UDP.
`PORTAL_SMOKE_RELAYS` supplies explicit relay URLs; discovery stays enabled
unless `-Pportal.smoke.discovery=false` (CLI `--no-discovery`).
`-Pportal.smoke.domainPrefix=<label>` passes `--domain-prefix=<label>`; blank uses
the stored identity name. The smoke requests the authoritative preferred URL
and reports canonical URLs separately, without printing passwords, prompts
or generated text. A collision may legitimately fall back to a canonical URL.
Network/public endpoint failures are reported, never replaced by local success.

## Packaging

Compose generates `build/generated/appResources` from the unchanged shared
`common` bundle plus the host-specific native library built under
`build/sample-llm/desktop`. Generated `macos-arm64`/`macos-x64`, `linux-*`, or
`windows-*` resources place the library under `native`; the packaged launcher
loads it from its own resources directory, not a checkout path. No generated
native binaries or model weights are tracked. Standard Compose
`createDistributable` and platform package tasks prepare these resources.
Direct JVM launches may set `-Dportal.sample.llm.library=/absolute/library/path`;
Gradle's smoke/run tasks configure the checkout native library path themselves.
