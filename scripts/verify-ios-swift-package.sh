#!/usr/bin/env bash
# Link device and simulator apps from the actual downloadable Swift package ZIP.
# No library search paths, project dependencies, or manual Security linker flags.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="${1:-$ROOT/dist/PortalSDK-swift-package.zip}"
WORK="$ROOT/build/ios-swift-package-consumer"
rm -rf "$WORK"
mkdir -p "$WORK/Sources"
unzip -q "$PACKAGE" -d "$WORK"
cp "$ROOT/samples/ios/QuickStartContract.swift" "$ROOT/samples/ios/AdvancedContract.swift" "$WORK/Sources/"
cat > "$WORK/Sources/ConsumerApp.swift" <<'SWIFT'
import SwiftUI
import PortalSDK

@main
struct ConsumerApp: App {
    private let client = PortalIosClient(allowRemoteTargets: false, defaultIdentityPath: nil)
    var body: some Scene { WindowGroup { Text("Portal SDK consumer") } }
}
SWIFT
cat > "$WORK/project.yml" <<'YAML'
name: PortalPackageConsumer
options:
  deploymentTarget:
    iOS: "16.0"
packages:
  PortalSDK:
    path: PortalSDK
targets:
  PortalPackageConsumer:
    type: application
    platform: iOS
    sources: [Sources]
    dependencies:
      - package: PortalSDK
        product: PortalSDK
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: org.gosuda.portal.packageconsumer
        GENERATE_INFOPLIST_FILE: YES
        SWIFT_VERSION: "5.0"
YAML
xcodegen generate --spec "$WORK/project.yml" --project "$WORK"
for destination in 'generic/platform=iOS Simulator' 'generic/platform=iOS'; do
    xcodebuild -project "$WORK/PortalPackageConsumer.xcodeproj" -scheme PortalPackageConsumer \
        -configuration Release -destination "$destination" \
        -derivedDataPath "$WORK/DerivedData" CODE_SIGNING_ALLOWED=NO build
done
