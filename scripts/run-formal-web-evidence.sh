#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONDONTWRITEBYTECODE=1

if [[ "$#" -ne 6 ]]; then
  echo "usage: $0 ARTIFACT_URI EXPECTED_FRONTEND_SHA256 BUILD_MANIFEST_SHA256 TEST_ENV_SHA256 ATTESTATION_URI OUTPUT" >&2
  exit 2
fi
ARTIFACT_URI="$1"
EXPECTED_FRONTEND_SHA256="$2"
BUILD_MANIFEST_SHA256="$3"
TEST_ENV_SHA256="$4"
ATTESTATION_URI="$5"
OUTPUT="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$6")"

if [[ -e "$OUTPUT" ]]; then
  echo "FORMAL_WEB_OUTPUT_ALREADY_EXISTS" >&2
  exit 1
fi
if [[ -z "${ORAS_PASSWORD:-}" || -z "${GITHUB_ACTOR:-}" ]]; then
  echo "FORMAL_WEB_CREDENTIAL_CONTEXT_MISSING" >&2
  exit 2
fi
python3 -B "$ROOT_DIR/release/verifier.py" oci-uri "$ARTIFACT_URI"
python3 -B "$ROOT_DIR/release/verifier.py" oci-uri "$ATTESTATION_URI"
python3 -B "$ROOT_DIR/scripts/check_formal_web_runner.py" --require-actions

WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/scholarsense-formal-web-XXXXXX")"
cleanup() {
  chmod -R u+w "$WORK_DIR" 2>/dev/null || true
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

TOOLS="$WORK_DIR/tools"
REGISTRY_CONFIG="$WORK_DIR/oras-config.json"
"$ROOT_DIR/scripts/install-release-tools-macos.sh" "$TOOLS" signing
"$TOOLS/oras" --registry-config "$REGISTRY_CONFIG" login ghcr.io \
  --username "$GITHUB_ACTOR" --password-stdin <<< "$ORAS_PASSWORD"

mkdir "$WORK_DIR/artifact" "$WORK_DIR/attestation"
"$TOOLS/oras" --registry-config "$REGISTRY_CONFIG" pull "$ARTIFACT_URI" --output "$WORK_DIR/artifact"
"$TOOLS/oras" --registry-config "$REGISTRY_CONFIG" pull "$ATTESTATION_URI" --output "$WORK_DIR/attestation"
BUILD_ROOT="$WORK_DIR/artifact/release-out/build"
ATTESTATION_ROOT="$WORK_DIR/attestation/release-out/attestation"

GOLDEN_URI="$(python3 -B "$ROOT_DIR/scripts/check_formal_web_evidence.py" preflight \
  "$ROOT_DIR" "$BUILD_ROOT" "$EXPECTED_FRONTEND_SHA256" \
  "$BUILD_MANIFEST_SHA256" "$TEST_ENV_SHA256")"

python3 -B "$ROOT_DIR/release/verifier.py" attestation-query \
  "$ATTESTATION_ROOT/scholarsense-frontend.attestations.json" "$EXPECTED_FRONTEND_SHA256" \
  "https://slsa.dev/provenance/v1" "https://cyclonedx.org/bom"
"$TOOLS/cosign" verify-blob \
  --bundle "$ATTESTATION_ROOT/scholarsense-frontend.sigstore.json" \
  --certificate-identity "https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/release.yml@refs/heads/main" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  "$BUILD_ROOT/scholarsense-frontend.tar.gz"

mkdir "$WORK_DIR/golden"
"$TOOLS/oras" --registry-config "$REGISTRY_CONFIG" pull "$GOLDEN_URI" --output "$WORK_DIR/golden"
python3 -B "$ROOT_DIR/release/formal_web.py" safe_extract_frontend \
  "$BUILD_ROOT/scholarsense-frontend.tar.gz" "$WORK_DIR/served" "$EXPECTED_FRONTEND_SHA256"
BROWSER_INSTALL_ROOT="${FORMAL_BROWSER_INSTALL_ROOT:-$HOME/Library/Caches/ScholarSense/formal-browser-install/TEST-ENV-1.0.0}"
"$ROOT_DIR/scripts/install-formal-web-browsers.sh" "$BROWSER_INSTALL_ROOT"

node "$ROOT_DIR/frontend/scripts/run-formal-web-evidence.mjs" \
  --served-root "$WORK_DIR/served" \
  --browsers "$BROWSER_INSTALL_ROOT/browsers.json" \
  --golden-root "$WORK_DIR/golden" \
  --artifact-uri "$ARTIFACT_URI" \
  --subject-sha256 "$EXPECTED_FRONTEND_SHA256" \
  --build-manifest-sha256 "$BUILD_MANIFEST_SHA256" \
  --output "$WORK_DIR/evidence"
python3 -B "$ROOT_DIR/scripts/check_formal_web_evidence.py" verify \
  "$ROOT_DIR" "$WORK_DIR/evidence" "$BUILD_ROOT" "$EXPECTED_FRONTEND_SHA256" \
  "$BUILD_MANIFEST_SHA256" "$TEST_ENV_SHA256"

mkdir -p "$(dirname "$OUTPUT")"
mv "$WORK_DIR/evidence" "$OUTPUT"
echo "run-formal-web-evidence: PASS ($EXPECTED_FRONTEND_SHA256)"
