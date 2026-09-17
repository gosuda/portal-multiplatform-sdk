# iOS Sample (PortalSDK.xcframework)

`PortalHomeView.swift` shows the intended consumption of the KMP SDK from
SwiftUI via the `PortalIosClient` callback facade.

## Producing the framework

On macOS:

```bash
./gradlew :portal-sdk:assemblePortalSDKXCFramework
# -> portal-sdk/build/XCFrameworks/release/PortalSDK.xcframework (static)
```

Add the XCFramework to the app target, then link the engine archive:

```
# Build settings -> Other Linker Flags
-lportaltunnel
# plus the library search path containing your libportaltunnel.a
```

`libportaltunnel.a` is not shipped in this repository — the Go mobile bridge
source is unrecovered (`native/source-lock.json`). Build it per target
(iosArm64 device, iosSimulatorArm64, iosX64) once the bridge is available;
the cinterop bindings in `portal-sdk` already match
`native/include/portaltunnel.h`.

## Contract notes

- `open` completion fires once on the main dispatcher; keep the returned
  `PortalOperation` to cancel an in-flight start (rolls the native handle
  back).
- `observeState` delivers the current snapshot immediately, then every
  revision. Cancel the `PortalSubscription` when the view goes away — this
  does not stop the tunnel.
- `session.stop` completion receives `nil` on success; a failure leaves the
  session in `STOPPING` and can be retried.
- iOS sessions are foreground-scoped; do not rely on background execution.
