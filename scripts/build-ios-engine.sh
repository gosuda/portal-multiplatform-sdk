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

    # The checked-in header is the ABI source of truth. Compare exported
    # declarations; cgo's generated prologue is build-mode-specific.
    decls_ref=$(grep -oE 'Portal[A-Za-z]+\(' native/include/portaltunnel.h | sort -u)
    decls_gen=$(grep -oE 'Portal[A-Za-z]+\(' "$OUT_DIR/libportaltunnel.h" | sort -u)
    if [[ "$decls_ref" != "$decls_gen" ]]; then
        echo "ERROR: generated declarations differ from native/include/portaltunnel.h" >&2
        diff <(echo "$decls_ref") <(echo "$decls_gen") >&2 || true
        exit 1
    fi
    echo "  → $OUT_DIR/libportaltunnel.a"
done
