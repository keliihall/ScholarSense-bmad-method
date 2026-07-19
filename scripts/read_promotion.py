#!/usr/bin/env python3
"""Fetch one immutable promotion ledger record for current-gate rollback."""

from __future__ import annotations

import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from promotion import GitRefLedger, PromotionError, promotion_record_issues  # noqa: E402
from release_json import canonical_bytes  # noqa: E402


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print("usage: read_promotion.py RELEASE_VERSION ENVIRONMENT REPOSITORY OUTPUT.json", file=sys.stderr)
        return 2
    release_version, environment, repository = argv[1:4]
    output = Path(argv[4])
    try:
        if output.exists():
            raise PromotionError("PROMOTION_OUTPUT_ALREADY_EXISTS")
        ledger = GitRefLedger(repository)
        record = ledger.read(release_version, environment)
        issues = promotion_record_issues(record, release_version, environment, ledger.uri(release_version, environment))
        if issues:
            raise PromotionError(issues[0])
        assert record is not None
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(canonical_bytes(record) + b"\n")
    except (OSError, PromotionError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"read-promotion: PASS ({record['ledgerUri']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
