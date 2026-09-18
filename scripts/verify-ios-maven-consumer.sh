#!/usr/bin/env bash
# Builds and links a clean KMP iOS consumer against staged Maven artifacts.
set -euo pipefail

REPOSITORY="${1:?usage: verify-ios-maven-consumer.sh <maven-repository-path>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/ios-maven-consumer"
rm -rf "$WORK"
mkdir -p "$WORK/src/iosMain/kotlin"

cat > "$WORK/settings.gradle.kts" <<EOF
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("$REPOSITORY") }
        google()
        mavenCentral()
    }
}
rootProject.name = "portal-ios-maven-consumer"
EOF

cat > "$WORK/build.gradle.kts" <<'EOF'
plugins {
    kotlin("multiplatform") version "2.4.10"
}

kotlin {
    iosArm64 {
        binaries.framework { baseName = "Consumer" }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.gosuda:portal-sdk:0.1.0")
        }
    }
}
EOF

cat > "$WORK/src/iosMain/kotlin/Consumer.kt" <<'EOF'
import org.gosuda.portal.PortalIosClient

fun createPortalClient(): PortalIosClient = PortalIosClient()
EOF

"$ROOT/gradlew" --project-dir "$WORK" linkDebugFrameworkIosArm64 --no-configuration-cache
