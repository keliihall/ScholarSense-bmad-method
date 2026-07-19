#!/usr/bin/env python3
"""Read back and reconcile a protected promotion from GHCR and its Git-ref ledger."""

from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))

from promotion import (  # noqa: E402
    GitRefLedger,
    OrasDigestStore,
    PromotionError,
    PromotionService,
)


class _UnusedVerifier:
    def verify(self, *_args):  # pragma: no cover - reconcile never invokes this port
        raise AssertionError("reconcile must not mint a verification receipt")


def main(argv: list[str]) -> int:
    if len(argv) != 6:
        print("usage: check_promotion.py RELEASE_VERSION TARGET_ENV TARGET_REPOSITORY ORAS REPOSITORY", file=sys.stderr)
        return 2
    release_version, target_environment, target_repository, oras, repository = argv[1:]
    try:
        service = PromotionService(
            _UnusedVerifier(),
            OrasDigestStore(Path(oras)),
            GitRefLedger(repository),
            {target_environment: target_repository},
        )
        record = service.reconcile(release_version, target_environment, datetime.now(timezone.utc))
    except (OSError, PromotionError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"promotion-reconciliation: PASS ({record['ledgerUri']}, {record['artifactOciDigest']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
