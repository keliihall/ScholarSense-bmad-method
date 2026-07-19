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

tool_profile="signing"
if [[ "${REQUIRE_CURRENT_RESCAN:-0}" == "1" ]]; then
  tool_profile="security"
fi
"$ROOT_DIR/scripts/install-release-tools.sh" "$WORK_DIR/tools" "$tool_profile"
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
ARTIFACT_IDENTITY="https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/artifact-signing.yml@refs/heads/main"
MANIFEST_IDENTITY="https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/manifest-signing.yml@refs/heads/main"
ARTIFACT_SIGNER_WORKFLOW="keliihall/ScholarSense-bmad-method/.github/workflows/artifact-signing.yml"
SOURCE_COMMIT="$(jq -er '.sourceCommit | select(test("^[0-9a-f]{40}$"))' "$MANIFEST")"
SOURCE_ARCHIVE_SHA256="$(jq -er '.sourceArchive.binarySha256 | select(test("^[0-9a-f]{64}$"))' "$MANIFEST")"
test "$(jq -er '.sourceArchive.uri' "$MANIFEST")" = "oci://$ARTIFACT_URI"
ISSUER="https://token.actions.githubusercontent.com"

python3 -B "$ROOT_DIR/scripts/check_sbom.py" "$ROOT_DIR" "$BUILD" "$SBOM"
if [[ "${REQUIRE_CURRENT_RESCAN:-0}" == "1" ]]; then
  for sbom in "$SBOM"/*.cdx.json; do
    scan="$WORK_DIR/current-$(basename "$sbom")"
    "$WORK_DIR/tools/trivy" sbom --scanners vuln --format cyclonedx --output "$scan" "$sbom"
    subject="$(jq -er '.metadata.component.hashes[] | select(.alg == "SHA-256") | .content' "$sbom")"
    python3 -B "$ROOT_DIR/release/verifier.py" current-vulnerability-policy "$scan" "$subject"
  done
fi
for subject in scholarsense-backend.jar scholarsense-frontend.tar.gz; do
  path="$BUILD/$subject"
  stem="${subject%%.*}"
  digest="$(sha256sum "$path" | cut -d' ' -f1)"
  python3 -B "$ROOT_DIR/release/verifier.py" attestation-query \
    "$ATTESTATION/${stem}.attestations.json" "$digest" \
    "https://slsa.dev/provenance/v1" "https://cyclonedx.org/bom" "https://spdx.dev/Document"
  python3 -B "$ROOT_DIR/release/verifier.py" github-attestations \
    "$path" "$digest" "keliihall/ScholarSense-bmad-method" \
    "$ARTIFACT_SIGNER_WORKFLOW" "$SOURCE_COMMIT" \
    "https://slsa.dev/provenance/v1" "https://cyclonedx.org/bom" "https://spdx.dev/Document"
  "$WORK_DIR/tools/cosign" verify-blob \
    --bundle "$ATTESTATION/${stem}.sigstore.json" \
    --certificate-identity "$ARTIFACT_IDENTITY" \
    --certificate-oidc-issuer "$ISSUER" \
    "$path"
done

python3 -B "$ROOT_DIR/scripts/check_formal_web_evidence.py" "$ROOT_DIR" "$WORK_DIR/web" "$BUILD"
python3 -B "$ROOT_DIR/scripts/check_release_manifests.py" release "$MANIFEST" "$BUILD/build-manifest.json"
"$WORK_DIR/tools/cosign" verify-blob \
  --bundle "$SIGNATURE" \
  --certificate-identity "$MANIFEST_IDENTITY" \
  --certificate-oidc-issuer "$ISSUER" \
  "$MANIFEST"
python3 -B "$ROOT_DIR/scripts/check_release_manifests.py" index "$INDEX" "$MANIFEST"
python3 -B "$ROOT_DIR/release/verifier.py" extract-source \
  "$BUILD/release-source.tar.gz" "$WORK_DIR/source" "$SOURCE_ARCHIVE_SHA256"
python3 -B "$ROOT_DIR/release/verifier.py" pulled-material \
  "$WORK_DIR/source" "$BUILD" "$SBOM" "$ATTESTATION" "$WORK_DIR/web" \
  "$MANIFEST" "$SIGNATURE" "$INDEX" \
  "$ARTIFACT_URI" "$SBOM_URI" "$ATTESTATION_URI" "$WEB_URI" "$MANIFEST_URI" "$SIGNATURE_URI"

printf 'VERIFIED_MANIFEST_SHA256=%s\n' "$(sha256sum "$MANIFEST" | cut -d' ' -f1)"
printf 'VERIFIED_EVIDENCE_INDEX_SHA256=%s\n' "$(sha256sum "$INDEX" | cut -d' ' -f1)"
echo "verify-release: PASS"
