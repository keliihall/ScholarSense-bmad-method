#!/usr/bin/env python3
"""Read back the frozen manifest/signature and assemble EvidenceIndex input."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from assembly import assemble_evidence_index_input  # noqa: E402
from release_json import canonical_bytes  # noqa: E402


def _required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(f"RELEASE_ASSEMBLY_INPUT_MISSING: {name}")
    return value


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
    if len(argv) != 2:
        print("usage: assemble-evidence-index-input.py OUTPUT.json", file=sys.stderr)
        return 2
    output = Path(argv[1]).resolve()
    if output.exists():
        print("RELEASE_ASSEMBLY_OUTPUT_ALREADY_EXISTS", file=sys.stderr)
        return 1
    try:
        manifest_uri = _required("MANIFEST_URI")
        signature_uri = _required("SIGNATURE_URI")
        configured = os.environ.get("ORAS")
        oras = Path(configured) if configured else Path(_required("RUNNER_TEMP")) / "release-tools/oras"
        if not oras.is_file() or not os.access(oras, os.X_OK):
            raise ValueError("RELEASE_ASSEMBLY_ORAS_MISSING")
        with tempfile.TemporaryDirectory(prefix="scholarsense-index-assembly-") as directory:
            root = Path(directory)
            (root / "manifest").mkdir()
            (root / "signature").mkdir()
            _pull(oras, manifest_uri, root / "manifest")
            _pull(oras, signature_uri, root / "signature")
            payload = assemble_evidence_index_input(
                manifest_uri,
                root / "manifest/release-out/release-manifest.json",
                signature_uri,
                root / "signature/release-out/manifest-signature/release-manifest.sigstore.json",
                datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            )
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(canonical_bytes(payload))
    except (OSError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print("assemble-evidence-index-input: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
