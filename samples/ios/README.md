# iOS Sample (PortalSDK.xcframework)

`PortalHomeView.swift` shows the intended consumption of the KMP SDK from
SwiftUI via the `PortalIosClient` callback facade.

The sample has three destinations: Publish, Settings, and Activity.
Configuration and session state remain in one `StateObject` when switching
destinations. The publish screen offers a content picker (Snake game, Portal
explainer, API server, Minecraft server), every current public URL with
clipboard feedback and browser links; terminal-session URLs are hidden.

## Host app requirements

This directory contains a generated Xcode project (`project.yml` →
`xcodegen generate`) plus the SwiftUI sources. Regenerate it whenever
`project.yml` changes; the `.xcodeproj` is gitignored.

```bash
./scripts/build-ios-engine.sh            # native/ios/<target>/libportaltunnel.a
./gradlew :portal-sdk:assemblePortalSDKReleaseXCFramework
cd samples/ios && xcodegen generate
xcodebuild -project PortalSample.xcodeproj -scheme PortalSample \
  -destination 'generic/platform=iOS' -configuration Release build
```

`project.yml` links `PortalSDK.xcframework` (static) and
`native/ios/iosArm64/libportaltunnel.a`, and sets `DEVELOPMENT_TEAM` —
change it to your own team before building for a device.

The publish screen offers a content picker (Snake game, Portal explainer,
API server, Minecraft server) with every current public URL, clipboard
feedback, and browser links; terminal-session URLs are hidden.
Configuration and session state remain in one `StateObject` when switching
destinations. There is no simulated connection or preview engine.

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

`libportaltunnel.a` is built from `native/bridge` — a clean-room
reimplementation of the removed upstream Go mobile bridge over
`sdk.Exposure` (portal-tunnel v2.4.3). `scripts/build-ios-engine.sh`
produces one archive per target (iosArm64 device, iosSimulatorArm64,
iosX64) and the cgo header is verified against
`native/include/portaltunnel.h`. The archives are gitignored; rebuild them
after pulling bridge changes.

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
