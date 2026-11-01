# Troubleshooting — portal-multiplatform-sdk

## Build
- **Maven cannot resolve `0.1.0`** — it is unpublished. Stage with
  `publishAndroidSdkToSampleRepository` or
  `publishDesktopSdkToSampleRepository` and point the sample at
  `-Pportal.samples.repository="$PWD/build/sample-maven"`. A dependency
  coordinate alone does not install an unpublished SDK.
- **Swift package not found remotely** — build/package the local XCFramework,
  extract `dist/PortalSDK-swift-package.zip`, and add its `PortalSDK` folder
  as a local package. No public downloadable release is available yet.

- **`org.jetbrains.kotlin.android` fails to apply** — AGP 9 has built-in
  Kotlin; remove the plugin. Set `jvmTarget` via top-level
  `kotlin { compilerOptions {} }`.
- **`androidLibrary` deprecated** — use `android {}` inside `kotlin {}` for
  the KMP Android target.
- **cinterop type errors** — `char**` out-params are `CPointerVar<ByteVar>`;
  `const char*` params take `CValuesRef` (`s.cstr` inside `memScoped`).
- **iosSimulatorArm64Test / iosX64Test disabled** — only when the matching
  `libportaltunnel.a` is absent; Gradle builds the archives from
  `native/bridge` automatically on macOS (`buildIosEngine`), so the tests
  run when the toolchain is present.

## Runtime
- **How much setup is required?** — create one long-lived client and call
  `publish` with a factory config. Android `PortalClient(context)`, desktop
  `PortalDesktop.client(appId)`, and Swift `PortalIosClient()` own persistent
  identity defaults. Do not add manual identity paths, readiness polling or
  rollback routines to normal onboarding. Your server must already be running,
  and your app keeps it and the client alive until tunnel cleanup completes.
- **Publish failure** — Kotlin throws `PortalException`; Swift
  `publish(config:completion:)` reports `PortalFailure` on Main. Inspect
  `code`, `operation`, `retryable`, and `readinessFailure`. Publish waits for
  all requested capabilities (30 seconds by default) and rolls back on failure.
- **ACTIVE but requests fail** — readiness is not application-health validation.
  HTTP/static require a ready URL; TCP and UDP each require a nonblank raw
  endpoint on a ready relay. Use the matching protocol, never a browser URL
  for TCP/UDP; check that your loopback server remains alive.

- **`NATIVE_UNAVAILABLE` on Android** — `libportaltunnel.so` missing for the
  device ABI (only arm64-v8a/x86_64 ship) or `portal-native-android` not on
  the classpath. Check `unzip -l app.apk | grep libportaltunnel`.
- **No events after open** — `events` has no replay; read `state` for
  durable status. Events emitted before the first subscriber count into
  `droppedEventCount`.
- **`stop()` threw but tunnel seems alive** — by design: failure keeps the
  session registered in STOPPING. Retain its upstream and retry `stop()` or
  client close. Failed publish rollback is recoverable through `client.sessions`.
- **iOS link failure** — consumers never link `libportaltunnel.a` manually:
  each published klib embeds the archive and Security linkage; Swift
  consumers use the self-contained `PortalSDK.xcframework`/Swift package.
- **Insecure relay rejected** — relays are `https`-only; `http://` is
  accepted only for loopback hosts (upgraded to https); `ws://`/`wss://`
  are not valid relay schemes.
- **Remote target rejected** — non-loopback `target_addr`/`udp_addr` need
  `PortalClient(allowRemoteTargets = true)`.
- **16KB page-size devices** — shipped `.so`s have 0x4000-aligned LOAD
  segments; verified via `readelf -lW`.

## Contract gotchas

- `encodeDefaults=false`: absent JSON keys mean "native default". A field
  set to its Kotlin default is omitted on the wire.
- `hide=true` is a listing preference, not access control.
- `hasSecurityWarning` (MITM self-probe) is a sticky suspicion flag, not
  proof; relays can still observe traffic metadata.
- Route selection uses the longest matching prefix, not entered order.
- ECH, x402/priced routes, method-constrained routes and overlay are rejected
  with `UNSUPPORTED_CAPABILITY` by the v2.7.1/protocol-10 engine.
