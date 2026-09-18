# Troubleshooting — portal-multiplatform-sdk

## Build

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

- **`NATIVE_UNAVAILABLE` on Android** — `libportaltunnel.so` missing for the
  device ABI (only arm64-v8a/x86_64 ship) or `portal-native-android` not on
  the classpath. Check `unzip -l app.apk | grep libportaltunnel`.
- **No events after open** — `events` has no replay; read `state` for
  durable status. Events emitted before the first subscriber count into
  `droppedEventCount`.
- **`stop()` threw but tunnel seems alive** — by design: failure keeps the
  session registered in STOPPING; retry `stop()` or check
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
