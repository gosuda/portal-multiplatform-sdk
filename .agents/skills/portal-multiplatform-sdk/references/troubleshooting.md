# Troubleshooting — portal-multiplatform-sdk

## Build

- **`org.jetbrains.kotlin.android` fails to apply** — AGP 9 has built-in
  Kotlin; remove the plugin. Set `jvmTarget` via top-level
  `kotlin { compilerOptions {} }`.
- **`androidLibrary` deprecated** — use `android {}` inside `kotlin {}` for
  the KMP Android target.
- **cinterop type errors** — `char**` out-params are `CPointerVar<ByteVar>`;
  `const char*` params take `CValuesRef` (`s.cstr` inside `memScoped`).
- **iosSimulatorArm64Test / iosX64Test disabled** — intentional: they would
  link `libportaltunnel.a`, which is not shipped. `compileTestKotlinIos*`
  still runs on macOS.

## Runtime

- **`NATIVE_UNAVAILABLE` on Android** — `libportaltunnel.so` missing for the
  device ABI (only arm64-v8a/x86_64 ship) or `portal-native-android` not on
  the classpath. Check `unzip -l app.apk | grep libportaltunnel`.
- **No events after open** — `events` has no replay; read `state` for
  durable status. Events emitted before the first subscriber count into
  `droppedEventCount`.
- **`stop()` threw but tunnel seems alive** — by design: failure keeps the
  session registered in STOPPING; retry `stop()` or check
  `state.value.lastFailure`.
- **iOS link failure** — consumer must provide `libportaltunnel`; the
  archive is not embedded in the klib yet (native/source-lock.json).
- **Insecure relay rejected** — `http://`/`ws://` relays need
  `PortalClient(allowInsecureLocalRelays = true)`; intended for local dev.
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
