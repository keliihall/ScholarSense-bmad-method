#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
OUTPUT="${1:-$ROOT_DIR/release-out/local-release}"

exec "$TOOLCHAIN" python3 "$ROOT_DIR/release/build_release.py" "$ROOT_DIR" "$OUTPUT"
