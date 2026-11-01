#!/usr/bin/env bash
# Link the real workbench from the downloadable Swift package ZIP on device and simulator.
# No library search paths, project dependencies, or manual Security linker flags.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="${1:-$ROOT/dist/PortalSDK-swift-package.zip}"
SAMPLE_LLM="$ROOT/build/sample-llm/ios/PortalSampleLlm.xcframework"
[[ -d "$SAMPLE_LLM" ]] || {
    echo "Build the sample-only inference module: scripts/build-sample-llm.sh ios" >&2
    exit 1
}
WORK="$ROOT/build/ios-swift-package-consumer"
rm -rf "$WORK"
mkdir -p "$WORK/Sources"
unzip -q "$PACKAGE" -d "$WORK"
cp "$ROOT"/samples/ios/*.swift "$WORK/Sources/"
cp -R "$ROOT/samples/ios/Assets.xcassets" "$ROOT/samples/ios/LaunchScreen.storyboard" \
    "$ROOT/samples/shared/common/site" "$ROOT/samples/shared/common/llm" \
    "$ROOT/samples/shared/common/models.json" "$WORK/Sources/"
cp -R "$SAMPLE_LLM" "$WORK/PortalSampleLlm.xcframework"
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
    sources:
      - path: Sources
        excludes: [site, llm]
      - path: Sources/site
        type: folder
        buildPhase: resources
      - path: Sources/llm
        type: folder
        buildPhase: resources
    dependencies:
      - package: PortalSDK
        product: PortalSDK
      - framework: PortalSampleLlm.xcframework
        embed: false
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: org.gosuda.portal.packageconsumer
        GENERATE_INFOPLIST_FILE: YES
        SWIFT_VERSION: "5.0"
        INFOPLIST_KEY_UILaunchStoryboardName: LaunchScreen
YAML
xcodegen generate --spec "$WORK/project.yml" --project "$WORK"
for destination in 'generic/platform=iOS Simulator' 'generic/platform=iOS'; do
    xcodebuild -project "$WORK/PortalPackageConsumer.xcodeproj" -scheme PortalPackageConsumer \
        -configuration Release -destination "$destination" \
        -derivedDataPath "$WORK/DerivedData" CODE_SIGNING_ALLOWED=NO build
done
