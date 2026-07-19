#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONDONTWRITEBYTECODE=1

required=(ARTIFACT_URI SBOM_URI ATTESTATION_URI WEB_URI MANIFEST_URI SIGNATURE_URI INDEX_URI ORAS_PASSWORD)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "VERIFIER_INPUT_MISSING: $name" >&2
    exit 2
  fi
done
for uri in "$ARTIFACT_URI" "$SBOM_URI" "$ATTESTATION_URI" "$WEB_URI" "$MANIFEST_URI" "$SIGNATURE_URI" "$INDEX_URI"; do
  python3 -B "$ROOT_DIR/release/verifier.py" oci-uri "$uri"
done

WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/scholarsense-verifier-XXXXXX")"
cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

"$ROOT_DIR/scripts/install-release-tools.sh" "$WORK_DIR/tools" signing
"$WORK_DIR/tools/oras" login ghcr.io --username "${GITHUB_ACTOR:?}" --password-stdin <<< "$ORAS_PASSWORD"
for entry in \
  "artifact:$ARTIFACT_URI" "sbom:$SBOM_URI" "attestation:$ATTESTATION_URI" \
  "web:$WEB_URI" "manifest:$MANIFEST_URI" "signature:$SIGNATURE_URI" "index:$INDEX_URI"; do
  name="${entry%%:*}"
  uri="${entry#*:}"
  mkdir "$WORK_DIR/$name"
  "$WORK_DIR/tools/oras" pull "$uri" --output "$WORK_DIR/$name"
done

BUILD="$WORK_DIR/artifact/release-out/build"
SBOM="$WORK_DIR/sbom/release-out/sbom"
ATTESTATION="$WORK_DIR/attestation/release-out/attestation"
MANIFEST="$WORK_DIR/manifest/release-out/release-manifest.json"
SIGNATURE="$WORK_DIR/signature/release-out/manifest-signature/release-manifest.sigstore.json"
INDEX="$WORK_DIR/index/release-out/evidence-index.json"
IDENTITY="https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/release.yml@refs/heads/main"
ISSUER="https://token.actions.githubusercontent.com"

python3 -B "$ROOT_DIR/scripts/check_sbom.py" "$ROOT_DIR" "$BUILD" "$SBOM"
for subject in scholarsense-backend.jar scholarsense-frontend.tar.gz; do
  path="$BUILD/$subject"
  stem="${subject%%.*}"
  digest="$(sha256sum "$path" | cut -d' ' -f1)"
  python3 -B "$ROOT_DIR/release/verifier.py" attestation-query \
    "$ATTESTATION/${stem}.attestations.json" "$digest" \
    "https://slsa.dev/provenance/v1" "https://cyclonedx.org/bom"
  "$WORK_DIR/tools/cosign" verify-blob \
    --bundle "$ATTESTATION/${stem}.sigstore.json" \
    --certificate-identity "$IDENTITY" \
    --certificate-oidc-issuer "$ISSUER" \
    "$path"
done

python3 -B "$ROOT_DIR/scripts/check_formal_web_evidence.py" "$ROOT_DIR" "$WORK_DIR/web" "$BUILD"
python3 -B "$ROOT_DIR/scripts/check_release_manifests.py" release "$MANIFEST" "$BUILD/build-manifest.json"
"$WORK_DIR/tools/cosign" verify-blob \
  --bundle "$SIGNATURE" \
  --certificate-identity "$IDENTITY" \
  --certificate-oidc-issuer "$ISSUER" \
  "$MANIFEST"
python3 -B "$ROOT_DIR/scripts/check_release_manifests.py" index "$INDEX" "$MANIFEST"

echo "verify-release: PASS"
