#!/usr/bin/env python3
"""Read back immutable release bundles and assemble the ReleaseManifest generator input."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from assembly import assemble_release_manifest_input  # noqa: E402
from release_json import canonical_bytes  # noqa: E402


TARGET_EVIDENCE_FILENAME = (
    "public-integration-target-conformance-evidence-1.0.0.json"
)


def _required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(f"RELEASE_ASSEMBLY_INPUT_MISSING: {name}")
    return value


def _oras() -> Path:
    configured = os.environ.get("ORAS")
    candidate = Path(configured) if configured else Path(_required("RUNNER_TEMP")) / "release-tools/oras"
    if not candidate.is_file() or not os.access(candidate, os.X_OK):
        raise ValueError("RELEASE_ASSEMBLY_ORAS_MISSING")
    return candidate


def _pull(oras: Path, uri: str, output: Path) -> None:
    result = subprocess.run(
        [str(oras), "pull", uri, "--output", str(output)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        raise ValueError(f"RELEASE_ASSEMBLY_OCI_READBACK_FAILED: {result.stderr.strip()}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_version")
    parser.add_argument("output")
    parser.add_argument("--manifest-version", choices=("1", "2"), default="1")
    try:
        args = parser.parse_args(argv[1:])
    except SystemExit as error:
        return int(error.code)
    output = Path(args.output).resolve()
    if output.exists():
        print("RELEASE_ASSEMBLY_OUTPUT_ALREADY_EXISTS", file=sys.stderr)
        return 1
    try:
        uris = {
            "artifact": _required("ARTIFACT_URI"),
            "sbom": _required("SBOM_URI"),
            "attestation": _required("ATTESTATION_URI"),
            "web": _required("WEB_URI"),
        }
        if args.manifest_version == "2":
            uris["public-integration-target"] = _required(
                "PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI"
            )
        oras = _oras()
        with tempfile.TemporaryDirectory(prefix="scholarsense-release-assembly-") as directory:
            root = Path(directory)
            for name, uri in uris.items():
                destination = root / name
                destination.mkdir()
                _pull(oras, uri, destination)
            build_root = root / "artifact/release-out/build"
            payload = assemble_release_manifest_input(
                PROJECT_ROOT,
                args.release_version,
                uris["artifact"],
                build_root,
                uris["sbom"],
                root / "sbom/release-out/sbom",
                uris["attestation"],
                root / "attestation/release-out/attestation",
                uris["web"],
                root / "web",
                datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
                manifest_version=args.manifest_version,
                public_integration_target_evidence_uri=(
                    uris["public-integration-target"]
                    if args.manifest_version == "2" else None
                ),
                public_integration_target_evidence_path=(
                    root / "public-integration-target" / TARGET_EVIDENCE_FILENAME
                    if args.manifest_version == "2" else None
                ),
            )
            if os.environ.get("GITHUB_SHA") and payload["buildManifest"].get("sourceCommit") != os.environ["GITHUB_SHA"]:
                raise ValueError("RELEASE_ASSEMBLY_WORKFLOW_SOURCE_MISMATCH")
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(canonical_bytes(payload))
    except (OSError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print("assemble-release-manifest-input: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
