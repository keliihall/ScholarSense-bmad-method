#!/usr/bin/env python3
"""Verify a local release tool/archive against the controlled toolchain lock."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from release_json import load_json  # noqa: E402


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(f"usage: {argv[0]} PROJECT_ROOT TOOL_NAME FILE", file=sys.stderr)
        return 2
    root = Path(argv[1]).resolve()
    name = argv[2]
    path = Path(argv[3]).resolve()
    try:
        lock = load_json(root / "contracts/release/toolchain-lock-1.0.0.json")
        matches = [item for item in lock["tools"] if item.get("name") == name]
        if len(matches) != 1:
            raise ValueError("RELEASE_TOOL_LOCK_ENTRY_INVALID")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != matches[0].get("binarySha256"):
            raise ValueError("RELEASE_TOOL_SHA256_MISMATCH")
    except (OSError, KeyError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"release-tool: PASS {name} {actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
