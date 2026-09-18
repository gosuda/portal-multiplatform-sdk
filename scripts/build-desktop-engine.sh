#!/usr/bin/env bash
# Builds the libportaltunnel v1 C ABI as a shared library for desktop targets
# from native/bridge (the clean-room Go bridge over portal-tunnel/v2
# sdk.Exposure). The same bridge produces the iOS c-archive; do not fork it.
#
# Usage:
#   ./scripts/build-desktop-engine.sh                 # host target only
#   ./scripts/build-desktop-engine.sh linux-x64       # one target
#   ./scripts/build-desktop-engine.sh linux-x64 windows-x64 macos-universal
#
# Output: native/desktop/<target>/libportaltunnel.{so,dylib} or
# portaltunnel.dll plus the cgo-generated header (verified against
# native/include/portaltunnel.h by scripts/verify-desktop-engine.sh).
#
# Toolchain requirements per target:
#   linux-x64        zig cc targeting x86_64-linux-gnu.2.17 (glibc 2.17
#                    baseline). Set ZIG=/path/to/zig or put zig on PATH.
#                    Pinned: zig 0.16.0, sha256
#                    70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00
#   windows-x64      Windows host or Linux with x86_64-w64-mingw32-gcc
#   macos-universal  macOS host, Xcode clang, lipo
#
# Environment overrides: GO (go binary), ZIG (zig binary), CC (C compiler for
# windows/macos). Run from the repo root.
set -euo pipefail

BRIDGE_DIR="native/bridge"
OUT_ROOT="native/desktop"
MIN_MACOS="13.0"
LINUX_GLIBC_TARGET="x86_64-linux-gnu.2.17"
GO="${GO:-go}"

if ! command -v "$GO" >/dev/null 2>&1; then
    echo "go toolchain not found (set GO=/path/to/go)" >&2
    exit 1
fi

find_zig() {
    if [[ -n "${ZIG:-}" && -x "${ZIG}" ]]; then echo "$ZIG"; return 0; fi
    if command -v zig >/dev/null 2>&1; then command -v zig; return 0; fi
    if [[ -x "$HOME/tools/zig/zig" ]]; then echo "$HOME/tools/zig/zig"; return 0; fi
    return 1
}

host_target() {
    case "$(uname -s)" in
        Linux)  echo linux-x64 ;;
        Darwin) echo macos-universal ;;
        MINGW*|MSYS*|CYGWIN*) echo windows-x64 ;;
        *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
    esac
}

TARGETS=("$@")
if [[ ${#TARGETS[@]} -eq 0 ]]; then
    TARGETS=("$(host_target)")
fi

build_one() {
    local target="$1" goos goarch cc_bin out_file ldflags="-s -w" extra_env=()
    case "$target" in
        linux-x64)
            goos=linux; goarch=amd64
            local zig
            if ! zig=$(find_zig); then
                echo "== $target: SKIP (zig not found; set ZIG=/path/to/zig)" >&2
                return 1
            fi
            cc_bin="$zig cc -target $LINUX_GLIBC_TARGET"
            out_file="libportaltunnel.so"
            ;;
        windows-x64)
            goos=windows; goarch=amd64
            cc_bin="${CC:-x86_64-w64-mingw32-gcc}"
            out_file="portaltunnel.dll"
            ;;
        macos-arm64)
            goos=darwin; goarch=arm64; cc_bin="${CC:-clang}"
            out_file="libportaltunnel.dylib"
            extra_env+=("CGO_CFLAGS=-target arm64-apple-macosx${MIN_MACOS}"
                        "CGO_LDFLAGS=-target arm64-apple-macosx${MIN_MACOS}")
            ;;
        macos-x64)
            goos=darwin; goarch=amd64; cc_bin="${CC:-clang}"
            out_file="libportaltunnel.dylib"
            extra_env+=("CGO_CFLAGS=-target x86_64-apple-macosx${MIN_MACOS}"
                        "CGO_LDFLAGS=-target x86_64-apple-macosx${MIN_MACOS}")
            ;;
        macos-universal)
            build_one macos-arm64 || return 1
            build_one macos-x64 || return 1
            local out_dir="$OUT_ROOT/macos-universal"
            mkdir -p "$out_dir"
            lipo -create \
                "$OUT_ROOT/macos-arm64/libportaltunnel.dylib" \
                "$OUT_ROOT/macos-x64/libportaltunnel.dylib" \
                -output "$out_dir/libportaltunnel.dylib"
            cp "$OUT_ROOT/macos-arm64/libportaltunnel.h" "$out_dir/libportaltunnel.h" 2>/dev/null || true
            echo "  → $out_dir/libportaltunnel.dylib (universal)"
            return 0
            ;;
        *)
            echo "unknown target: $target (expected linux-x64|windows-x64|macos-arm64|macos-x64|macos-universal)" >&2
            return 1
            ;;
    esac

    local out_dir="$OUT_ROOT/$target"
    mkdir -p "$out_dir"
    echo "== $target ($goos/$goarch, cc=$cc_bin)"
    if ! (
        cd "$BRIDGE_DIR"
        env CGO_ENABLED=1 GOOS="$goos" GOARCH="$goarch" CC="$cc_bin" \
            ${extra_env[@]+"${extra_env[@]}"} \
            "$GO" build -ldflags="$ldflags" -buildmode=c-shared -o "../../$out_dir/$out_file" .
    ); then
        echo "  ERROR: go build failed for $target" >&2
        return 1
    fi
    # cgo emits the header next to the library as <name>.h.
    local gen_header="$out_dir/${out_file%.*}.h"
    if [[ -f "$gen_header" && "$gen_header" != "$out_dir/libportaltunnel.h" ]]; then
        mv "$gen_header" "$out_dir/libportaltunnel.h"
    fi
    echo "  → $out_dir/$out_file"
}

status=0
for t in "${TARGETS[@]}"; do
    build_one "$t" || status=1
done
exit "$status"
