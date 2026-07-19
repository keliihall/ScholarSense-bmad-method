#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
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

echo "[bootstrap] resolve locked Maven runtime dependencies before offline lock verification"
"$TOOLCHAIN" "$ROOT_DIR/backend/mvnw" -f "$ROOT_DIR/backend/pom.xml" \
  dependency:resolve -DincludeScope=runtime -DincludeTransitive=true -DoutputAbsoluteArtifactFilename=true

echo "[bootstrap] resolve Maven build plugins before any lifecycle execution"
"$TOOLCHAIN" "$ROOT_DIR/backend/mvnw" -f "$ROOT_DIR/backend/pom.xml" \
  dependency:resolve-plugins -DincludeTransitive=true -DoutputAbsoluteArtifactFilename=true

echo "[bootstrap] PASS"
