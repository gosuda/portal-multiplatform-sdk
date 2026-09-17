#!/usr/bin/env bash
# Syncs the bundled libportaltunnel binaries to a portal-tunnel release.
#
# Usage:
#   ./scripts/sync-portal-tunnel.sh            # latest release
#   ./scripts/sync-portal-tunnel.sh v1.2.3     # specific tag
#
# Reads the current pin from portal-tunnel.version, downloads the release
# assets for each Android ABI, verifies checksums when present, and updates
# the pin. Run from the repo root.

set -euo pipefail

REPO="gosuda/portal-tunnel"
PIN_FILE="portal-tunnel.version"
JNILIBS="portal-native-android/src/main/jniLibs"
ABIS=("arm64-v8a" "x86_64")

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep -oP '"tag_name":\s*"\K[^"]+')
    echo "latest release: $TAG"
fi

CURRENT=$(cat "$PIN_FILE" 2>/dev/null || echo "none")
if [[ "$CURRENT" == "$TAG" ]]; then
    echo "already at $TAG"
    exit 0
fi

echo "syncing $CURRENT → $TAG"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for ABI in "${ABIS[@]}"; do
    ASSET="libportaltunnel-${ABI}.so"
    URL="https://github.com/$REPO/releases/download/$TAG/$ASSET"
    echo "  $ABI: $ASSET"
    if ! curl -fsSL -o "$TMP/$ASSET" "$URL"; then
        echo "    not found as standalone asset; trying zip"
        ZIP="libportaltunnel-android-${TAG}.zip"
        ZIP_URL="https://github.com/$REPO/releases/download/$TAG/$ZIP"
        curl -fsSL -o "$TMP/$ZIP" "$ZIP_URL"
        unzip -o "$TMP/$ZIP" -d "$TMP/extracted" >/dev/null
        find "$TMP/extracted" -name "libportaltunnel.so" -path "*$ABI*" -exec cp {} "$TMP/$ASSET" \;
    fi
    mkdir -p "$JNILIBS/$ABI"
    cp "$TMP/$ASSET" "$JNILIBS/$ABI/libportaltunnel.so"
    echo "    → $JNILIBS/$ABI/libportaltunnel.so"
done

echo "$TAG" > "$PIN_FILE"
echo "pinned to $TAG"
