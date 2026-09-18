# SDK Usability Implementation Plan

Date: 2026-09-18
Status: partially implemented; P4 release/distribution and platform verification remain open

## 1. Goal

Make the first successful Portal publication small, safe, and reproducible on
Android and iOS without weakening the existing lifecycle, security, or wire
contracts.

Target first-use flows:

- Android: add released dependencies, create a context-backed client, publish a
  loopback HTTP service, and obtain a public URL in at most 10 lines of app
  code. No caller-provided identity path.
- iOS: install one released package/artifact, publish a loopback HTTP service,
  and obtain a public URL in at most 15 lines of Swift. No caller-provided
  identity path, `KotlinBoolean`, all-fields `PortalConfig` initializer, Go
  build, or manual `libportaltunnel.a` link.
- Advanced callers retain the existing raw `PortalConfig`, `open`, state,
  events, relay mutation, metadata mutation, retryable stop, and owner-scoped
  close APIs.

## 2. Non-goals

- Do not change the v1 C ABI, wire keys, serialized defaults, or
  `encodeDefaults=false`.
- Do not redesign transport behavior, relay discovery, identity format, or
  readiness inference.
- Do not make Android background execution automatic. Apps still explicitly
  own a foreground service.
- Do not promise indefinite iOS background execution.
- Do not hide `PortalFailure` or replace authoritative `StateFlow` state with
  callbacks/events.
- Do not remove the raw constructor or builder; they remain advanced escape
  hatches.

## 3. Contracts that must remain invariant

1. `PortalClient` owns every session it creates; `close()` affects only those
   sessions.
2. `open()` means native start accepted and ownership registered, not ready.
3. `PortalTunnel.state` is authoritative; events remain bounded and auxiliary.
4. Cancellation propagates as `CancellationException`.
5. A failed `stop()` leaves the session registered in `STOPPING` and retryable.
6. Terminal sessions never resurrect after late events.
7. Identity documents and facilitator tokens never enter logs or diagnostics.
8. Every convenience config reaches the existing `ConfigValidation.validate`
   path before the native call.
9. `PortalConfig` remains the v1 serializable DTO. Convenience APIs only
   construct or consume it.

## 4. Target public API

Names below are the implementation contract. If Kotlin/Native exports a Swift
name differently, adjust only the iOS facade spelling and lock the actual name
with the Swift compile fixture; do not distort the common Kotlin API.

### 4.1 Intent-oriented `PortalConfig` factories

Add a `companion object` to `PortalConfig`:

```kotlin
public companion object {
    public fun http(
        targetAddress: String,
        name: String? = null
    ): PortalConfig

    public fun routes(
        routes: List<PortalHTTPRoute>,
        name: String? = null
    ): PortalConfig

    public fun tcp(name: String? = null): PortalConfig

    public fun udp(
        targetAddress: String,
        name: String? = null
    ): PortalConfig

    public fun staticSite(
        directory: String,
        index: String = "index.html",
        name: String? = null
    ): PortalConfig
}
```

Factory mappings:

| Factory | Exact DTO fields set |
|---|---|
| `http` | `name`, `targetAddr` |
| `routes` | `name`, `httpRoutes` |
| `tcp` | `name`, `tcp=true` |
| `udp` | `name`, `udp=true`, `udpAddr=targetAddress` |
| `staticSite` | `name`, `staticDir=directory`, `staticIndex=index` |

All other fields keep `PortalConfig` defaults. Factories do not duplicate
validation. Invalid input fails from `PortalClient.open/publish` through the
existing structured `PortalException` contract.

Keep `portalConfig {}` and `PortalConfig.Builder`. Do not add a second options
object in this change; that would recreate the flat DTO under another name.

### 4.2 Ready-on-return publication

Add to `PortalClient`:

```kotlin
public suspend fun publish(
    config: PortalConfig,
    timeoutMillis: Long = 30_000
): PortalTunnel
```

Semantics:

1. Call `open(config)`; do not duplicate validation, normalization, identity
   defaults, registration, or event buffering.
2. Call `tunnel.awaitActive(timeoutMillis)`. Under ABI v1, ACTIVE is the only
   all-requested-capabilities readiness signal.
3. Return the same `PortalTunnel` instance after ACTIVE.
4. If readiness fails, enter `NonCancellable`, call `tunnel.stop()`, then
   rethrow the readiness failure.
5. If readiness cleanup also fails, surface the cleanup `PortalException`
   because a registered `STOPPING` session needs operator action. Set
   `operation="publish_cleanup"` and include the original readiness code in
   the message. The session remains discoverable through `client.sessions` so
   `stop()` can be retried.
6. If publication is cancelled, propagate the original
   `CancellationException` after non-cancellable cleanup. A cleanup failure
   remains observable on the session snapshot/diagnostics and must not replace
   cancellation.
7. If the tunnel became terminal before readiness, preserve the existing
   `TUNNEL_CLOSED` behavior.

`open()` remains unchanged and documented as the advanced accepted-before-ready
operation.

### 4.3 Android lifecycle entry point

Clean cutover; update every repository caller in the same change:

```kotlin
public fun PortalClientHolder.init(
    context: Context,
    allowRemoteTargets: Boolean = false
): PortalClient
```

Implementation requirements:

- Store no `Activity` or `Service` reference.
- Pass `context.applicationContext` to `PortalClient(context, ...)`.
- Keep initialization synchronized and idempotent.
- A later `init` call returns the existing client; it does not mutate options.
- Change `PortalTunnelService.client` to
  `PortalClient(applicationContext)`.
- Update `SampleApp` to call `PortalClientHolder.init(this)`.

Do not retain the context-free `PortalClientHolder.init()` overload. The SDK is
not released and the old overload advertises an unsafe default; a compatibility
shim would preserve the bug.

### 4.4 iOS identity default and ergonomic facade

Keep `PortalIosClient(defaultIdentityPath:)` as an explicit override. When the
argument is null and a config supplies neither identity field:

1. Resolve the user-domain Application Support directory with Foundation.
2. Create a `Portal` subdirectory if absent.
3. Use `<Application Support>/Portal/identity.json`.
4. Perform resolution inside the `open/publish` operation so filesystem errors
   arrive through the completion handler, not as constructor exceptions.
5. Map directory creation/access failures to `PERMISSION_DENIED` with
   `operation="identity_path"`; never fall back to a temporary or cache path.
6. If the config already supplies `identityJson` or `identityPath`, do no
   platform path work.

Add an iOS-only factory facade returning common `PortalConfig` values:

```kotlin
public object PortalIosConfigFactory {
    public fun http(targetAddress: String, name: String?): PortalConfig
    public fun routes(routes: List<PortalHTTPRoute>, name: String?): PortalConfig
    public fun tcp(name: String?): PortalConfig
    public fun udp(targetAddress: String, name: String?): PortalConfig
    public fun staticSite(directory: String, index: String, name: String?): PortalConfig
}
```

The facade delegates to the common factories and exists only to guarantee a
short, stable Swift spelling. Do not duplicate field mapping.

Add to `PortalIosClient`:

```kotlin
public fun publish(
    config: PortalConfig,
    timeoutMillis: Long,
    completion: (PortalIosSession?, PortalFailure?) -> Unit
): PortalOperation
```

It delegates to `PortalClient.publish`. Completion fires once on the main
dispatcher. Cancellation keeps the current contract: cancelling the returned
operation cancels work and does not invoke completion.

## 5. Intended quick starts

### Android

```kotlin
val client = PortalClient(applicationContext)
val tunnel = client.publish(
    PortalConfig.http("127.0.0.1:8080", name = "device-api")
)
val publicUrl = requireNotNull(tunnel.state.value.primaryPublicUrl)
```

For process ownership:

```kotlin
PortalClientHolder.init(this)
PortalClientHolder.open(
    PortalConfig.http("127.0.0.1:8080", "device-api")
) { result -> /* observe state or failure */ }
```

The holder callback remains accepted-before-ready in this phase. Do not
silently change its semantics; add a separately named `publish` callback only
if the sample needs process-owned ready-on-return behavior.

### Swift

The exact generated spelling is locked by the compile fixture. The intended
shape is:

```swift
let client = PortalIosClient()
let config = PortalIosConfigFactory.shared.http(
    targetAddress: "127.0.0.1:8080",
    name: "device-api"
)
operation = client.publish(config: config, timeoutMillis: 30_000) {
    session, failure in
    // session?.snapshot.primaryPublicUrl or structured failure
}
```

## 6. Implementation sequence

Each phase is independently reviewable and ends with a conventional commit.
Do not combine P4 release/distribution work with public API changes.

### P0 — Lock the easy-path contract

Files:

- `docs/SDK_USABILITY_IMPLEMENTATION_PLAN.md`
- `samples/android/src/main/java/org/gosuda/portal/sample/QuickStartContract.kt`
- `samples/ios/QuickStartContract.swift`
- `samples/ios/project.yml`
- `.github/workflows/gradle.yml`

Steps:

1. Add compile-only snippets containing exactly the intended quick-start calls.
2. Include the Swift file in the generated Xcode sample target.
3. Add the Android snippet to the existing sample source set so
   `:samples:android:compileDebugKotlin` checks it.
4. Add a macOS CI step that builds the generated iOS sample after the
   XCFramework and engine archive are available. Until native artifacts are
   distributable, keep this as a documented local/release check rather than a
   false green compile-only job.
5. Count only app-owned statements from client creation through public URL;
   imports, local server implementation, UI rendering, and cleanup are outside
   the line budget.

Acceptance:

- Android contract is at most 10 app-code lines.
- Swift contract is at most 15 app-code lines.
- Both fixtures use public APIs only.
- Renaming or changing a documented API breaks a compiled fixture.

Commit: `test(samples): lock quick-start api contracts`

### P1 — Make identity defaults platform-safe

Files:

- `portal-android-lifecycle/src/main/java/org/gosuda/portal/lifecycle/PortalClientHolder.kt`
- `portal-android-lifecycle/src/main/java/org/gosuda/portal/lifecycle/PortalTunnelService.kt`
- `portal-sdk/src/iosMain/kotlin/org/gosuda/portal/PortalIos.kt`
- new iOS-internal path helper under
  `portal-sdk/src/iosMain/kotlin/org/gosuda/portal/internal/`
- `samples/android/src/main/java/org/gosuda/portal/sample/SampleApp.kt`
- iOS tests under `portal-sdk/src/iosTest/`
- README lifecycle and identity examples

RED checks:

- Android lifecycle sample cannot compile after changing `init` until every
  caller passes context.
- iOS test opens a config without identity fields and asserts the config sent
  to the engine has a persistent Application Support path.
- iOS path failure test asserts `PERMISSION_DENIED`, operation
  `identity_path`, and a single completion.

GREEN implementation:

1. Change holder signature and service construction exactly as section 4.3.
2. Introduce an injectable internal iOS path resolver so filesystem success
   and failure are deterministic in tests; public API remains unchanged.
3. Resolve/copy identity fields before delegating to common `open/publish`.
4. Update samples and README in the same cutover.

Verification:

```text
./gradlew :portal-android-lifecycle:assembleRelease
./gradlew :samples:android:assembleDebug
./gradlew :portal-sdk:compileTestKotlinIosSimulatorArm64
# macOS with engine archives:
./gradlew :portal-sdk:iosSimulatorArm64Test
```

Runtime smoke:

- Delete app data, start one Android tunnel through the holder, and confirm the
  identity file is under `filesDir` and reused after Activity recreation.
- Delete the iOS app container, publish once, and confirm identity persistence
  across relaunch without passing a path.

Commit: `fix(identity): apply safe platform storage defaults`

### P2 — Add intent-oriented config factories

Files:

- `portal-sdk/src/commonMain/kotlin/org/gosuda/portal/PortalConfig.kt`
- `portal-sdk/src/iosMain/kotlin/org/gosuda/portal/PortalIos.kt` or a focused
  `PortalIosConfigFactory.kt`
- `portal-sdk/src/commonTest/kotlin/org/gosuda/portal/PortalConfigTest.kt`
- quick-start contract fixtures

RED checks, one observable contract per factory:

- HTTP sets only `target_addr` plus an optional name.
- routes preserve route order and do not synthesize static/upstream values.
- TCP sets `tcp=true`.
- UDP sets both `udp=true` and `udp_addr`.
- static site sets directory and the default/explicit index.
- serialized defaults remain absent.
- opening invalid factory output still returns the existing structured
  `INVALID_CONFIG` rather than a factory-specific exception.

GREEN implementation:

1. Add the common companion factories with direct DTO construction.
2. Add the iOS delegating facade.
3. Compile the generated Swift call shape and update the planned spelling if
   Kotlin/Native export rules require it.
4. Do not add aliases, deprecated factory names, or a parallel options model.

Verification:

```text
./gradlew :portal-sdk:testAndroidHostTest
./gradlew :portal-sdk:linuxX64Test
./gradlew :portal-sdk:compileKotlinIosArm64 \
  :portal-sdk:compileKotlinIosSimulatorArm64 \
  :portal-sdk:compileKotlinIosX64
```

Commit: `feat(config): add exposure-specific factories`

### P3 — Add rollback-safe publication

Files:

- `portal-sdk/src/commonMain/kotlin/org/gosuda/portal/PortalClient.kt`
- `portal-sdk/src/iosMain/kotlin/org/gosuda/portal/PortalIos.kt`
- `portal-sdk/src/commonTest/kotlin/org/gosuda/portal/PortalClientTest.kt`
- `portal-sdk/src/commonTest/kotlin/org/gosuda/portal/FakeEngine.kt`
- iOS facade tests
- quick-start contract fixtures

Extend `FakeEngine` only with controls needed to observe readiness and cleanup;
do not introduce timing sleeps.

RED checks:

1. Immediate ACTIVE returns the same registered tunnel.
2. Delayed ACTIVE suspends and then returns after a status event.
3. Timeout stops the native handle and removes the session when stop succeeds.
4. Terminal-before-ready returns `TUNNEL_CLOSED` and leaves no live session.
5. Caller cancellation runs cleanup and propagates `CancellationException`.
6. Cleanup failure leaves the tunnel in `STOPPING`, keeps it in
   `client.sessions`, and reports `operation=publish_cleanup` for
   non-cancellation failures.
7. iOS completion fires once on success/failure and not after operation
   cancellation.

GREEN implementation:

1. Implement `PortalClient.publish` only by composing `open`, `awaitActive`,
   and `stop`.
2. Use `NonCancellable` only for cleanup, never for the readiness wait.
3. Preserve the original `PortalTunnel`; do not add a second publication
   wrapper type.
4. Delegate the iOS facade to the common operation and reuse existing
   completion/error translation helpers.

Verification:

```text
./gradlew :portal-sdk:testAndroidHostTest
./gradlew :portal-sdk:linuxX64Test
./gradlew :portal-sdk:compileTestKotlinIosSimulatorArm64
# macOS with engine archives:
./gradlew :portal-sdk:iosSimulatorArm64Test
```

Runtime smoke:

- Publish a real loopback HTTP endpoint through a relay.
- Fetch the public URL and assert the response body came from the local server.
- Repeat once with a deliberately unreachable relay/short timeout and confirm
  no native session remains after successful cleanup.

Commit: `feat(client): add ready-on-return publish`

### P4 — Make release artifacts installable

This phase starts only after resolving every release gate in
`native/source-lock.json`.

Files:

- `native/source-lock.json`
- `portal-sdk/build.gradle.kts`
- `portal-native-android/build.gradle.kts`
- `portal-android-lifecycle/build.gradle.kts`
- `scripts/build-ios-engine.sh`
- new deterministic XCFramework packaging script under `scripts/`
- new `Package.swift` and minimal Swift wrapper target only if required to link
  `Security`
- `.github/workflows/gradle.yml`
- `.github/workflows/publish.yml`

Release-gate steps:

1. Record the exact Android NDK revision used to produce both shipped `.so`
   files. If it cannot be proven from upstream provenance, rebuild from pinned
   source; do not guess.
2. Complete and record the MIT/binary redistribution review for Portal Tunnel
   and every bundled native artifact.
3. Recompute SHA-256 values and 16 KB ELF alignment checks after any rebuild.
4. Reject release while any lock field contains `UNRESOLVED`, `PENDING`,
   `MISSING`, or `TO_BE_FILLED`.

Android publication steps:

1. Publish `portal-native-android`, `portal-sdk`, and
   `portal-android-lifecycle` with one version.
2. Inspect generated POMs to ensure `portal-sdk` resolves the native Android
   artifact transitively.
3. Publish to Maven Local and build a clean external consumer using only
   released coordinates—no project substitution or repository source modules.
4. Verify arm64-v8a and x86_64 `.so` files survive AAR consumption and R8.

Apple packaging steps:

1. Build each pinned `libportaltunnel.a` slice.
2. Build each static `PortalSDK.framework` slice.
3. Merge the matching Kotlin framework archive and Go archive per platform
   using deterministic Apple tooling, then create one XCFramework.
4. Inspect architectures and exported `PortalStart`, `PortalStop`, and
   `PortalFreeString` symbols.
5. Build a clean Xcode consumer without manual library search paths or
   `-lportaltunnel`.
6. If `Security` is not linked transitively, provide a thin SwiftPM wrapper
   target with `.linkedFramework("Security")`; do not ask consumers to copy
   linker flags from documentation.
7. Zip deterministically, calculate SwiftPM checksum, and publish a versioned
   binary target.

Workflow correction:

- Replace the current GitHub-release-triggered publish workflow. Automatic
  execution is allowed only for `release-*` tags whose pointed commit subject
  starts with `release(scope):`.
- Keep `workflow_dispatch` for manual verification/reruns.
- Add an explicit guard that checks both tag and commit subject before any
  publish step.

Verification:

- Clean Gradle consumer resolves from Maven Local/staging and runs an Android
  real-relay smoke.
- Clean SwiftPM consumer resolves the zipped XCFramework and runs simulator and
  physical-device real-relay smokes.
- `native/source-lock.json` has no unresolved marker.
- Release workflows reject an ordinary tag and an ordinary feature commit.

Commits:

- `build(native): resolve release provenance gates`
- `build(apple): package self-contained portal xcframework`
- `ci(release): gate artifact publication`

### P5 — Cut documentation and samples over

Files:

- `README.md`
- `samples/android/`
- `samples/desktop/`
- `samples/ios/`
- `.github/workflows/publish.yml`
- `.agents/skills/portal-multiplatform-sdk/` API reference/examples
- `docs/DESIGN_RULES.md`
- `CHANGELOG.md`
- `docs/WORK_CHECKPOINT.md`

Steps:

1. Make ready-on-return HTTP publication the first README example.
2. Put installation before architecture; show only released coordinates and the
   verified SwiftPM/XCFramework path.
3. Move raw `PortalConfig`, `open`, event details, and diagnostics into an
   advanced section without deleting them.
4. Add small recipes for HTTP routes, TCP, UDP, static content, Android
   foreground ownership, identity overrides, and structured failures.
5. Migrate both sample apps to factories and `publish` for their primary path.
6. Keep one advanced sample path using `open` to demonstrate early observation
   and accepted-before-ready semantics.
7. Update the skill reference in the same commit so generated guidance does
   not teach obsolete entry points.
8. Update `DESIGN_RULES.md` with the additive publish and platform-default
   contracts.
9. Add past-tense changelog entries under the current date.
10. After publication, build Android and Desktop samples against the exact
    Maven Central release with no staging repository, Maven Local, project
    substitution, or same-invocation SDK publication.
11. Replace the iOS sample's repository-local XCFramework dependency with the
    matching versioned remote Swift package, retaining local XCFramework
    assembly only as a pre-release artifact check.
12. Add an independent post-release workflow that proves every sample resolves
    the just-published artifacts and rejects `build/sample-maven`, `dist/`, and
    repository SDK project modules as dependency sources.

Verification:

- Every README/skill snippet matches a compiled contract fixture.
- Every local Markdown link resolves and code fences are balanced.
- GitHub rendering shows the intended quick-start order.
- Android, Desktop, and iOS samples build from clean state against the exact
  published release, not artifacts produced by the same checkout.
- The sample dependency graph contains no SDK project component, staging
  repository, Maven Local artifact, or local XCFramework.
- Real-relay smoke passes on Android, Desktop, and iOS after the sample
  migration.

Commit: `docs(onboarding): lead with ready-to-use publishing`

## 7. Migration table

| Current call | Replacement in repository | Reason |
|---|---|---|
| `PortalClient()` in Android quick start | `PortalClient(applicationContext)` | writable persistent identity default |
| `PortalClientHolder.init()` | `PortalClientHolder.init(context)` | same safe default for process owner |
| service `PortalClient()` | `PortalClient(applicationContext)` | same safe default for service owner |
| raw HTTP `PortalConfig(...)` | `PortalConfig.http(...)` | remove unrelated fields |
| raw UDP `PortalConfig(udp=true, udpAddr=...)` | `PortalConfig.udp(...)` | prevent half-configured UDP intent |
| `open(config); awaitActive()` | `publish(config)` | one rollback-safe ready-on-return operation |
| Swift all-fields config init | `PortalIosConfigFactory` | stable small Swift surface |
| caller-supplied iOS identity path | `PortalIosClient()` default | persistent platform-owned storage |
| manual `libportaltunnel.a` link | released Swift package/XCFramework | one installable artifact |

No deprecated aliases are planned because no public release exists. All
repository call sites move in the same phase as their API cutover.

## 8. Verification matrix

| Contract | Automated check | Runtime proof |
|---|---|---|
| Wire/default stability | `PortalConfigTest`, Android host tests | none needed |
| Factory mappings | common tests + Swift compile fixture | sample config inspection |
| Ownership/rollback | `PortalClientTest` with `FakeEngine` | failed-ready cleanup smoke |
| Android identity default | sample compile + platform path check | reinstall/relaunch device smoke |
| iOS identity default | iOS resolver/facade tests | relaunch simulator/device smoke |
| Native adapters | linuxX64 stub + iOS engine tests | real relay |
| Android distribution | clean external Gradle consumer | APK/R8/device smoke |
| Apple distribution | clean SwiftPM/Xcode consumer | simulator + device smoke |
| Docs | compiled snippets + link/fence check | GitHub rendered review |

Final local Gradle gate:

```text
./gradlew :portal-sdk:testAndroidHostTest \
  :portal-sdk:linuxX64Test \
  :portal-sdk:bundleAndroidMainAar \
  :portal-native-android:assembleRelease \
  :portal-android-lifecycle:assembleRelease \
  :samples:android:assembleDebug \
  :portal-sdk:compileKotlinIosArm64 \
  :portal-sdk:compileKotlinIosSimulatorArm64 \
  :portal-sdk:compileKotlinIosX64 \
  :portal-sdk:compileTestKotlinIosSimulatorArm64
```

macOS native/Apple gate:

```text
./scripts/build-ios-engine.sh
./gradlew :portal-sdk:iosSimulatorArm64Test \
  :portal-sdk:assemblePortalSDKReleaseXCFramework
cd samples/ios
xcodegen generate
xcodebuild -project PortalSample.xcodeproj -scheme PortalSample \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
```

## 9. Risks and decisions

### Readiness ambiguity

ABI v1 has no independent per-capability readiness. `publish` therefore waits
for ACTIVE, matching current `awaitActive`; it must not invent stronger
per-feature guarantees. ABI v2 can refine this later without changing the
ready-on-return intent.

### Cleanup failure after readiness failure

Hiding cleanup failure risks an unreachable native session. For ordinary
failures, cleanup failure takes precedence and is marked
`operation=publish_cleanup`. For cancellation, coroutine cancellation remains
primary by contract; the retained session is visible through client state.

### Swift export stability

Kotlin default arguments and companion objects do not always produce pleasant
Swift names. The iOS facade is allowed to adapt spelling, but only after the
actual generated framework is compiled from Swift. Documentation never guesses
the exported name.

### Distribution scope

A self-contained iOS artifact is a release requirement, not optional polish.
If static archive merging proves invalid, stop P4 and choose a supported
packaging mechanism based on linker evidence; do not document manual linking as
an easy path.

### Global Android ownership

`PortalClientHolder` remains explicit. The plan does not auto-install a global
client through a ContentProvider because hidden process initialization makes
ownership and shutdown harder to reason about.

## 10. Definition of done

The roadmap is complete only when all conditions hold:

- The Android and Swift target quick starts compile and meet their line budgets.
- Default identity persistence works across app relaunch on both platforms.
- `publish` is ready-on-return and cleanup behavior is covered for success,
  timeout, terminal failure, cancellation, and cleanup failure.
- Raw `open` behavior and all design invariants remain unchanged.
- Android consumes released Maven coordinates from a clean project.
- iOS consumes one released package/artifact without manual Go archive linking.
- Android and iOS real-relay requests reach an app-local HTTP server.
- Samples, README, skill reference, design rules, changelog, tasks, and
  checkpoint agree with the shipped API.
- Release provenance has no unresolved marker and release workflows obey the
  repository tag/commit-subject gate.
