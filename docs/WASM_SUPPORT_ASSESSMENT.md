# Kotlin/Wasm Support Assessment

Date: 2026-09-18
Decision: do not add a runnable Portal tunnel target yet

## 1. Executive decision

Do **not** publish `wasmJs` or `wasmWasi` as supported targets of
`portal-sdk` today.

A target that compiles but cannot start the Portal engine, listen/dial with the
required semantics, persist identity safely, or expose app-local HTTP/TCP/UDP
services is not SDK support. Returning `UNSUPPORTED_CAPABILITY` from every
operation, shipping a mock engine, or silently delegating to a remote service
would misrepresent the existing `PortalClient` contract.

Track two distinct future directions:

1. **Browser web control SDK** — a separate product that observes and controls
   a tunnel owned by a trusted mobile/desktop/daemon runtime. It is not a
   `PortalClient` implementation and must not handle Portal identity key
   material in browser storage by default.
2. **WASI runtime research spike** — reconsider only when Kotlin and the Portal
   engine can target the same Component Model/WASI socket generation with
   production-grade networking.

## 2. Current Kotlin/Wasm environments

### `wasmJs` / browser

Kotlin/Compose web applications use the `wasm-js` target and execute inside a
browser sandbox. They can use browser APIs and JavaScript interop, including
`fetch` and WebSocket, but cannot load the native Go C ABI through JNI, JNA, or
Kotlin/Native cinterop.

More importantly, the current Portal product exposes an app-owned local
service:

```text
local HTTP/TCP/UDP listener -> Portal engine -> relay -> public endpoint
```

A browser does not provide arbitrary TCP/UDP listen/dial APIs or filesystem
paths matching `target_addr`, `udp_addr`, `static_dir`, or `identity_path`.
`fetch("http://127.0.0.1:...")` is not equivalent: it is HTTP-only and subject
to browser CORS/private-network/mixed-content policy; it cannot proxy raw TCP
or UDP and cannot turn the page into a native local service owner.

Therefore browser `wasmJs` cannot preserve:

- HTTP/TLS upstream parity for arbitrary app-local servers;
- raw TCP or UDP exposure;
- filesystem-backed static roots and identity paths;
- the native Portal relay/QUIC/TLS implementation;
- process/session ownership semantics across page refresh, worker suspension,
  tab close, and browser storage eviction.

### `wasmWasi`

As of 2026-09-18, Kotlin/Wasm officially supports WASI 0.1 (Preview 1); WASI
0.2 support is planned. The current Kotlin target does not expose the current
WASI 0.3 Component Model socket/async interfaces used by a modern network
service architecture.

Go officially targets `GOOS=wasip1 GOARCH=wasm`. Go's own WASI documentation
states that WASI Preview 1 lacks a full socket implementation: it can operate
on already-open sockets but cannot generally open sockets, preventing standard
`net/http` servers and many normal `net` operations without host-specific
extensions.

The Portal bridge and transitive engine use:

- `net` and `net/http`;
- WebSocket;
- QUIC;
- TLS/cryptography;
- filesystem identity/static content;
- OS/system inspection dependencies;
- concurrent long-lived relay and local-serving loops.

A successful `GOOS=wasip1` compile, if achievable after dependency surgery,
would not prove runtime equivalence. Host-specific socket extensions would
also destroy the portable KMP target promise.

## 3. Why WASI 0.3 is not enough yet

WASI 0.3 is the current stable WASI generation and includes native async plus
TCP/UDP socket interfaces. That makes it relevant, but it does not by itself
make Portal portable:

- Kotlin/Wasm currently targets WASI 0.1, not 0.3.
- Go's official port currently targets `wasip1`, not a WASI 0.3 component.
- Kotlin and Go modules need a shared Component Model/WIT interface and host
  composition strategy; the current C ABI cannot simply be linked like a
  native `.so`/`.dll`/`.dylib`.
- Portal's QUIC/TLS stack and transitive OS-specific dependencies must work
  against the chosen WASI socket/TLS capabilities.
- A WASI host must grant outbound relay networking, local upstream access,
  clocks, randomness, filesystem/preopens, and long-lived async execution.
  Those are host capabilities, not assumptions the SDK can make.

Adding `wasmWasi()` to Gradle before those conditions hold would create an
artifact with no honest end-to-end behavior.

## 4. Architecture options

### Option A — Browser tunnel runtime

Decision: rejected for current Portal semantics.

A browser-only runtime would need to reimplement the relay protocol over
browser WebSocket/WebTransport and replace local upstreams with in-page request
handlers. It would be HTTP-oriented and could not preserve raw TCP/UDP,
filesystem paths, or native engine parity. This is a new engine and product,
not another adapter for `PortalNativeEngine`.

If pursued later, name it explicitly (for example `portal-browser-publisher`),
define a new capability model, and never pretend it supports the existing
`PortalConfig` modes unchanged.

### Option B — Browser control plane

Decision: feasible as a separate module after a management protocol exists.

```text
Kotlin/Wasm browser UI
  -> authenticated HTTPS/WebSocket management API
     -> trusted Portal owner (desktop/mobile/daemon)
        -> PortalClient + native engine
```

Possible browser operations:

- list sessions and authoritative snapshots;
- observe state/events;
- request publish/stop/relay/metadata operations;
- display/copy public URLs and diagnostics.

Required security contract:

- short-lived scoped authorization; no Portal identity document in browser
  localStorage/IndexedDB by default;
- CSRF/origin enforcement and explicit CORS policy;
- replay protection and per-operation authorization;
- owner runtime remains authoritative and survives page refresh;
- secrets and facilitator tokens are redacted from every response;
- browser disconnect never implicitly stops a tunnel.

This must be a separately named artifact/API. Do not implement the current
`PortalClient` with remote calls behind the same name because local ownership,
cancellation, latency, and failure semantics differ materially.

### Option C — WASI component runtime

Decision: research only; gated.

Potential future shape:

```text
Kotlin wasmWasi component
  -> WIT portal-engine interface
     -> Go/Rust Portal engine component
        -> wasi:sockets + wasi:filesystem + wasi:random + wasi:clocks
```

This requires a new WIT boundary rather than the v1 pointer-based C ABI. The
host owns capability grants. Identity/storage paths become preopened directory
handles, not arbitrary strings. Event callbacks become async streams. That is
an ABI v2+ design and must not be squeezed into the current v1 interface.

## 5. No-op target policy

The repository must not add any of the following merely to make dependency
resolution succeed:

- `actual platformNativeEngine()` that always throws;
- fake public URLs or simulated sessions;
- compile-only `PortalClient` APIs with no tunnel implementation;
- browser-to-cloud fallback hidden behind local `PortalClient`;
- disabled TCP/UDP/static behavior without explicit new capability types.

If shared web UI needs Portal types before runtime support exists, consider a
small read-only protocol/model artifact later. Do not make all of
`portal-sdk` resolve on Wasm by weakening its runtime contract.

## 6. Re-evaluation gates

Re-open WASI implementation only when every gate is met:

1. Kotlin/Wasm supports the selected WASI Component Model release and its
   async/socket bindings in stable tooling.
2. The Portal engine language/toolchain targets the same WASI release, or a
   production WIT component adapter exists.
3. QUIC/TLS/WebSocket and all transitive dependencies compile and execute
   without host-specific socket extensions.
4. A host capability model can grant:
   - outbound relay TCP/UDP/HTTP;
   - local upstream access;
   - secure randomness and clocks;
   - persistent preopened identity/static storage;
   - long-lived async execution.
5. Identity generation/parsing, relay connect, HTTP forwarding, TCP, UDP,
   static content, callbacks, cancellation, and retryable stop pass against at
   least two conforming runtimes.
6. Binary/component provenance and reproducible builds are recorded in
   `native/source-lock.json`.
7. The public API can express capability handles and async streams without
   lying about string paths or process ownership.

## 7. Recommended near-term work

### W0 — Keep common code Wasm-conscious

No target is published. When changing commonMain:

- avoid `java.*`, POSIX, pointers, and platform paths;
- keep serialization/state/failure models portable;
- do not introduce thread-blocking assumptions into common reducers;
- retain explicit capabilities and structured unsupported errors.

This is already the architecture rule and costs no separate abstraction.

### W1 — Protocol-only feasibility test

Before any production implementation:

1. Attempt to compile the minimal Portal Go bridge dependency graph for
   `GOOS=wasip1 GOARCH=wasm`.
2. Produce a categorized report: compile failures, missing syscalls, sockets,
   QUIC/TLS, filesystem, threading, and unsupported OS packages.
3. Do not patch dependencies or claim support in this phase.
4. Separately compile a minimal Kotlin `wasmWasi` component that uses the
   available WASI version and document its actual socket surface.

Deliverable: evidence-backed gap report, not an SDK artifact.

### W2 — Web control product discovery

Only if browser management is a product requirement:

1. Define a versioned management API independent of the native C ABI.
2. Threat-model browser auth, origins, secret redaction, and tunnel ownership.
3. Prototype read-only session listing/state streaming first.
4. Add mutations only after authorization and idempotency contracts exist.
5. Build `portal-web-control` as a separate KMP/Wasm artifact.

Do not couple Desktop implementation to this work. Desktop remains the next
runnable KMP platform because it can host the real engine.

## 8. Verification for any future WASI target

A Wasm target is supported only after proving all of the following on real
runtimes:

- identity persists in an explicitly granted store;
- relay connection and readiness complete;
- a local HTTP upstream is reachable and publicly fetchable;
- TCP and UDP work, or the public capability model explicitly excludes them
  as a different product;
- callbacks/events preserve ordering and cannot cross the host boundary with
  exceptions;
- cancellation rolls back handles;
- failed stop remains observable and retryable;
- runtime capability denial maps to structured `PortalFailure`;
- no secret appears in browser console, JS glue, diagnostics, or serialized
  management state;
- at least two conforming WASI runtimes pass the same behavior suite.

Compilation, a Hello World, or a mocked relay is not acceptance evidence.

## 9. Source evidence

- Kotlin/Wasm environments and Compose browser target:
  https://kotlinlang.org/docs/wasm-overview.html
- Kotlin/Wasm currently supports WASI 0.1; WASI 0.2 is planned:
  https://kotlinlang.org/docs/wasm-wasi.html
- Go `wasip1` limitations, including missing full socket open/listen support:
  https://go.dev/blog/wasi
- WASI release/component model status:
  https://wasi.dev/releases
- WASI 0.3 async and socket model:
  https://wasi.dev/releases/wasi-p3

## 10. Final recommendation

Proceed with the JVM Desktop plan first. It reuses the real C ABI and can meet
full Portal semantics on Linux, Windows, and macOS.

For Kotlin/Wasm:

- **Browser runtime:** no-go for current `PortalClient` semantics.
- **Browser control UI:** viable separate product after a secure management API.
- **WASI runtime:** research track only until Kotlin and Go share a production
  Component Model/socket toolchain and the Portal engine passes real network
  tests.
