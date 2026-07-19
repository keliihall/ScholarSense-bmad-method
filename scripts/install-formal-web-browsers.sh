#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="${1:-}"
CACHE="${FORMAL_BROWSER_CACHE:-$HOME/Library/Caches/ScholarSense/formal-browsers/TEST-ENV-1.0.0/artifacts}"

if [[ -z "$DESTINATION" ]]; then
  echo "usage: $0 DESTINATION" >&2
  exit 2
fi
PYTHONDONTWRITEBYTECODE=1 python3 -B "$ROOT_DIR/release/install_formal_browsers.py" "$DESTINATION" "$CACHE"
