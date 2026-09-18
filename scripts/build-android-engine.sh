#!/usr/bin/env bash
# Builds the Android JNI libportaltunnel.so directly from native/bridge.
# Requires Go and Android NDK r29. Outputs generated libraries under the
# portal-native-android build directory; no prebuilt binary is fetched.
set -euo pipefail

BRIDGE_DIR="native/bridge"
OUT_ROOT="portal-native-android/build/generated/jniLibs"
ANDROID_API="${ANDROID_API:-26}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
    NDK=$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
fi
[[ -n "$NDK" && -d "$NDK" ]] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }

case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "unsupported Android build host: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$host/bin"
[[ -d "$TOOLCHAIN" ]] || { echo "NDK LLVM toolchain not found: $TOOLCHAIN" >&2; exit 1; }

TARGETS=("${@:-arm64-v8a x86_64}")
if [[ $# -eq 0 ]]; then TARGETS=(arm64-v8a x86_64); fi

for target in "${TARGETS[@]}"; do
    case "$target" in
        arm64-v8a) goarch=arm64; clang_target=aarch64-linux-android ;;
        x86_64) goarch=amd64; clang_target=x86_64-linux-android ;;
        *) echo "unknown target: $target (expected arm64-v8a|x86_64)" >&2; exit 1 ;;
    esac
    cc="$TOOLCHAIN/${clang_target}${ANDROID_API}-clang"
    [[ -x "$cc" ]] || { echo "Android clang not found: $cc" >&2; exit 1; }
    out_dir="$OUT_ROOT/$target"
    mkdir -p "$out_dir"
    echo "== android/$target (API $ANDROID_API, cc=$cc)"
    (
        cd "$BRIDGE_DIR"
        env CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$cc" \
            go build -trimpath -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
            -buildmode=c-shared -o "../../$out_dir/libportaltunnel.so" .
    )
    rm -f "$out_dir/libportaltunnel.h"
    echo "  → $out_dir/libportaltunnel.so"
done
