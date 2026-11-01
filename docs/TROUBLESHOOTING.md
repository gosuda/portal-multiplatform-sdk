# Troubleshooting Log

## Decision tree — runtime symptoms → structured fields → fix

Start from the symptom, read the named field, apply the fix. All fields are
on `PortalFailure` (`tunnel.state.value.lastFailure`, the `PortalException`,
or the iOS completion's `PortalFailure`) unless noted.

### "No public URL" — tunnel runs but `publicUrls` stays empty

1. `snapshot.phase` is `CONNECTING` for a long time → the relay handshake is
   still in flight; keep waiting or check `snapshot.relays[].state`.
2. `snapshot.relays[].state == "failed"` → read `relay.failure`/`relay.error`;
   a `mitm` failure sets `snapshot.hasSecurityWarning` — do not trust that
   endpoint, switch networks or relays.
3. `snapshot.lastFailure.code == "RELAY_UNAVAILABLE"` → the configured relay
   is unreachable; verify the URL (`https`, no `ws://`), or enable discovery.
4. `snapshot.lastFailure.code == "NETWORK_UNAVAILABLE"` → device connectivity;
   retry when the network returns (`retryable == true`).

### Relay connection failure

- `code == "RELAY_UNAVAILABLE"` → relay refused or unreachable; check the URL
  and that the relay serves the v1 protocol.
- `code == "INVALID_CONFIG"` with a relay message → scheme/host rejected:
  `https` only, `http` only for loopback, no user-info, port 1–65535.
- `hasSecurityWarning == true` → MITM self-probe failed on a relay; treat the
  endpoint as untrusted.

### Local upstream refusal (tunnel ACTIVE, requests fail)

- `code == "INVALID_CONFIG"` mentioning `target_addr`/`udp_addr` →
  non-loopback target without `allowRemoteTargets`, or a missing port.
- Requests time out while the tunnel is ACTIVE → the app-local server is not
  listening on the configured loopback address; verify `target_addr` matches
  the server's bind address and that the server started before `publish`.

### Permission / background limits

- `code == "PERMISSION_DENIED"`, `operation == "identity_path"` → the
  platform identity directory could not be created; check app storage
  permissions (Android `filesDir`, iOS Application Support, desktop app dir).
- Tunnel dies when the app backgrounds → Android: move the tunnel to a
  `PortalTunnelService` foreground service; iOS: no indefinite background
  execution is promised — keep the app foregrounded.

### Shutdown failure

- `stop()` threw → the session stays registered in `STOPPING`
  (`snapshot.phase`), retry `stop()`; `lastFailure` holds the native error.
- `publish` failed with `operation == "publish_cleanup"` → readiness failed
  AND rollback failed; `failure.readinessFailure` has the original cause and
  the session remains in `client.sessions` for a manual `stop()` retry.

### Native engine problems

- `code == "NATIVE_UNAVAILABLE"` → engine binary missing/mismatched:
  Android ABI not shipped (arm64-v8a/x86_64 only), desktop packaged runtime
  absent or hash-mismatched, iOS archive not embedded in the klib.
- `code == "ABI_MISMATCH"` → engine older/newer than ABI v1; rebuild from
  `native/bridge` at the pinned portal-tunnel version.

### [2026-11-01] Cross-app pasteboard reads stalled the iOS UI smoke runner

- **Context / Symptom:** The throwaway UI workflow stalled after copying a
  password, then exceeded its command deadline. The runner logged
  `This application is not expected to prompt for paste authorization`.
- **Root Cause:** The test runner read another app's `UIPasteboard.general.string`
  directly, crossing iOS paste authorization rather than exercising a user paste.
- **Solution:** Kept the real Copy action, but authenticated the public endpoint
  using the custom password actually typed in the secure field. Cache reuse was
  checked after relaunch by scrolling the verified model card into view.
  No production permission bypass, clipboard entitlement or test-only app hook
  was introduced.

### [2026-11-01] AGP rejected a task provider in the sample assets SourceSet

- **Context / Symptom:** SDK staging stopped during Android sample
  configuration: `You cannot add Provider instances to the Android SourceSet API`.
  The new generated native-license asset used `assets.srcDir(taskProvider)`.
- **Root Cause:** Current AGP requires the Variant Sources API to distinguish
  generated read-only assets from static editable source directories.
- **Solution:** Declared a typed notice task with an output `DirectoryProperty`
  and wired it through `variant.sources.assets.addGeneratedSourceDirectory`.
  Static shared Java/assets use `directories` rather than deprecated `srcDir`.
  The disallow-provider safety check remains enabled.
- **Related source-set migration:** Built-in Kotlin does not compile custom
  `AndroidSourceSet.java` Kotlin roots. The shared JVM code must use
  `sourceSets["main"].kotlin.directories`; otherwise model types appear unresolved.
  See [AGP built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin).

### [2026-11-01] Android API 26 was rejected by the sample CMake guard

- **Context / Symptom:** Configure failed with `requires Android API 26 or newer`
  even though AGP passed `-DANDROID_PLATFORM=android-26`.
- **Root Cause:** NDK's legacy toolchain intentionally sets
  `CMAKE_SYSTEM_VERSION` to 1 to inhibit CMake's separate NDK handling.
- **Solution:** Checked the NDK's effective `ANDROID_PLATFORM_LEVEL` instead.
  The minimum remains API 26; no guard or NDK safety option was disabled.

### [2026-11-01] Domain edits did not apply and samples exposed only long canonical URLs

- **Context / Symptom:** Public URLs included the identity's 40-hex suffix;
  editing the old display-name field did not reliably change the hostname.
- **Root Cause:** Upstream `identity.LoadOrCreate` retains an existing stored
  name; the bridge also omitted the granted `AliasURL` from relay status.
- **Solution:** Added explicit `domain_prefix` / `withDomainPrefix` to override
  only the in-memory lease name. Kept stored signing keys/identity unchanged.
  Forwarded relay-granted aliases and preferred them only while ACTIVE/ready.
  All workbenches expose Domain prefix and use the SDK preferred copy/open URL.
- **Prevention / Reference:** Never strip the canonical address to invent an
  alias. Collisions can decline the short alias; reconnect/stop can release it.
  Native identity-preservation and SDK lease-transition regressions cover this.

### [2026-11-01] Engine migration retained vulnerable indirect JOSE and QUIC versions

- **Context / Symptom:** The first feature push printed an outdated total
  alert count; the authenticated Dependabot API showed four open alerts in
  `native/bridge/go.mod`: three for go-jose and one for quic-go.
- **Root Cause:** portal-tunnel v2.7.1 still selected go-jose v4.1.4 and
  quic-go v0.59.0. Pruning obsolete dependencies did not patch retained ones.
- **Solution:** Ran `go get github.com/go-jose/go-jose/v4@v4.1.6
  github.com/quic-go/quic-go@v0.59.1` and `go mod tidy`. These explicit
  indirect selections satisfy the advisories' patched version ranges.
  Native outputs and consumer apps must be rebuilt before device delivery;
  editing the manifest alone does not patch an already installed APK.
- **Prevention / Reference:** Inspect current authenticated alert details,
  not only the push banner. Keep the patched selections when upgrading the
  engine; the four alert records were #1, #12, #23 and #24.

### [2026-11-01] Obsolete desktop resource entries survived an incremental cutover

- **Context / Symptom:** The desktop JAR still listed `site/` and
  `site-explainer/` after those source resources were removed.
- **Root Cause:** Existing incremental resource output remained in the local
  build directory; source deletion alone did not give a clean artifact inventory.
- **Solution:** Cleaned only `:samples:desktop` and rebuilt the distribution.
  The new JAR contains the used icon/classes; arcade files are packaged once
  through Compose application resources.
- **Prevention / Reference:** Inspect packaged inventories during asset
  cutovers. This is separate from the iOS cinterop input bug: that required
  explicit archive tracking, not a blanket clean workaround.

### [2026-11-01] Desktop packaging could not use Android Studio JBR or Homebrew JDK

- **Context / Symptom:** `createDistributable` failed with `jpackage is missing`
  for Android Studio's JBR; the available Homebrew JDK was then rejected by
  Compose's packaging-vendor check.
- **Root Cause:** The IDE runtime was stripped of packaging tools. Compose
  separately guards a known Homebrew packaging issue; having `java`/`javac`
  was not sufficient.
- **Solution:** Selected the JDK 17 compiler toolchain for desktop packaging
  and installed a checksum-verified full Temurin 17 distribution. Kept runtime
  and vendor checks enabled rather than suppressing the failure.
- **Prevention / Reference:** Use a full supported JDK for `jpackage`; see
  https://github.com/JetBrains/compose-multiplatform/issues/3107.

### [2026-11-01] One advertised public TCP relay port was unreachable

- **Context / Symptom:** A TCP connect to a ready `gosunuts.xyz:40004` endpoint
  timed out from this host. The same Android-owned listener returned an exact
  byte echo through another ready relay, `s-h.day:50008`.
- **Root Cause:** Readiness describes an allocated endpoint, not a successful
  connection from every caller network. The specific remote routing/port
  cause was not observable from this checkout.
- **Solution:** Exercised the actual protocol through the reachable ready
  relay. No relay blacklist, synthetic success or SDK retry policy was added.
- **Prevention / Reference:** Check real TCP/UDP traffic, not HTTPS alone;
  select another ready endpoint or configure explicit compatible relays.

### [2026-11-01] Static-site smoke assumed an unsupported 404 contract

- **Context / Symptom:** A new missing-file assertion failed although the
  published HTML and JavaScript module were served correctly.
- **Root Cause:** Upstream `sdk.HTTPRouteConfig.StaticIndex` and
  `utils.NewStaticSiteHandler` explicitly serve the SPA/CSR entry for unknown
  paths. The SDK never promised 404 for these paths.
- **Solution:** Removed the invented assertion, without changing or repinning
  native fallback behavior. Static smoke checks actual HTML and module MIME
  types; browser play verifies the arcade.

### [2026-11-01] iOS klibs retained an old engine after a native upgrade

- **Context / Symptom:** The iOS workbench still logged
  `relay sdk protocol version mismatch: relay="10" client="9"` after the Go
  archives were rebuilt for v2.7.1. Kotlin framework compilation and ABI
  symbol verification succeeded, but actual publishing timed out.
- **Root Cause:** Cinterop embedded `libportaltunnel.a` in its klib, while its
  task tracked the stable `.def` path rather than the archive contents.
  Rebuilding the engine did not invalidate the existing embedded klib.
- **Solution:** Declared each target's native archive as an explicit input
  of its cinterop task. Ordinary incremental assembly now rebuilds the klib
  when that archive changes; no clean or forced task rerun is required.
- **Prevention / Reference:** Exercise a public request after incremental
  engine upgrades. Source-version strings and exported ABI names do not
  prove which native implementation a consumer actually linked.

### [2026-11-01] Desktop window cleanup required a missing Main dispatcher

- **Context / Symptom:** Closing the desktop window stopped its tunnel but
  left the window open. A direct controller smoke reproduced
  `Module with the Main dispatcher is missing`.
- **Root Cause:** The teardown callback used `Dispatchers.Main`, but the
  desktop runtime had no Main-dispatcher provider.
- **Solution:** Posted the final window-exit callback to the JDK AWT event
  queue after owned SDK/session/listener cleanup. This used the existing UI
  event loop instead of adding another coroutine-dispatcher dependency.
- **Prevention / Reference:** Verify native window close, not only tunnel
  stop or compilation.

### [2026-11-01] HTTP readiness incorrectly implied UDP readiness

- **Context / Symptom:** The real-relay UDP smoke received an ACTIVE session
  before its ready relay had allocated a UDP endpoint.
- **Root Cause:** Capability readiness reused HTTP lease readiness for raw
  transports, although endpoint allocation completed later.
- **Solution:** Required a nonblank `tcp_addr` or `udp_addr` on a ready relay
  for the respective transport. Missing or withdrawn endpoints leave the
  session CONNECTING. The all-mode public smoke then passed exact TCP/UDP
  echo round trips as well as HTTP, routes and static files.

### [2026-11-01] macOS system Python could not negotiate the tunnel's TLS

- **Context / Symptom:** Python 3.9 urllib requests failed with an unexpected
  TLS EOF while current curl reached the same published service.
- **Root Cause:** The system Python used LibreSSL 2.8.3 and reported
  `ssl.HAS_TLSv1_3 == False`; the published tunnel negotiated TLS 1.3.
- **Solution:** Used a TLS-1.3-capable client for runtime smoke requests.
  Current curl completed certificate verification and the public request;
  neither server TLS nor certificate verification was weakened.

### [2026-11-01] Recompiling a running desktop sample invalidated lazy class loads

- **Context / Symptom:** Clicking Publish in a long-lived development run
  reported `NoClassDefFoundError: org/gosuda/portal/sample/WorkbenchRequest`
  while a concurrent Gradle build regenerated its classes.
- **Root Cause:** The JavaExec process loaded directly from mutable build
  output directories; already-loaded classes survived while other class files
  were being replaced.
- **Solution:** Stopped the old sample and launched it after compilation
  completed. The fresh UI published a static page and served a real request.
- **Prevention / Reference:** Finish compilation before UI smoke; do not
  rebuild the same JavaExec class directories under a running sample.

### [2026-11-01] Android native task received a Provider instead of the SDK path

- **Context / Symptom:** Android Maven staging reported
  `Android NDK 29.0.14206865 not found` even though that exact revision was
  installed under the Android SDK.
- **Root Cause:** `Exec.environment("ANDROID_HOME", provider)` stringified the
  Gradle Provider instead of supplying its resolved SDK directory.
- **Solution:** Resolved the provider inside the task's `doFirst`, preserving
  lazy Android SDK access while passing an actual filesystem path. Android
  JNI assembly, ABI/16 KiB checks and Maven staging then completed.

### [2026-11-01] Operating relays rejected the bundled engine protocol

- **Context / Symptom:** The real-relay sample smoke failed on multiple
  discovered relays with `relay sdk protocol version mismatch:
  relay="10" client="9"` and ultimately a readiness timeout.
- **Root Cause:** The bridge still pinned portal-tunnel v2.4.3 while current
  relays used protocol 10.
- **Solution:** Pinned upstream v2.7.1, migrated its current identity and HTTP
  routing APIs, rebuilt the source-based native artifacts and pruned the Go
  module graph. The linked graph no longer includes the previous
  `go-ethereum` dependency. Removed upstream ECH/x402/method-route options
  are explicitly rejected as `UNSUPPORTED_CAPABILITY`, not ignored.
- **Prevention / Reference:** Run the explicit real-relay smoke when updating
  the engine; ABI v1 compatibility alone does not prove relay-protocol
  compatibility. See [upstream v2.7.1](https://github.com/gosuda/portal-tunnel/releases/tag/v2.7.1).

### [2026-11-01] SDK validation and recovery regressions

- **Context / Symptom:** Current regression tests run against the original
  `ea6dad8` common implementation failed 10 of 53 cases: IPv6, routed
  upstream/static-path policy, wildcard targets, live relay edits, UTF-8
  metadata limits and diagnostic secret exposure. A separate iOS regression
  reproduced a closed-owner state callback running off Main.
- **Root Cause:** Startup and live validation differed; HTTP routes bypassed
  target/static checks; IPv6 parsing was followed by a colon rejection;
  metadata used a UTF-16 approximation; raw parser/failure text reached
  diagnostics. Closed observation called back synchronously on the caller.
- **Solution:** Unified validation, applied the explicit remote-target policy,
  counted UTF-8 bytes, sanitized diagnostic exports without replacing typed
  context, exposed iOS owned sessions for cleanup retry and dispatched final
  observation on Main. The corrected common regressions and iOS lifecycle
  cases passed through their platform test runners.
- **Prevention / Reference:** Keep behavior regressions in `PortalClientTest`,
  `PortalConfigTest` and `IosLifecycleTest`; raw snapshots/native errors are
  not sanitized logging exports.

### [2026-11-01] Native provenance failed on macOS system Python

- **Context / Symptom:** Desktop/iOS engine builds failed with
  `AttributeError: module 'hashlib' has no attribute 'file_digest'` under
  `/usr/bin/python3` 3.9.6.
- **Root Cause:** Provenance and desktop verification used a Python 3.11 API.
  Provenance also queried Go outside `native/bridge`, reporting the shell's
  1.27.1 rather than the module-selected 1.27.3 used for compilation.
- **Solution:** Streamed SHA-256 input with the existing standard library,
  queried Go from the bridge directory and aligned source-lock/toolchain docs.
  Native build/artifact verification supports Python 3.9; the separate release
  gate still requires Python 3.11 (`tomllib`).

### [2026-11-01] Universal engine architecture verification rejected lipo arguments

- **Context / Symptom:** The real universal dylib existed, but verification
  stopped with `lipo: -verify_arch requires exactly one input file`.
- **Root Cause:** The installed lipo parser rejected the combined architecture
  argument layout used by the script.
- **Solution:** Verified one named architecture at a time with the binary
  first, then checked each slice's required exports. The macOS engine passed
  both slices, all 11 ABI exports, header comparison, system dependencies
  and streamed SHA-256/size verification.

### [2026-11-01] iOS demo mode restart collided with fixed listener ports

- **Context / Symptom:** A real HTTP → routes listener smoke failed with
  `POSIXErrorCode(rawValue: 48): Address already in use`.
- **Root Cause:** Demo modes reused fixed ports while Network.framework
  listener cancellation completed asynchronously.
- **Solution:** Bound loopback listeners to automatically assigned ports,
  passed their resolved addresses into SDK configuration and displayed the
  local endpoints. The same sample listener implementation then passed HTTP
  webhook/size-limit, two route upstreams, exact TCP echo and UDP echo smoke.
- **Prevention / Reference:** Avoid fixed demo ports and do not mistake an
  asynchronous cancellation request for immediate address availability.

### [2026-09-21] Linux Maven staging invoked the Apple toolchain

- **Context / Symptom:** Staging Android/Desktop artifacts failed in
  `:portal-sdk:buildIosEngine` with `xcrun: command not found`.
- **Root Cause:** Disabled iOS cinterop tasks still contributed their producer
  dependencies to the shared KMP metadata publication graph.
- **Solution:** Made the Apple engine producer macOS-only. The same staging
  command then completed with Apple production/cinterop skipped on Linux.
- **Prevention / Reference:** Run Android/Desktop staging on non-Apple hosts;
  do not treat disabled consumer tasks as an implicit producer host guard.

### [2026-09-21] SDK cancellation, identity, and secret-handling regressions

- **Context / Symptom:** Four targeted tests failed against the original
  common sources: explicit identity bypass, failed cancellation rollback,
  outer readiness timeout cancellation, and configuration string redaction.
- **Root Cause:** Default identity resolution ran despite inline credentials;
  failed rollback discarded ownership; an outer timeout was caught as a
  local readiness failure; generated data-class strings exposed secrets.
- **Solution:** Adopted handles before cancellation boundaries, retained
  STOPPING sessions on failed rollback, resolved defaults only when needed,
  used a local timeout boundary, and redacted only string representations.
  The corrected SDK passed Desktop, Android host, and Linux native suites.
- **Prevention / Reference:** Keep the added common regressions. Close retries
  failed stops; caller cancellation still propagates.

### [2026-09-21] JNI corrupted supplementary Unicode

- **Context / Symptom:** A host JNI consumer could not round-trip a Korean
  token secret containing a supplementary Unicode character through identity
  parsing. The original adapter failed; the corrected adapter passed.
- **Root Cause:** JNI modified UTF-8 was passed directly to the Go C ABI,
  which expects standard UTF-8.
- **Solution:** Converted UTF-16/standard UTF-8 explicitly at the boundary,
  contained callback errors, and rejected embedded NUL instead of truncating.
  The same real-engine smoke observed STOPPED after immediate proxy failure
  and confirmed that the native handle was no longer registered.

### [2026-09-21] Foreground-service stop retries lost their handles

- **Context / Symptom:** A throwaway Android host consumer failed against the
  original service: after one failed stop, a second service stop left the
  tunnel STOPPING instead of STOPPED.
- **Root Cause:** The service cleared its duplicate tunnel list even when
  native stop failed.
- **Solution:** Used `client.sessions` as the authoritative retry set. The
  identical host scenario passed with the corrected service.

### [2026-09-21] Desktop distributions omitted required JDK modules

- **Context / Symptom:** Full-JDK builds did not establish that the stripped
  runtime contained `HttpClient`, preferences, HTTP-server, and EC-provider
  modules used by sample content and HTTPS.
- **Root Cause:** Compose/jlink did not infer those application requirements.
- **Solution:** Declared the modules explicitly. A throwaway copy of the real
  packaged launcher ran the on-device HTTP smoke and a real-relay HTTP 200
  marker round-trip with `JAVA_HOME` pointing to a nonexistent directory.
- **Prevention / Reference:** Test the bundled runtime, not only Gradle's
  full-JDK `JavaExec` tasks. No GUI visual verification was performed here.

### [2026-09-21] Release workflow and Swift package were incomplete

- **Context / Symptom:** Actionlint rejected the original Dokka upload because
  required `path` was missing. The generated Swift manifest referenced a
  wrapper target whose sources were not distributed.
- **Root Cause:** Release-asset creation had not been checked as an actual
  consumer install path; a loose manifest is not a remote SwiftPM package.
- **Solution:** Added complete upload paths and a deterministic local-package
  ZIP containing the manifest, wrapper, and XCFramework. Publication now
  depends on the full platform matrix, including clean Swift package links.
- **Prevention / Reference:** Workflow validation passed locally; actual
  Apple linking still requires the macOS release verification job.


### [2026-09-19] `@Optional` input directory still failed validation on fresh clones

- **Context / Symptom:** `./gradlew :portal-sdk:compileKotlinDesktop` on a
  clone without `native/desktop/` failed with `Input file does not exist …
  property 'nativeDesktopDir'` even though the property was annotated
  `@Optional`.
- **Root Cause:** `@Optional` permits a null/unset value but does not
  suppress existence validation for a set `@InputDirectory` — the directory
  was always set, so Gradle still demanded it exist.
- **Solution:** Mark the directory `@Internal` and track the expected
  binaries through a `ConfigurableFileCollection` `@InputFiles` input, which
  skips missing entries. `requireComplete` remains the release gate that
  turns absence into an error.
- **Prevention / Reference:** For "may not exist" inputs prefer file
  collections over `@Optional` scalar/directory inputs; verify on a clean
  checkout, not only where the directory already exists.

### [2026-09-19] XCFramework symbol check failed on macOS despite present symbols

- **Context / Symptom:** `scripts/package-ios-xcframework.sh` reported
  `PortalStart missing` from the simulator framework even though `nm`
  listed the symbol.
- **Root Cause:** `nm -g … | grep -Eq` — `grep -q` exits on the first match
  and closes the pipe; `nm` kept writing to the large static archive and
  died with SIGPIPE (141), which `set -o pipefail` turned into a pipeline
  failure. Linux runs never hit it because the archive was smaller.
- **Solution:** Consume the full listing (`grep -E … >/dev/null`) instead of
  `grep -q` inside `pipefail` pipelines.
- **Prevention / Reference:** Under `pipefail`, never pair `grep -q` with a
  producer that writes more than a few lines; redirect to `/dev/null` or
  capture then match.

### [2026-09-19] iOS sources had never compiled on macOS

- **Context / Symptom:** First `compileKotlinIosSimulatorArm64` on a macOS
  host failed: `IosIdentityPath.kt` used `NSFileManager` without the
  `ExperimentalForeignApi` opt-in.
- **Root Cause:** Previous verification ran on Linux where Apple cinterop is
  disabled, so the opt-in error was never compiled.
- **Solution:** Added `@OptIn(ExperimentalForeignApi::class)`; all three iOS
  variants now compile and `iosSimulatorArm64Test` runs 47 tests green.
- **Prevention / Reference:** Platform-gated source sets need a real host
  build in the verification matrix; dry-run task-graph checks do not compile.

### [2026-09-19] Desktop tests hardcoded Linux assumptions

- **Context / Symptom:** `desktopTest` on macOS failed: the JNA stub was
  built as `libportaltunnel_stub.so` (rejected — macOS needs `.dylib`), and
  two tests asserted `DesktopOs.LINUX`/`linux-x64` unconditionally.
- **Root Cause:** The stub task and the packaged-runtime tests were written
  and only ever run on the Linux checkpoint host.
- **Solution:** The stub extension now follows the host OS
  (`.so`/`.dylib`/`.dll`); `runtimeReportsPackagedSource` and
  `packagedResourceResolvesAndLoads` derive expected OS/arch from
  `os.name`/`os.arch`.
- **Prevention / Reference:** Any test that loads a real binary must
  parameterize host OS/arch; hardcoding the CI host's platform is a latent
  cross-platform failure.


### [2026-09-18] Downloaded desktop matrix was flattened and appeared missing

- **Context / Symptom:** The publish job downloaded all three successful
  desktop artifacts, but `GenerateNativeIndex` reported every expected binary
  missing under `native/desktop/<target>/`.
- **Root Cause:** `actions/download-artifact` used a wildcard with
  `merge-multiple: true`. Each upload's root was already the contents of its
  target directory, so merging flattened all files directly into
  `native/desktop/`.
- **Solution:** Download each named artifact explicitly into its exact
  `native/desktop/linux-x64`, `windows-x64`, or `macos-universal` directory in
  both CI and publication workflows.
- **Prevention / Reference:** Artifact upload/download roots are content
  boundaries, not preserved parent paths; reconstruct matrix directories
  explicitly before packaging.

### [2026-09-18] Published iOS SDK required consumers to link the Go archive

- **Context / Symptom:** KMP/Swift consumers could compile Portal APIs but had
  to build and link a matching `libportaltunnel.a` plus Security manually.
- **Root Cause:** The cinterop definition generated bindings only; target
  archives were linked from repository paths during framework/test builds and
  were absent from published klibs.
- **Solution:** Generate a target-specific `.def` with `staticLibraries`,
  `libraryPaths`, and Security `linkerOpts`; make every Apple cinterop depend
  on the source archive build. Published klibs and XCFramework slices are now
  self-contained.
- **Prevention / Reference:** For a private native implementation dependency,
  inspect the published `.klib` and require the static archive entry; a green
  producer build alone does not prove consumer packaging.

### [2026-09-18] Clean release runner could not write native `index.json`

- **Context / Symptom:** `publishDesktopSdkToSampleRepository` failed on the
  macOS runner with `FileNotFoundException:
  portal-native-desktop/build/generated/portal-native/index.json`.
- **Root Cause:** `GenerateNativeIndex` deleted its output directory, then
  only recreated target subdirectories while copying binaries. On a clean
  runner no directory existed when it wrote the top-level index.
- **Solution:** Recreate the declared output directory immediately after
  cleanup. Also stream binaries through `DigestInputStream` so hashing the
  complete release matrix does not allocate one byte array per native binary.
- **Prevention / Reference:** A task owning an `@OutputDirectory` must create
  that directory before writing root-level outputs; never rely on stale build
  directories from earlier tasks.

### [2026-09-18] AGP rejected a Provider-backed JNI source directory

- **Context / Symptom:** `:portal-native-android:buildAndroidEngine` failed
  during configuration with `You cannot add Provider instances to the Android
  SourceSet API`.
- **Root Cause:** AGP cannot classify a `DirectoryProvider` passed through the
  legacy `sourceSets.main.jniLibs.srcDir` API as generated or static.
- **Solution:** Resolve the deterministic build-directory path to a `File`
  when registering it as the JNI source directory, and keep explicit
  `dependsOn(buildAndroidEngine)` wiring on native merge/AAR tasks.
- **Prevention / Reference:** Use AGP's variant generated-source API when a
  custom task exposes a typed `DirectoryProperty`; otherwise pass a concrete
  path and wire the producer task explicitly.

### [2026-09-18] Multiplatform publication stopped at the source-lock gate

- **Context / Symptom:** `Publish Multiplatform SDK` stopped with
  `native/source-lock.json still has unresolved release gates` after native
  runtime builds completed.
- **Root Cause:** `portal-native-android` shipped two `.so` files copied from
  `gosuda/portal-android-sdk`. That repository has no LICENSE, so the files
  had neither a reproducible build path here nor established redistribution
  terms. The generic grep check also matched its own policy sentence and ran
  only after the expensive desktop matrix.
- **Solution:** Deleted the inherited binaries. Added an Android JNI shim to
  `native/bridge` and `build-android-engine.sh`; Gradle now builds arm64-v8a
  and x86_64 libraries with Go 1.27.1 and NDK r29 before AAR assembly or
  publication. CI installs the pinned toolchain, validates release gates
  first, and publishes only generated binaries.
- **Prevention / Reference:** Never clear a provenance/licensing marker merely
  to make CI green. Every native artifact published to consumers needs a
  traceable source, toolchain, checksum, and redistribution basis.

### [2026-09-18] Desktop release verifier rejected valid Windows and macOS engines

- **Context / Symptom:** The release matrix reported all 11 Windows
  `Portal*` exports missing even though cgo generated the matching header.
  On macOS it reported `libportaltunnel.dylib`, the universal-binary
  architecture headings, and `/usr/lib/libresolv.9.dylib` as unexpected
  dependencies.
- **Root Cause:** The first Windows parser stopped at the blank line before
  `[Ordinal/Name Pointer] Table`; its initial fix then assumed one exact
  `[ordinal] name` row shape. The Windows runner's MinGW `objdump` emitted a
  different column/annotation layout, so valid names still did not match.
  The macOS parser treated every `otool -L` line after the first as a
  dependency, but universal output contains a heading per architecture and
  each dylib slice lists its `LC_ID_DYLIB`. The allowlist also omitted
  macOS's system resolver library.
- **Solution:** Extract exact `Portal*` tokens from the complete PE metadata,
  independent of columns, annotations, and CRLF. For macOS, accept only
  tab-indented load-command rows, exclude the dylib's own install ID, and
  allow versioned `/usr/lib/libresolv.*.dylib`. Representative parser
  self-tests run in both release workflows.
- **Prevention / Reference:** ABI verification parsers must use the real
  multi-section/multi-architecture tool formats rather than blank-line or
  global-line-number assumptions.

### [2026-09-18] Sample ignored isolated Maven repository and queried Central

- **Context / Symptom:** After publishing `portal-sdk:0.1.0` into
  `build/sample-maven`, the desktop sample still failed with `Could not
  resolve io.github.gosuda:portal-sdk:0.1.0` and queried Maven Central.
- **Root Cause:** `samples/desktop/build.gradle.kts` declared its own
  `repositories` block. Gradle preferred project repositories over the
  repository injected by `dependencyResolutionManagement` from
  `-Pportal.samples.repository`.
- **Solution:** Removed the project-level repository block so all projects
  use the settings-level repositories. The staged repository now precedes
  Google and Maven Central.
- **Prevention / Reference:** Keep repository policy centralized in
  `settings.gradle.kts`; otherwise consumer-verification repositories can be
  silently shadowed by project-local declarations.

### [2026-09-18] `generateNativeIndex` fails on a fresh clone — `Input file does not exist`

- **Context / Symptom:** On a Windows checkout without `native/desktop/`,
  `:portal-native-desktop:generateNativeIndex` failed during Gradle input
  validation: `property 'nativeDesktopDir' specifies directory
  'D:\…\native\desktop' which doesn't exist`.
- **Root Cause:** `nativeDesktopDir` was a plain `@InputDirectory`, so
  Gradle validated the directory exists before the task ran. The task's
  own graceful path (missing binaries → partial-matrix warning) never
  executed. `native/desktop/` is gitignored — engine binaries are built
  by `scripts/build-desktop-engine.sh` or the CI matrix, not committed.
- **Solution:** Added `@get:Optional` to `nativeDesktopDir`. The task now
  runs on a fresh clone and emits the partial-matrix warning; a release
  build still fails via `requireComplete=true` when binaries are absent.
- **Prevention / Reference:** A `@InputDirectory` that may legitimately
  be absent needs `@Optional`; Gradle validates input existence before
  the task's own missing-input handling can run.


### [2026-09-18] Ollama download 404 — wrong asset names and format

- **Context / Symptom:** `OllamaSetup.install()` failed with
  `download failed: HTTP 404` on Linux. The URLs pointed at
  `ollama.com/download/ollama-linux-amd64.tgz` and
  `ollama.com/download/ollama-darwin.zip`.
- **Root Cause:** Two problems. (1) The Linux release asset is
  `ollama-linux-amd64.tar.zst` (Zstandard), not `.tgz` — the `.tgz` name
  was never published. (2) The macOS asset is `Ollama-darwin.zip` with a
  capital O; the lowercase name 404s. `ollama.com/download/` also
  redirects inconsistently; GitHub release assets are the stable source.
- **Solution:** Switched all URLs to
  `github.com/ollama/ollama/releases/latest/download/…` with the correct
  names. Linux `.tar.zst` is decompressed via `zstd-jni` (no `zstd`
  binary needed) then untarred; Windows extracts the full install tree
  so `ollama.exe` finds its bundled `lib/` DLLs.
- **Prevention / Reference:** Verify release asset names against
  `api.github.com/repos/ollama/ollama/releases/latest` — they differ per
  OS and change format (`.tar.zst`, not `.tgz`/`.zip` for Linux).


### [2026-09-18] `inner` class + `enum` inside a Kotlin `object` fails to compile

- **Context / Symptom:** `MinecraftServer` (a singleton `object`) declared
  `private inner class Session` containing `enum class State`; the build
  failed with `Modifier 'inner' is not applicable inside 'standalone
  object'` and `'Enum class' is prohibited here`.
- **Root Cause:** An `object` has no outer instance, so `inner` is
  meaningless; Kotlin also forbids `enum`/`inner`/`sealed`/`interface`
  declarations inside an `inner` class. Both rules fired at once.
- **Solution:** Dropped `inner` (a nested class in an `object` still reads
  the object's members unqualified) and moved the enum to object level as
  `SessionState`.
- **Prevention / Reference:** In a Kotlin `object`, use plain nested
  classes and object-level enums; reserve `inner` for real outer classes.

### [2026-09-18] `mark/reset` on an unbuffered socket stream silently no-ops

- **Context / Symptom:** Sniffing the first byte of a Minecraft connection
  (HTTP vs protocol) via `DataInputStream(socket.getInputStream())` +
  `mark(1)/read/reset` would not have worked — `markSupported()` is false
  on a raw socket stream, so `reset()` throws `IOException`.
- **Root Cause:** `mark/reset` is only honored by streams that buffer;
  `Socket.getInputStream()` does not.
- **Solution:** Wrapped the socket stream in `BufferedInputStream` before
  `DataInputStream`, giving real `mark/reset` support.
- **Prevention / Reference:** Always wrap socket input in
  `BufferedInputStream` when peeking; check `markSupported()` before
  relying on `mark/reset`.


### [2026-09-18] `processResources` placed native runtime at JAR root

- **Context / Symptom:** `portal-native-desktop` JAR contained `index.json`
  and `linux-x64/libportaltunnel.so` at the root instead of under
  `META-INF/portal-native/`, so `DesktopNativeLibraryLoader` could not find
  the packaged resource.
- **Root Cause:** `into("META-INF/portal-native")` was called on the
  `processResources` task itself, not on the `from(...)` source — the
  destination applies per-source, not per-task.
- **Solution:** Nest the `into` inside the `from` block:
  `from(layout.buildDirectory.dir("generated/portal-native")) { into("META-INF/portal-native") }`.
- **Prevention / Reference:** In Gradle `CopySpec`, `into`/`exclude`/`include`
  configure the enclosing `from` source; a bare `into` on the task sets the
  destination dir, not a resource prefix.

### [2026-09-18] Desktop `PortalClient()` failed without a packaged engine

- **Context / Symptom:** `PortalClientTest.portalEntryPointCreatesClient` and
  `builderCreatesWorkingClient` failed on desktop with `IllegalStateException`
  even though the tests only construct and close a client.
- **Root Cause:** `DesktopPortalEngine` loaded the native library eagerly in
  its constructor, so `PortalClient()` threw `NATIVE_UNAVAILABLE` when no
  packaged `.so` was on the classpath — unlike Android, where JNI loads
  lazily.
- **Solution:** Made `DesktopPortalEngine` resolve the library lazily on the
  first native call (matching Android's JNI behavior); the constructor now
  takes a `() -> PortalNativeLibrary` provider.
- **Prevention / Reference:** Platform engines must not touch native code at
  construction — a client with no sessions must close cleanly without a
  native dependency.

### [2026-09-18] Desktop identity path created the directory too early

- **Context / Symptom:** Per-OS identity-path tests failed with
  `PortalException: cannot create identity directory` because
  `DesktopIdentityPath.resolve` ran `Files.createDirectories` eagerly at
  `PortalDesktop.client(...)` construction.
- **Root Cause:** `PortalClient.defaultIdentityPath` was a plain `String?`
  resolved once at construction, so the directory was created before
  `open`/`publish` — contradicting the lazy-creation contract (and iOS).
- **Solution:** Changed `defaultIdentityPath` to a `(() -> String?)?`
  provider invoked inside `open`; split `DesktopIdentityPath` into a pure
  `pathFor` (no IO) and `resolve` (creates the dir, called lazily).
  `PortalDesktop.client` validates `applicationId` eagerly but defers
  directory creation to the operation.
- **Prevention / Reference:** Identity-path resolution that touches the
  filesystem must be deferred to `open`/`publish` so failures surface as
  `PERMISSION_DENIED` through the operation, not at client construction.


### [2026-09-18] Gradle could not locate Android SDK on Windows

- **Context / Symptom:** `publishToMavenLocal` failed with `SDK location not
  found` because neither `ANDROID_HOME` nor `local.properties` supplied an
  SDK path.
- **Root Cause:** The Android SDK was installed at the standard per-user path,
  but the shell environment did not export it.
- **Solution:** Ran Gradle with
  `ANDROID_HOME=C:\Users\mingy\AppData\Local\Android\Sdk`; alternatively set
  `sdk.dir` in the gitignored `local.properties`.
- **Prevention / Reference:** Configure one of those paths before running
  Android Gradle tasks on a fresh Windows checkout.

### [2026-09-17] `org.jetbrains.kotlin.android` rejected by AGP 9

- **Context / Symptom:** `Failed to apply plugin 'org.jetbrains.kotlin.android'`
  — "no longer required for Kotlin support since AGP 9.0".
- **Root Cause:** AGP 9.x ships built-in Kotlin support; the separate
  kotlin-android plugin is an error, not a no-op.
- **Solution:** Removed the plugin from `portal-native-android` and
  `samples/android`; Kotlin `jvmTarget` is set via the top-level `kotlin {
  compilerOptions {} }` extension the built-in plugin registers.
- **Reference:** https://kotl.in/gradle/agp-built-in-kotlin

### [2026-09-17] `androidLibrary {}` deprecated in KMP plugin

- **Context / Symptom:** `'androidLibrary' block is deprecated. Please use
  'android' instead` when configuring the KMP Android target.
- **Root Cause:** `com.android.kotlin.multiplatform.library` renamed the DSL
  block; the JetBrains template still used the old name.
- **Solution:** Use `android { namespace/compileSdk/minSdk/withHostTestBuilder/
  compilerOptions }` inside `kotlin {}`.

### [2026-09-17] cinterop `char**` out-params need `CPointerVar`, not `CPointer`

- **Context / Symptom:** `Argument type mismatch: CPointer<CPointer<ByteVar>>
  vs CValuesRef<CPointerVarOf<CPointer<ByteVar>>>` for every `Portal*` call.
- **Root Cause:** `alloc<CPointer<ByteVar>>()` produces the wrong pointed
  type; `char**` maps to `CPointerVar<ByteVar>` and `const char*` params take
  `CValuesRef` (`string.cstr` inside `memScoped`).
- **Solution:** `alloc<CPointerVar<ByteVar>>()`, pass `.ptr`, and give the
  native-call lambdas a `MemScope` receiver so `.cstr` is in scope.

### [2026-09-17] `munmap_chunk(): invalid pointer` in linuxX64Test

- **Context / Symptom:** `NativeStubTest.tunnelLifecycleThroughCAbi` crashed
  the test process with an invalid `free()`.
- **Root Cause:** The event callback freed its `const char*` arguments via
  `PortalFreeString`. The stub (and plausibly the real Go bridge) passes
  non-heap/static buffers; the iOS SDK never frees callback args while the
  Flutter SDK does — the Go bridge source is unavailable to settle it.
- **Solution:** Do not free callback arguments; document the contract in
  DESIGN_RULES.md §4. Out-params are still freed exactly once.
- **Prevention:** When the mobile bridge source is recovered, verify whether
  callback strings are `C.CString`-allocated per call and revisit.

### [2026-09-17] `fetchAndIncrement` missing on common `AtomicLong`

- **Context / Symptom:** `Unresolved reference 'fetchAndIncrement'` in
  commonMain.
- **Root Cause:** `kotlin.concurrent.atomics.AtomicLong` does not mirror the
  full `java.util.concurrent.atomic` API surface.
- **Solution:** Use `addAndFetch(1)` (returns the incremented value).

### [2026-09-17] iOS test binaries cannot link without the engine archive

- **Context / Symptom:** `iosSimulatorArm64Test`/`iosX64Test` would link the
  real `libportaltunnel`, which is not shipped (source-lock: MISSING).
- **Solution:** `linkDebugTestIos*`/`linkReleaseTestIos*`/`ios*Test` tasks are
  disabled in `portal-sdk/build.gradle.kts` only while
  `native/ios/<target>/libportaltunnel.a` is absent;
  `compileTestKotlinIos*` still type-checks test sources. With the archives
  present (built by `scripts/build-ios-engine.sh`) the tasks link and run
  against the real engine.

### [2026-09-17] `PortalStop` raced the serve-exit goroutine on `serveErr`

- **Context / Symptom:** `native stop failed (code 1): tunnel stop timed
  out` even though the tunnel logged a clean shutdown and emitted STOPPED.
- **Root Cause:** `PortalStop` and the serve-exit goroutine both received
  from the capacity-1 `serveErr` channel; whichever lost the race blocked
  until the 15 s timeout.
- **Solution:** The serve-exit goroutine is now the sole `serveErr`
  consumer; `markStopped` closes a separate `done` channel that
  `PortalStop`/`PortalStopAll` wait on.
- **Prevention:** Never add a second consumer to a "result" channel; use a
  broadcast `done` channel for waiters.

### [2026-09-17] Gradle does not relink when an external `.a` changes

- **Context / Symptom:** After rebuilding `libportaltunnel.a`,
  `linkDebugTestIosSimulatorArm64` stayed UP-TO-DATE and the test binary
  kept the old engine.
- **Root Cause:** The archive is produced outside Gradle, so the link task
  had no declared input for it.
- **Solution:** `inputs.file(native/ios/<target>/libportaltunnel.a)` is
  declared on each `linkDebugTestIos*` task.

### [2026-09-17] Go runtime on iOS needs `Security.framework`

- **Context / Symptom:** `Undefined symbols for architecture arm64:
  _SecTrustEvaluateWithError, _SecCertificateCopyData, …` when linking the
  Go archive.
- **Root Cause:** Go's Darwin TLS path calls the Security framework.
- **Solution:** Add `-framework Security` to the consumer's linker flags
  (already in `portal-sdk` linkerOpts and `samples/ios/project.yml`).

### [2026-09-17] Kotlin/Native does not export default-arg constructors to Swift

- **Context / Symptom:** `'init()' is unavailable` for
  `PortalIosClient()` in Swift.
- **Root Cause:** Kotlin default parameter values are not exported; only
  the full-argument initializer exists in the Objective-C/Swift surface.
- **Solution:** Call `PortalIosClient(allowRemoteTargets: false,
  defaultIdentityPath: nil)` explicitly.

### [2026-09-18] Orphan STOPPED event left a zombie session in `client.sessions`

- **Context / Symptom:** A new `publish` test that emits `STOPPED` inside the
  native `start` (the orphan-buffer path) failed: `client.sessions` still
  contained the terminal tunnel after `open` returned.
- **Root Cause:** `PortalClient.open` called `PortalEventHub.register(tunnel)`
  (which drains buffered orphan events, including `STOPPED` → `markTerminal` →
  `unregisterTunnel`) *before* `registry.register(tunnel)`. The session was
  therefore registered only after it had already been unregistered — a zombie.
- **Solution:** Reordered to `registry.register(tunnel)` then
  `PortalEventHub.register(tunnel)`, so a terminal transition during the
  orphan drain unregisters a session that actually exists.
- **Prevention / Reference:** Any registration pair where one side can
  synchronously tear the other down must register the teardown target first.
  Covered by `publishTerminalSessionDoesNotResurrect` in `PortalClientTest`.

### [2026-09-18] Concurrent close could re-register an event route

- **Context / Symptom:** Review of the orphan-event registration fix found a
  second interleaving: `close()` could stop and unregister a just-created
  tunnel after `registry.register`, then `open()` could continue with
  `PortalEventHub.register` and re-add a route owned by the closed client.
- **Root Cause:** The final closed-state check and the two registration calls
  were not atomic with the `closeMutex` section that marks the client closed
  and snapshots its sessions.
- **Solution:** Wrapped the final `closed` check plus registry/hub registration
  in `closeMutex`. Native start remains outside the mutex; a handle finishing
  after close is stopped non-cancellably and rejected with CLIENT_CLOSED.
- **Prevention / Reference:** Covered by
  `closeDuringNativeStartRejectsAndStopsLateHandle`; lifecycle publication
  must cross the owner-close boundary in one critical section.
