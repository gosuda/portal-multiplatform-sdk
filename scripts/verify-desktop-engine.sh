#!/usr/bin/env bash
# Verifies a built desktop engine binary against the v1 ABI contract:
#   1. every required Portal* symbol is exported;
#   2. the cgo-generated header matches native/include/portaltunnel.h
#      (function declarations — the cgo prologue differs by build mode);
#   3. dynamic dependencies are limited to documented system libraries;
#   4. records SHA-256 and size for source-lock provenance.
#
# Usage:
#   ./scripts/verify-desktop-engine.sh native/desktop/linux-x64/libportaltunnel.so
#   ./scripts/verify-desktop-engine.sh native/desktop/windows-x64/portaltunnel.dll
#   ./scripts/verify-desktop-engine.sh native/desktop/macos-universal/libportaltunnel.dylib
#
# Run from the repo root. Exits nonzero on any failed check.
set -euo pipefail

BIN="${1:?usage: verify-desktop-engine.sh <path-to-binary>}"
HEADER="native/include/portaltunnel.h"
REQUIRED=(
    PortalSetEventCallback PortalFreeString PortalGenerateIdentity
    PortalParseIdentity PortalStart PortalStop PortalStopAll
    PortalGetStatus PortalAddRelay PortalRemoveRelay PortalUpdateMetadata
)

parse_windows_exports() {
    # MinGW/GNU objdump formatting differs across runner/toolchain versions:
    # export rows may carry extra columns, annotations, and CRLF. Extract
    # Portal* tokens from the complete PE metadata instead of relying on one
    # exact "[ordinal] name" row shape.
    awk '{
        line = $0
        while (match(line, /Portal[A-Za-z0-9_]+/)) {
            print substr(line, RSTART, RLENGTH)
            line = substr(line, RSTART + RLENGTH)
        }
    }' | sort -u
}

parse_macos_dependencies() {
    local self_name="$1"
    # Universal binaries produce one non-indented heading per architecture.
    # Only indented load-command rows are dependencies. otool also prints the
    # dylib's LC_ID_DYLIB as the first row; that is not a loaded dependency.
    awk -v self_name="$self_name" '
        /^\t/ {
            dep = $1
            if (dep != self_name) print dep
        }
    '
}

if [[ "${1:-}" == "--self-test" ]]; then
    windows_fixture=$'The Export Tables (interpreted .edata section contents)\r

Export Address Table -- Ordinal Base 1
	[   0] +base[   1] 1370 Export RVA

[Ordinal/Name Pointer] Table
	[   0] PortalAddRelay\r
	[  10] PortalUpdateMetadata  forwarder metadata\r
Name pointer: PortalSetEventCallback'
    windows_expected=$'PortalAddRelay\nPortalSetEventCallback\nPortalUpdateMetadata'
    [[ "$(parse_windows_exports <<<"$windows_fixture")" == "$windows_expected" ]] ||
        { echo "Windows export parser self-test failed" >&2; exit 1; }

    macos_fixture='native/desktop/macos-universal/libportaltunnel.dylib (architecture x86_64):
native/desktop/macos-universal/libportaltunnel.dylib:
	libportaltunnel.dylib (compatibility version 0.0.0, current version 0.0.0)
	/usr/lib/libSystem.B.dylib (compatibility version 1.0.0, current version 1336.61.1)
	/usr/lib/libresolv.9.dylib (compatibility version 1.0.0, current version 1.0.0)
native/desktop/macos-universal/libportaltunnel.dylib (architecture arm64):
	libportaltunnel.dylib (compatibility version 0.0.0, current version 0.0.0)
native/desktop/macos-universal/libportaltunnel.dylib:
	/System/Library/Frameworks/Security.framework/Versions/A/Security (compatibility version 1.0.0, current version 61439.120.27)'
    macos_expected=$'/usr/lib/libSystem.B.dylib\n/usr/lib/libresolv.9.dylib\n/System/Library/Frameworks/Security.framework/Versions/A/Security'
    [[ "$(parse_macos_dependencies libportaltunnel.dylib <<<"$macos_fixture")" == "$macos_expected" ]] ||
        { echo "macOS dependency parser self-test failed" >&2; exit 1; }

    echo "desktop verifier parser self-tests passed"
    exit 0
fi


fail=0
note() { echo "  $*"; }
err() { echo "  ERROR: $*" >&2; fail=1; }

[[ -f "$BIN" ]] || { echo "binary not found: $BIN" >&2; exit 1; }
echo "== verifying $BIN"

# --- 1. exported symbols -----------------------------------------------------
case "$BIN" in
    *.so)
        exports=$(nm -D --defined-only "$BIN" 2>/dev/null | awk '{print $NF}' || true)
        ;;
    *.dylib)
        exports=$(nm -gU "$BIN" 2>/dev/null | awk '{print $NF}' | sed 's/^_//' || true)
        ;;
    *.dll)
        exports=$(objdump -p "$BIN" 2>/dev/null | parse_windows_exports || true)
        ;;
    *)
        echo "unrecognized binary type: $BIN" >&2; exit 1 ;;
esac

for sym in "${REQUIRED[@]}"; do
    if grep -qx "$sym" <<<"$exports"; then
        note "export ok: $sym"
    else
        err "missing export: $sym"
    fi
done

# --- 2. header equivalence ---------------------------------------------------
# The checked-in header is the ABI source of truth. cgo emits a per-build
# header next to the binary; compare the Portal* declarations, not the
# build-mode-specific prologue.
gen_header="$(dirname "$BIN")/libportaltunnel.h"
if [[ -f "$gen_header" ]]; then
    decls_ref=$(grep -oE 'Portal[A-Za-z]+\(' "$HEADER" | sort -u)
    decls_gen=$(grep -oE 'Portal[A-Za-z]+\(' "$gen_header" | sort -u)
    if [[ "$decls_ref" == "$decls_gen" ]]; then
        note "header declarations match ($(wc -l <<<"$decls_ref" | tr -d ' ') symbols)"
    else
        err "generated header declarations differ from $HEADER"
        diff <(echo "$decls_ref") <(echo "$decls_gen") >&2 || true
    fi
else
    note "no generated header beside binary; skipped header check"
fi

# --- 3. dynamic dependencies -------------------------------------------------
deps=""
allow='^$'
case "$BIN" in
    *.so)
        deps=$(readelf -d "$BIN" 2>/dev/null | awk '/NEEDED/ {gsub(/[][]/,"",$5); print $5}' || true)
        allow='^(linux-vdso\.so\.1|libpthread\.so\.0|libc\.so\.6|libdl\.so\.2|librt\.so\.1|libm\.so\.6|ld-linux-x86-64\.so\.2|libresolv\.so\.2)$'
        ;;
    *.dylib)
        deps=$(otool -L "$BIN" 2>/dev/null |
            parse_macos_dependencies "$(basename "$BIN")" || true)
        allow='^(/usr/lib/libSystem\.B\.dylib|/usr/lib/libresolv\.[0-9]+\.dylib|/System/Library/Frameworks/.*|@rpath/.*|/usr/lib/libc\+\+.*)$'
        ;;
    *.dll)
        deps=$(objdump -p "$BIN" 2>/dev/null | awk '/DLL Name/ {print $3}' || true)
        allow='^(KERNEL32\.dll|msvcrt\.dll|WS2_32\.dll|ADVAPI32\.dll|CRYPT32\.dll|bcrypt\.dll|ntdll\.dll|SECHOST\.dll|USER32\.dll|SHELL32\.dll|ole32\.dll|WINMM\.dll|IPHLPAPI\.DLL|api-ms-win-.*)$'
        ;;
esac
while IFS= read -r dep; do
    [[ -z "$dep" ]] && continue
    if [[ "$dep" =~ $allow ]]; then
        note "dep ok: $dep"
    else
        err "unexpected dependency: $dep"
    fi
done <<<"$deps"

# --- 4. provenance -----------------------------------------------------------
sha=$(sha256sum "$BIN" | awk '{print $1}')
size=$(stat -c%s "$BIN" 2>/dev/null || stat -f%z "$BIN")
note "sha256: $sha"
note "size:   $size bytes"

if [[ $fail -ne 0 ]]; then
    echo "== FAILED: $BIN" >&2
    exit 1
fi
echo "== OK: $BIN"
