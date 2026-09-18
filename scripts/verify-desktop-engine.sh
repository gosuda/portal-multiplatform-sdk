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
        exports=$(objdump -p "$BIN" 2>/dev/null | sed -n '/Export Address Table/,/^$/p' | awk '{print $NF}' || true)
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
        deps=$(otool -L "$BIN" 2>/dev/null | awk 'NR>1 {gsub(/^\t/,""); print $1}' || true)
        allow='^(/usr/lib/libSystem\.B\.dylib|/System/Library/Frameworks/.*|@rpath/.*|/usr/lib/libc\+\+.*)$'
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
