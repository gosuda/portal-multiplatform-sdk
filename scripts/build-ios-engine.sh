#!/usr/bin/env bash
# Builds libportaltunnel.a for each iOS target from native/bridge (the
# clean-room Go bridge over portal-tunnel/v2 sdk.Exposure).
#
# Usage:
#   ./scripts/build-ios-engine.sh            # all three targets
#   ./scripts/build-ios-engine.sh iosArm64   # one target
#
# Output: native/ios/<target>/libportaltunnel.a plus the cgo-generated
# header at native/ios/<target>/portaltunnel.h (verified against
# native/include/portaltunnel.h).
#
# Requires: macOS, Xcode, Go >= 1.24. Run from the repo root.
set -euo pipefail

BRIDGE_DIR="native/bridge"
OUT_ROOT="native/ios"
MIN_IOS="16.0"

TARGETS=("${@:-iosArm64 iosSimulatorArm64 iosX64}")
if [[ $# -eq 0 ]]; then
    TARGETS=(iosArm64 iosSimulatorArm64 iosX64)
fi

for TARGET in "${TARGETS[@]}"; do
    case "$TARGET" in
        iosArm64)
            GOARCH=arm64
            SDK=iphoneos
            CLANG_TARGET="arm64-apple-ios${MIN_IOS}"
            ;;
        iosSimulatorArm64)
            GOARCH=arm64
            SDK=iphonesimulator
            CLANG_TARGET="arm64-apple-ios${MIN_IOS}-simulator"
            ;;
        iosX64)
            GOARCH=amd64
            SDK=iphonesimulator
            CLANG_TARGET="x86_64-apple-ios${MIN_IOS}-simulator"
            ;;
        *)
            echo "unknown target: $TARGET (expected iosArm64|iosSimulatorArm64|iosX64)" >&2
            exit 1
            ;;
    esac

    SDKROOT=$(xcrun --sdk "$SDK" --show-sdk-path)
    CLANG=$(xcrun --sdk "$SDK" --find clang)
    OUT_DIR="$OUT_ROOT/$TARGET"
    mkdir -p "$OUT_DIR"

    echo "== $TARGET ($SDK, $GOARCH)"
    (
        cd "$BRIDGE_DIR"
        env \
            CGO_ENABLED=1 \
            GOOS=ios \
            GOARCH="$GOARCH" \
            CC="$CLANG" \
            CGO_CFLAGS="-isysroot $SDKROOT -target $CLANG_TARGET" \
            CGO_LDFLAGS="-isysroot $SDKROOT -target $CLANG_TARGET" \
            go build -buildmode=c-archive -o "../../$OUT_DIR/libportaltunnel.a" .
    )

    # cgo emits the header next to the archive; keep the shared header in
    # sync so cinterop and the archive never drift.
    if ! cmp -s "$OUT_DIR/libportaltunnel.h" native/include/portaltunnel.h; then
        echo "  note: generated header differs from native/include/portaltunnel.h"
        echo "  review the diff before replacing it"
    fi
    echo "  → $OUT_DIR/libportaltunnel.a"
done
