from __future__ import annotations

import copy
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_json import load_json  # noqa: E402
from release_policy import (  # noqa: E402
    install_script_inventory,
    license_issues,
    promotion_record_set_issues,
    sbom_reconciliation_issues,
    signature_bundle_issues,
    source_identity_issues,
    vulnerability_issues,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = PROJECT_ROOT / "contracts/release"


class ReleaseSecurityPolicyTest(unittest.TestCase):
    def test_critical_high_and_unknown_vulnerabilities_fail_closed(self) -> None:
        policy = load_json(CONTRACTS / "vulnerability-policy-1.0.0.json")
        subject = "a" * 64
        findings = [
            {"purl": "pkg:npm/a@1.0.0", "vulnerabilityId": "CVE-1", "severity": "CRITICAL"},
            {"purl": "pkg:npm/b@1.0.0", "vulnerabilityId": "CVE-2", "severity": "HIGH"},
            {"purl": "pkg:npm/c@1.0.0", "vulnerabilityId": "CVE-3", "severity": "UNKNOWN"},
        ]
        issues = vulnerability_issues(
            findings,
            policy,
            [],
            subject,
            datetime(2026, 7, 19, tzinfo=timezone.utc),
        )
        self.assertEqual(3, len(issues))

    def test_exact_unexpired_exception_is_accepted_but_expired_or_wrong_subject_is_not(self) -> None:
        policy = load_json(CONTRACTS / "vulnerability-policy-1.0.0.json")
        finding = {"purl": "pkg:npm/a@1.0.0", "vulnerabilityId": "CVE-1", "severity": "HIGH"}
        exception = {
            "exceptionId": "VEX-2026-001",
            "purl": finding["purl"],
            "vulnerabilityId": finding["vulnerabilityId"],
            "subjectSha256": "a" * 64,
            "reason": "Named compensating control is active.",
            "approvedBy": "Hei",
            "expiresAt": "2026-08-01T00:00:00Z",
        }
        now = datetime(2026, 7, 19, tzinfo=timezone.utc)
        self.assertEqual([], vulnerability_issues([finding], policy, [exception], "a" * 64, now))
        expired = copy.deepcopy(exception)
        expired["expiresAt"] = "2026-07-01T00:00:00Z"
        self.assertTrue(vulnerability_issues([finding], policy, [expired], "a" * 64, now))
        self.assertTrue(vulnerability_issues([finding], policy, [exception], "b" * 64, now))

    def test_unknown_denied_and_unlisted_licenses_are_blocked(self) -> None:
        policy = load_json(CONTRACTS / "license-policy-1.0.0.json")
        self.assertEqual([], license_issues([{"purl": "pkg:npm/a@1", "license": "MIT"}], policy))
        for license_name in ("UNKNOWN", "AGPL-3.0-only", "WTFPL"):
            with self.subTest(license=license_name):
                self.assertTrue(license_issues([{"purl": "pkg:npm/a@1", "license": license_name}], policy))

    def test_current_install_scripts_are_inventoried_and_remain_not_executed(self) -> None:
        lock = load_json(PROJECT_ROOT / "frontend/package-lock.json")
        inventory = install_script_inventory(lock, approved_paths=[])
        self.assertEqual(
            {
                "node_modules/@tanstack/vue-query/node_modules/vue-demi",
                "node_modules/fsevents",
                "node_modules/vite/node_modules/fsevents",
            },
            {item["path"] for item in inventory},
        )
        self.assertTrue(all(item["decision"] == "blocked-not-executed" for item in inventory))

    def test_signature_bundle_binds_subject_identity_issuer_and_current_run(self) -> None:
        expected = {
            "subjectSha256": "a" * 64,
            "certificateIdentity": "https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/release.yml@refs/tags/release-v1.0.0",
            "oidcIssuer": "https://token.actions.githubusercontent.com",
            "integratedAt": "2026-07-19T06:00:00Z",
            "runId": "100",
        }
        self.assertEqual(
            [],
            signature_bundle_issues(
                expected,
                "a" * 64,
                expected["certificateIdentity"],
                expected["oidcIssuer"],
                "100",
                datetime(2026, 7, 19, 5, 59, tzinfo=timezone.utc),
            ),
        )
        mutations = (
            ("subjectSha256", "b" * 64),
            ("certificateIdentity", "https://github.com/attacker/repo/workflow"),
            ("oidcIssuer", "https://issuer.example"),
            ("runId", "99"),
            ("integratedAt", "2026-07-19T05:00:00Z"),
        )
        for field, value in mutations:
            with self.subTest(field=field):
                bundle = {**expected, field: value}
                self.assertTrue(
                    signature_bundle_issues(
                        bundle,
                        "a" * 64,
                        expected["certificateIdentity"],
                        expected["oidcIssuer"],
                        "100",
                        datetime(2026, 7, 19, 5, 59, tzinfo=timezone.utc),
                    )
                )

    def test_partial_promotion_and_concurrent_winners_are_rejected(self) -> None:
        winner = {
            "releaseVersion": "1.0.0",
            "targetEnvironment": "stage",
            "manifestSha256": "a" * 64,
            "artifactOciDigest": "sha256:" + "b" * 64,
            "evidenceIndexSha256": "c" * 64,
            "ledgerUri": "https://api.github.com/repos/example/repo/git/ref/tags/promotion-ledger/1.0.0-stage",
            "result": "promoted",
        }
        replay = {**winner, "result": "replayed"}
        self.assertEqual([], promotion_record_set_issues([winner, replay]))
        partial = {**winner, "ledgerUri": ""}
        self.assertTrue(promotion_record_set_issues([partial]))
        two_winners = [winner, copy.deepcopy(winner)]
        self.assertTrue(promotion_record_set_issues(two_winners))
        conflict = [{**winner}, {**winner, "manifestSha256": "d" * 64, "result": "conflict"}]
        self.assertTrue(promotion_record_set_issues(conflict))

    def test_source_identity_rejects_no_vcs_dirty_and_ref_or_commit_drift(self) -> None:
        commit = "a" * 40
        ref = "refs/tags/release-v1.0.0"
        self.assertEqual([], source_identity_issues(commit, commit, ref, ref, False))
        cases = (
            ("NO_VCS", commit, ref, ref, False),
            (commit, "b" * 40, ref, ref, False),
            (commit, commit, "refs/heads/main", ref, False),
            (commit, commit, ref, ref, True),
        )
        for candidate in cases:
            with self.subTest(candidate=candidate):
                self.assertTrue(source_identity_issues(*candidate))

    def test_sbom_reconciliation_rejects_missing_extra_and_wrong_subject(self) -> None:
        expected = {"pkg:npm/a@1.0.0", "pkg:maven/org.example/b@1.0.0"}
        subject = "a" * 64
        sbom = {
            "subjectArtifactSha256": subject,
            "components": [{"purl": purl} for purl in sorted(expected)],
        }
        self.assertEqual([], sbom_reconciliation_issues(expected, sbom, subject))
        missing = copy.deepcopy(sbom)
        missing["components"].pop()
        self.assertTrue(sbom_reconciliation_issues(expected, missing, subject))
        extra = copy.deepcopy(sbom)
        extra["components"].append({"purl": "pkg:npm/extra@1.0.0"})
        self.assertTrue(sbom_reconciliation_issues(expected, extra, subject))
        self.assertTrue(sbom_reconciliation_issues(expected, sbom, "b" * 64))


if __name__ == "__main__":
    unittest.main()
