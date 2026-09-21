#!/usr/bin/env bash
# Builds and links a clean KMP iOS consumer against staged Maven artifacts.
set -euo pipefail

REPOSITORY="$(cd "${1:?usage: verify-ios-maven-consumer.sh <maven-repository-path>}" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_VERSION="$(python3 -c 'import sys,tomllib; print(tomllib.load(open(sys.argv[1], "rb"))["versions"]["kotlin"])' "$ROOT/gradle/libs.versions.toml")"
SDK_VERSION="$(python3 -c 'import sys,tomllib; print(tomllib.load(open(sys.argv[1], "rb"))["versions"]["portal-sdk"])' "$ROOT/gradle/libs.versions.toml")"
python3 - "$REPOSITORY" "$SDK_VERSION" <<'PY'
from pathlib import Path
import sys, zipfile
repository, version = Path(sys.argv[1]), sys.argv[2]
for target in ("iosarm64", "iossimulatorarm64", "iosx64"):
    artifact = repository / f"io/github/gosuda/portal-sdk-{target}/{version}/portal-sdk-{target}-{version}.klib"
    with zipfile.ZipFile(artifact) as klib:
        embedded = [entry for entry in klib.infolist() if entry.filename.endswith("/libportaltunnel.a") and entry.file_size > 0]
        if len(embedded) != 1:
            raise SystemExit(f"{artifact}: expected one nonempty embedded engine archive, found {len(embedded)}")
    print(f"Verified embedded engine in {artifact}")
PY
WORK="$ROOT/build/ios-maven-consumer"
rm -rf "$WORK"
mkdir -p "$WORK/src/iosMain/kotlin"

cat > "$WORK/settings.gradle.kts" <<EOF
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("$REPOSITORY") } }
            filter { includeGroup("io.github.gosuda") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "portal-ios-maven-consumer"
EOF

cat > "$WORK/build.gradle.kts" <<EOF
plugins {
    kotlin("multiplatform") version "$KOTLIN_VERSION"
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
        it.binaries.framework { baseName = "Consumer" }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.gosuda:portal-sdk:$SDK_VERSION")
        }
    }
}
EOF

cat > "$WORK/src/iosMain/kotlin/Consumer.kt" <<'EOF'
import org.gosuda.portal.PortalIosClient

fun createPortalClient(): PortalIosClient = PortalIosClient()
EOF

"$ROOT/gradlew" --project-dir "$WORK" linkReleaseFrameworkIosArm64 \
    linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosX64 --no-configuration-cache
