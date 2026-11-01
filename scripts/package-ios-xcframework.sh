#!/usr/bin/env bash
# Package verified framework slices and a complete, installable local Swift package.
# Run after :portal-sdk:assemblePortalSDKReleaseXCFramework on macOS.
# The package ZIP is extracted and added with Xcode's Add Local Package action;
# a standalone release-asset Package.swift is not a remotely resolvable package.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for tool in lipo nm swift python3; do
    command -v "$tool" >/dev/null || { echo "error: $tool not found (macOS required)" >&2; exit 1; }
done
python3 - <<'PY'
import os
from pathlib import Path
import plistlib
import shutil
import stat
import subprocess
import zipfile

root = Path.cwd()
dist = root / "dist"
source = root / "portal-sdk/build/XCFrameworks/release/PortalSDK.xcframework"
if not source.is_dir():
    raise SystemExit(f"{source} missing; run assemblePortalSDKReleaseXCFramework")
info = plistlib.loads((source / "Info.plist").read_bytes())
expected = {("ios", "", "arm64"), ("ios", "simulator", "arm64"), ("ios", "simulator", "x86_64")}
seen = set()
for library in info["AvailableLibraries"]:
    framework = source / library["LibraryIdentifier"] / library["LibraryPath"]
    binary = framework / "PortalSDK"
    framework_info = plistlib.loads((framework / "Info.plist").read_bytes())
    minimum = tuple(map(int, framework_info["MinimumOSVersion"].split(".")))
    if minimum[:2] != (16, 0):
        raise SystemExit(f"{framework}: expected minimum iOS 16.0, found {minimum}")
    for arch in library["SupportedArchitectures"]:
        key = (library["SupportedPlatform"], library.get("SupportedPlatformVariant", ""), arch)
        if key in seen:
            raise SystemExit(f"duplicate XCFramework slice: {key}")
        seen.add(key)
        subprocess.run(["lipo", str(binary), "-verify_arch", arch], check=True)
        symbols = subprocess.check_output(["nm", "-arch", arch, "-gU", str(binary)], text=True)
        exports = {line.split()[-1].removeprefix("_") for line in symbols.splitlines() if line.split()}
        missing = {"PortalStart", "PortalStop", "PortalFreeString"} - exports
        if missing:
            raise SystemExit(f"{binary} ({arch}): missing engine definitions: {sorted(missing)}")
if seen != expected:
    raise SystemExit(f"XCFramework matrix mismatch: expected {expected}, found {seen}")

dist.mkdir(exist_ok=True)
framework = dist / "PortalSDK.xcframework"
if framework.exists():
    shutil.rmtree(framework)
shutil.copytree(source, framework, symlinks=True)
package = dist / "PortalSDK-swift-package"
if package.exists():
    shutil.rmtree(package)
(package / "Sources/PortalSDKLink").mkdir(parents=True)
shutil.copytree(framework, package / framework.name, symlinks=True)
(package / "Sources/PortalSDKLink/Exports.swift").write_text("@_exported import PortalSDK\n")
(package / "Package.swift").write_text('''// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "PortalSDK",
    platforms: [.iOS(.v16)],
    products: [.library(name: "PortalSDK", targets: ["PortalSDKLink"])],
    targets: [
        .binaryTarget(name: "PortalSDK", path: "PortalSDK.xcframework"),
        .target(
            name: "PortalSDKLink",
            dependencies: ["PortalSDK"],
            linkerSettings: [.linkedFramework("Security")]
        )
    ]
)
''')


def archive(directory, output, prefix):
    # Stable order, timestamps, Unix permissions and no host-specific metadata.
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(directory.rglob("*")):
            relative = f"{prefix}/{path.relative_to(directory).as_posix()}"
            mode = path.lstat().st_mode
            if stat.S_ISDIR(mode):
                continue
            entry = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777 if stat.S_ISLNK(mode) else stat.S_IFREG | (0o755 if mode & 0o111 else 0o644)) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            if stat.S_ISLNK(mode):
                zipped.writestr(entry, os.readlink(path).encode())
            else:
                with path.open("rb") as source_file, zipped.open(entry, "w") as target_file:
                    shutil.copyfileobj(source_file, target_file)

archive(framework, dist / "PortalSDK.xcframework.zip", framework.name)
archive(package, dist / "PortalSDK-swift-package.zip", "PortalSDK")
# Remove the obsolete incomplete manifest left by earlier script versions.
(dist / "Package.swift").unlink(missing_ok=True)
PY
swift package compute-checksum dist/PortalSDK.xcframework.zip > dist/PortalSDK.xcframework.zip.sha256
swift package compute-checksum dist/PortalSDK-swift-package.zip > dist/PortalSDK-swift-package.zip.sha256
echo "Packaged dist/PortalSDK.xcframework.zip and dist/PortalSDK-swift-package.zip"
