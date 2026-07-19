#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
MAVEN_REPOSITORY="${MAVEN_REPO_LOCAL:-${HOME:?}/.m2/repository}"
if [[ "$MAVEN_REPOSITORY" != /* ]]; then
  MAVEN_REPOSITORY="$ROOT_DIR/$MAVEN_REPOSITORY"
fi
export PYTHONDONTWRITEBYTECODE=1

echo "[bootstrap] validate exact PAB toolchain and Maven Wrapper"
(
  cd "$ROOT_DIR/backend"
  "$TOOLCHAIN" ./mvnw --version
)

echo "[bootstrap] validate frontend and contract seeds"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_frontend_structure.py" "$ROOT_DIR/frontend"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_frontend_baseline.py" "$ROOT_DIR"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_contract_seeds.py" "$ROOT_DIR"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_production_pollution.py" "$ROOT_DIR"

echo "[bootstrap] prewarm isolated frontend dependency and browser cache"
"$TOOLCHAIN" "$ROOT_DIR/scripts/verify_frontend.sh" --prepare

echo "[bootstrap] fetch and verify the locked Maven bootstrap plugin before first execution"
"$TOOLCHAIN" python3 -B "$ROOT_DIR/scripts/prepare_locked_maven_plugin.py" \
  "$ROOT_DIR" "$MAVEN_REPOSITORY"

echo "[bootstrap] resolve locked Maven runtime dependencies before offline lock verification"
"$TOOLCHAIN" "$ROOT_DIR/backend/mvnw" -f "$ROOT_DIR/backend/pom.xml" \
  -Dmaven.repo.local="$MAVEN_REPOSITORY" \
  org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve \
  -DincludeScope=runtime -DincludeTransitive=true -DoutputAbsoluteArtifactFilename=true

echo "[bootstrap] resolve Maven build plugins before any lifecycle execution"
"$TOOLCHAIN" "$ROOT_DIR/backend/mvnw" -f "$ROOT_DIR/backend/pom.xml" \
  -Dmaven.repo.local="$MAVEN_REPOSITORY" \
  org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve-plugins \
  -DincludeTransitive=true -DoutputAbsoluteArtifactFilename=true

echo "[bootstrap] PASS"
