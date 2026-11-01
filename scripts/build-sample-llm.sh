#!/usr/bin/env bash
# Builds only the sample-local pinned llama.cpp CPU engine, never model weights.
# Usage: ./scripts/build-sample-llm.sh desktop|ios
# Dependencies: CMake >=3.22, C/C++17 compiler; JDK17 for desktop JNI;
# Xcode + iOS SDK for the static iOS XCFramework (iOS16 minimum).
# PORTAL_SAMPLE_LLM_CACHE optionally relocates the verified source cache outside
# the repository. PORTAL_LLM_JOBS defaults to 4; CMAKE selects the CMake binary.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/samples/local-llm"
OUT="$ROOT/build/sample-llm"
CMAKE="${CMAKE:-cmake}"
JOBS="${PORTAL_LLM_JOBS:-4}"

if ! command -v "$CMAKE" >/dev/null 2>&1; then
    echo "CMake >=3.22 is required (set CMAKE=/path/to/cmake)." >&2
    exit 1
fi

build_desktop() {
    local platform_flags=()
    if [[ "$(uname -s)" == "Darwin" ]]; then
        platform_flags+=("-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0")
    fi
    if [[ "$(uname -s)" == "Darwin" && -z "${JAVA_HOME:-}" ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17)"
        export JAVA_HOME
    fi
    "$CMAKE" -S "$SOURCE" -B "$OUT/cmake-desktop" \
        -DCMAKE_BUILD_TYPE=Release \
        -DPORTAL_SAMPLE_LLM_OUTPUT_DIR="$OUT/desktop" \
        ${platform_flags[@]+"${platform_flags[@]}"}
    "$CMAKE" --build "$OUT/cmake-desktop" --config Release \
        --target portal_sample_llm --parallel "$JOBS"
    "$CMAKE" -E copy_if_different "$SOURCE/NOTICE" "$OUT/desktop/NOTICE"
    echo "Sample CPU JNI library: $OUT/desktop"
}

build_ios_slice() {
    local name="$1" sdk="$2" arch="$3"
    local build="$OUT/cmake-ios-$name"
    local slice="$OUT/ios/slices/$name"
    local archives=() archive
    "$CMAKE" -S "$SOURCE" -B "$build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_SYSTEM_PROCESSOR="$arch" \
        -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" \
        -DCMAKE_OSX_ARCHITECTURES="$arch" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
        -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
        -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
    "$CMAKE" --build "$build" --config Release \
        --target portal_sample_llm --parallel "$JOBS"
    while IFS= read -r archive; do
        [[ -z "$archive" ]] || archives+=("$archive")
    done < "$build/portal-sample-llm-archives-Release.txt"
    "$CMAKE" -E make_directory "$slice/Headers"
    xcrun libtool -static -o "$slice/libPortalSampleLlm.a" "${archives[@]}"
    "$CMAKE" -E copy_directory "$SOURCE/include" "$slice/Headers"
}

build_ios() {
    if [[ "$(uname -s)" != "Darwin" ]]; then
        echo "Building an iOS XCFramework requires macOS and Xcode." >&2
        exit 1
    fi
    build_ios_slice device-arm64 iphoneos arm64
    build_ios_slice simulator-arm64 iphonesimulator arm64
    build_ios_slice simulator-x86_64 iphonesimulator x86_64
    "$CMAKE" -E make_directory "$OUT/ios/slices/simulator-universal/Headers"
    xcrun lipo -create \
        "$OUT/ios/slices/simulator-arm64/libPortalSampleLlm.a" \
        "$OUT/ios/slices/simulator-x86_64/libPortalSampleLlm.a" \
        -output "$OUT/ios/slices/simulator-universal/libPortalSampleLlm.a"
    "$CMAKE" -E copy_directory "$SOURCE/include" "$OUT/ios/slices/simulator-universal/Headers"
    # xcodebuild refuses to overwrite an existing XCFramework. Remove only this
    # script's generated product; cached model files are never build inputs.
    "$CMAKE" -E rm -rf "$OUT/ios/PortalSampleLlm.xcframework"
    xcodebuild -create-xcframework \
        -library "$OUT/ios/slices/device-arm64/libPortalSampleLlm.a" \
        -headers "$OUT/ios/slices/device-arm64/Headers" \
        -library "$OUT/ios/slices/simulator-universal/libPortalSampleLlm.a" \
        -headers "$OUT/ios/slices/simulator-universal/Headers" \
        -output "$OUT/ios/PortalSampleLlm.xcframework"
    "$CMAKE" -E copy_if_different "$SOURCE/NOTICE" "$OUT/ios/NOTICE"
    echo "Sample CPU static module: $OUT/ios/PortalSampleLlm.xcframework"
}

case "${1:-desktop}" in
    desktop) build_desktop ;;
    ios) build_ios ;;
    *) echo "Usage: $0 desktop|ios" >&2; exit 2 ;;
esac
