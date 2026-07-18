#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
export PYTHONDONTWRITEBYTECODE=1

"$ROOT_DIR/scripts/bootstrap.sh"

echo "[verify] clean backend build, architecture/migration guards, cross-cutting contracts, and role smoke"
(
  cd "$ROOT_DIR/backend"
  "$TOOLCHAIN" ./mvnw clean verify
)

echo "[verify] 1.1a audit regression"
(
  cd "$ROOT_DIR"
  "$TOOLCHAIN" python3 -m unittest discover -s _bmad/scripts/tests -p 'test_*.py'
)

echo "[verify] 1.1b standard-library checks and negative fixtures"
(
  cd "$ROOT_DIR"
  "$TOOLCHAIN" python3 -m unittest discover -s scripts/tests -p 'test_*.py'
  "$TOOLCHAIN" python3 scripts/check_frontend_structure.py frontend
  "$TOOLCHAIN" python3 scripts/check_contract_seeds.py .
  "$TOOLCHAIN" python3 scripts/check_production_pollution.py .
)

echo "[verify] normalized source/output structure"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/normalized_manifest.py" "$ROOT_DIR" --summary
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_release_source.py" "$ROOT_DIR"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_cisb.py" "$ROOT_DIR"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_workflow_security.py"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_release_contracts.py" "$ROOT_DIR"

echo "[verify] isolated frontend offline install, type, unit, build, browser and accessibility"
"$TOOLCHAIN" "$ROOT_DIR/scripts/verify_frontend.sh" --offline

echo "[verify] PASS"
