#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="${1:-}"
PROFILE="${2:-security}"

if [[ -z "$DESTINATION" || ! "$PROFILE" =~ ^(oci|signing|security)$ ]]; then
  echo "usage: $0 DESTINATION {oci|signing|security}" >&2
  exit 2
fi
if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  echo "RELEASE_TOOL_PLATFORM_UNSUPPORTED" >&2
  exit 1
fi
if [[ -e "$DESTINATION" ]]; then
  echo "RELEASE_TOOL_DESTINATION_EXISTS" >&2
  exit 1
fi

mkdir -p "$DESTINATION"
cleanup() {
  if [[ "${COMPLETE:-0}" != "1" ]]; then
    rm -rf -- "$DESTINATION"
  fi
}
trap cleanup EXIT INT TERM

source_uri() {
  PYTHONDONTWRITEBYTECODE=1 python3 -B - "$ROOT_DIR" "$1" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, str(Path(sys.argv[1]) / "scripts"))
from release_json import load_json
lock = load_json(Path(sys.argv[1]) / "contracts/release/toolchain-lock-1.0.0.json")
matches = [item for item in lock["tools"] if item.get("name") == sys.argv[2]]
if len(matches) != 1:
    raise SystemExit("RELEASE_TOOL_LOCK_ENTRY_INVALID")
print(matches[0]["sourceUri"])
PY
}

fetch() {
  local name="$1"
  local output="$2"
  curl --fail --location --proto '=https' --tlsv1.2 --output "$DESTINATION/$output" "$(source_uri "$name")"
  PYTHONDONTWRITEBYTECODE=1 python3 -B "$ROOT_DIR/scripts/check_release_tool.py" "$ROOT_DIR" "$name" "$DESTINATION/$output"
}

fetch "oras-linux-amd64-archive" "oras.tar.gz"
tar -xzf "$DESTINATION/oras.tar.gz" -C "$DESTINATION" oras
chmod 0755 "$DESTINATION/oras"

if [[ "$PROFILE" == "oci" ]]; then
  "$DESTINATION/oras" version
  COMPLETE=1
  exit 0
fi

fetch "cosign-linux-amd64" "cosign"
fetch "cosign-linux-amd64-bundle" "cosign.sigstore.json"
chmod 0755 "$DESTINATION/cosign"
"$DESTINATION/cosign" verify-blob \
  --bundle "$DESTINATION/cosign.sigstore.json" \
  --certificate-identity "keyless@projectsigstore.iam.gserviceaccount.com" \
  --certificate-oidc-issuer "https://accounts.google.com" \
  "$DESTINATION/cosign"

if [[ "$PROFILE" == "signing" ]]; then
  "$DESTINATION/oras" version
  "$DESTINATION/cosign" version
  COMPLETE=1
  exit 0
fi

fetch "trivy-linux-amd64-archive" "trivy.tar.gz"
fetch "trivy-linux-amd64-bundle" "trivy.sigstore.json"
tar -xzf "$DESTINATION/trivy.tar.gz" -C "$DESTINATION" trivy
chmod 0755 "$DESTINATION/trivy"
"$DESTINATION/cosign" verify-blob \
  --bundle "$DESTINATION/trivy.sigstore.json" \
  --certificate-identity "https://github.com/aquasecurity/trivy/.github/workflows/reusable-release.yaml@refs/tags/v0.72.0" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  "$DESTINATION/trivy.tar.gz"

"$DESTINATION/oras" version
"$DESTINATION/cosign" version
"$DESTINATION/trivy" --version
COMPLETE=1
