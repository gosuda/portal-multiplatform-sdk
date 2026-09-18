# Android Sample (Compose)

A full Portal client: config editor, identity management, session controls
(start/stop/refresh/awaitReady), live metadata + relay updates, an event
log, and diagnostics — all over the authoritative `PortalSnapshot`.

The publish screen offers four contents: **Snake game** (bundled static
site), **How Portal works** (explainer page), **On-device model** (LLM
served over HTTP), and **Minecraft server** (server-list ping over TCP).

## Build

This sample consumes `io.github.gosuda:portal-sdk:0.1.0` and
`io.github.gosuda:portal-android-lifecycle:0.1.0` from Maven; it does not
compile the SDK projects as substitutes. Before the release is available on
Maven Central, stage the publications from this checkout:

```bash
./gradlew publishAndroidSdkToSampleRepository
./gradlew :samples:android:assembleDebug \
  -Pportal.samples.repository="$PWD/build/sample-maven"
```

## On-device model API

The "On-device model" content runs a real HTTP server on `127.0.0.1:18080`
inside the app and exposes it through the tunnel via `target_addr`. Once
published, the public URL serves the same endpoints to anyone — the device
does the inference, no cloud.

| Endpoint | Response |
|---|---|
| `GET /` | HTML playground (prompt form) |
| `GET /v1/generate?prompt=<text>&max_tokens=<n>&seed=<n>` | JSON completion |
| `GET /v1/model` | JSON model card (engine, backend, path) |
| `GET /v1/health` | JSON liveness (uptime, requests, inflight, rejected) |

### `/v1/generate`

Query parameters:

- `prompt` — input text (default `"Portal"`)
- `max_tokens` — completion length cap, 1–512 (default 256)
- `seed` — optional integer for deterministic sampling

Response:

```json
{
  "model": "litert-lm",
  "backend": "GPU",
  "prompt": "explain portals",
  "text": "Portals are …",
  "max_tokens": 256,
  "seed": null,
  "served_from": "this device"
}
```

Errors: `429` (8 requests already in flight, `Retry-After: 1`), `504`
(inference exceeded 120 s), `500` (inference failed), `404` (unknown path).

### Example

```bash
# After publishing, the public URL is shown on the Publish tab.
curl "https://<name>.<relay-domain>/v1/generate?prompt=explain%20portals&max_tokens=256"
curl "https://<name>.<relay-domain>/v1/health"
```

### Engine

- With a `.litertlm` model installed (Settings → `05 / On-device model` →
  pick a model → Download), inference runs on **LiteRT-LM** — GPU backend
  when enabled, CPU otherwise.
- Without a model, a tiny embedded Markov chain answers instead — same API
  shape, so the demo works out of the box.
- Model files live in `Android/data/org.gosuda.portal.sample/files/models/`
  and persist across app restarts; partial downloads resume via HTTP Range.

## Host app notes

- `PortalClient` is process-scoped via `portal-android-lifecycle`
  (`PortalClientHolder`); the Activity never owns the client.
- Keep-alive uses a foreground service (`KeepAliveService`) — required for
  publishing while the screen is off.
- minSdk 26; shipped ABIs arm64-v8a, x86_64.
