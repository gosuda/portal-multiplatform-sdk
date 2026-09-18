# Work Checkpoint

## Active task
KMP platform expansion planning — **Desktop plan complete; Kotlin/Wasm
feasibility assessed; implementation not started**.

## State (2026-09-18)
- Added `docs/DESKTOP_IMPLEMENTATION_PLAN.md`.
- Desktop decision:
  - first-class target is JVM `jvm("desktop")` on Java 17+, matching normal
    Compose Desktop consumers;
  - bind the existing v1 C ABI with JNA rather than reuse Android JNI or expose
    Kotlin/Native desktop as the primary artifact;
  - first runtime matrix is Linux x86_64 glibc, Windows x86_64, and macOS
    universal (x86_64 + arm64);
  - package verified native libraries in `:portal-native-desktop`, extract to a
    content-addressed cache, and never download native code at runtime;
  - add `PortalDesktop.client(...)` with application-scoped persistent identity
    defaults and an explicit native-library override;
  - prove the host Linux C ABI path before adding Gradle targets or public API.
- Added `docs/WASM_SUPPORT_ASSESSMENT.md`.
- Kotlin/Wasm decision:
  - do not publish `wasmJs` or `wasmWasi` as runnable `portal-sdk` targets;
  - browser Wasm cannot preserve local listener, arbitrary TCP/UDP, filesystem,
    identity-path, or native-engine ownership semantics;
  - Kotlin/Wasm currently supports WASI 0.1, while Go `wasip1` lacks portable
    full socket open/listen support required by the engine;
  - WASI 0.3 has async sockets, but Kotlin and Go do not currently share a
    production Component Model target for this engine;
  - a future browser control SDK is viable only as a separately named remote
    management product, not a `PortalClient` implementation;
  - a future WASI component needs a WIT/Component Model ABI and must clear the
    explicit gates in the assessment.
- Updated `TASKS.md` with Desktop D0–D6 and Wasm re-evaluation gates.
- No production code, public API, build configuration, or release artifact was
  changed in this planning session.
- Prior SDK usability state remains:
  - P0–P3 mostly implemented;
  - P4/P5, Android/iOS host verification, real-relay coverage, clean consumer
    checks, and sample UI migration remain open;
  - `license_review` remains PENDING because `portal-android-sdk` ships no
    LICENSE file; `android_ndk_revision` is resolved to r29.

## Verification
- Documentation link and Markdown fence validation passed for both plans,
  `TASKS.md`, and this checkpoint.
- Desktop plan contains no residual patch markers.
- `git diff --check` passed.
- Runtime/build tests were not run because this session changes planning
  documentation only.

## Next action
Execute Desktop D0 on Linux:

1. build the current `native/bridge` with `-buildmode=c-shared`;
2. verify the required `Portal*` exports against
   `native/include/portaltunnel.h`;
3. run a throwaway JVM/JNA harness through identity generation/parsing,
   callback delivery, start/status/stop, UTF-8, and every returned-string free;
4. record ABI, glibc/dependency, binary-size, and real-relay evidence before
   modifying Gradle targets or publishing public Desktop API.

Do not start Kotlin/Wasm runtime implementation. Revisit only when the
toolchain gates in `docs/WASM_SUPPORT_ASSESSMENT.md` are met, or begin a
separately scoped browser-control product after its management/security
contract is approved.

## Prior environment notes (2026-09-17 macOS session)
- Android SDK at `~/Android/Sdk` (platform 36, build-tools 36.0.0) via
  `local.properties` (gitignored).
- JDK 17, Gradle 9.6.1 wrapper, Kotlin 2.4.10, AGP 9.1.0.
- Go 1.27.1 darwin/arm64, Xcode 27.0, xcodegen 2.46.0 (brew).
- iPhone 15 Pro connected (UDID 00008130-001E58C13AE0001C), team
  `37FAA8L9Q7` in `samples/ios/project.yml`.
- AGP 9: no `org.jetbrains.kotlin.android` plugin; use `android {}` not
  `androidLibrary {}` in the KMP block. See docs/TROUBLESHOOTING.md.

## Blockers
- `license_review` and `android_ndk_revision` still gate releases
  (source-lock.json). No code blockers for iOS.
