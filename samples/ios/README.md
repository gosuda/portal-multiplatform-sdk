# iOS Sample (PortalSDK.xcframework)

`PortalHomeView.swift` shows the intended consumption of the KMP SDK from
SwiftUI via the `PortalIosClient` callback facade.

The sample has three Korean-language destinations: 공개 (publish), 설정
(next-session configuration), and 활동 (live management, identity, events,
and diagnostics). Configuration and session state remain in one `StateObject`
when switching destinations. The publish screen offers every current public
URL with clipboard feedback and browser links; terminal-session URLs are hidden.

## Host app requirements

This directory is a SwiftUI source sample, not a standalone Xcode project.
Create an iOS 16+ SwiftUI app target on macOS, include `PortalHomeView.swift`,
and present `PortalHomeView(siteDir:identityPath:)` with an existing local site
directory containing `index.html` and a writable identity-file path. Keep the
view/model alive for the intended session lifetime and stop publishing before
discarding it. There is no simulated connection or preview engine.

The framework alone is insufficient to run: the matching native engine archive
below is required for linking, publishing, and identity generation. This sample
does not substitute successful UI state when the engine is unavailable.

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
  revision. The model cancels subscriptions when the view disappears and
  reattaches on appearance or return to the foreground, reading the current
  snapshot. This does not stop the tunnel. Auxiliary events are not replayed.
- `session.stop` completion receives `nil` on success; a failure leaves the
  session in `STOPPING` and can be retried.
- Start and session mutations are guarded while an operation is in progress.
  A failed stop remains retryable from the publish screen.
- iOS sessions are foreground-scoped; do not rely on background execution.
  Leaving the app or locking the screen can interrupt publishing, but there is
  no guarantee that a tunnel stops immediately on backgrounding. Keep the app
  in the foreground while serving, and explicitly stop when finished.
- Relay discovery finds relays; it does not control public-directory listing.
  Hiding metadata is not authentication, and ECH is not blanket anonymity.
