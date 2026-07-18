#!/usr/bin/env python3
"""Check the controlled backend Maven lock against local resolved artifacts."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "release"))
sys.path.insert(0, str(ROOT / "scripts"))

from backend_lock import validate_backend_lock  # noqa: E402
from release_json import load_json  # noqa: E402


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) == 2 else ROOT
    try:
        lock = load_json(root / "contracts/release/backend-lock-1.0.0.json")
        issues = validate_backend_lock(lock, root)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"backend-lock: PASS ({len(lock['dependencies'])} runtime, {len(lock['plugins'])} plugins)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
