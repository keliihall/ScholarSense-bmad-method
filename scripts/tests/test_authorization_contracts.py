from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_authorization_contracts import (  # noqa: E402
    fixture_issues,
    policy_issues,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
AUTHORIZATION = PROJECT_ROOT / "contracts/authorization"


class AuthorizationContractTest(unittest.TestCase):
    def test_production_contracts_schemas_successors_and_locks_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_rfp_freezes_seven_roles_expanded_actions_and_field_matrix(self) -> None:
        policy = self.load("role-field-policy-rfp-1.0.0.json")
        roles = {item["roleId"]: item for item in policy["roles"]}

        self.assertEqual({f"R{index}" for index in range(1, 8)}, set(roles))
        self.assertEqual(
            {"B", "I", "C", "S", "E", "N", "G", "T"},
            set(policy["fieldClasses"]),
        )
        self.assertTrue(
            all("/" not in action for role in roles.values() for action in role["actions"])
        )
        self.assertEqual(
            ["R1", "R5", "R3", "R6", "R2", "R4", "R7"],
            policy["defaultSurfaceSelection"]["stableTieBreaker"],
        )
        self.assertEqual(
            {field: "C" for field in ("B", "I", "C", "S", "E", "N", "G")}
            | {"T": "M"},
            roles["R1"]["fieldVisibility"],
        )

    def test_fixture_is_a_complete_fixed_oracle(self) -> None:
        policy = self.load("role-field-policy-rfp-1.0.0.json")
        fixture = self.load("rfp-fixture-1.0.0.json")

        self.assertEqual([], fixture_issues(policy, fixture))
        self.assertEqual("2026-07-17T08:00:00Z", fixture["serverNow"])
        self.assertTrue({f"SUBJECT-R{index}" for index in range(1, 8)}.issubset({
            subject["subjectId"] for subject in fixture["subjects"]
        }))
        self.assertTrue(
            {
                "CASE-A", "CASE-B", "WORKITEM-A", "TRANSFER-A", "TRANSFER-B",
                "REPORT-COL-A", "REPORT-SCHOOL", "RULE-1", "DQ-A", "DQ-B", "JOB-1",
            }.issubset({item["objectToken"] for item in fixture["objects"]})
        )
        scenarios = {item["scenarioId"]: item for item in fixture["oracle"]}
        self.assertEqual("ALLOW", scenarios["R2-CASE-A-WORKITEM"]["expectedResult"])
        self.assertEqual("DENY", scenarios["R2-CASE-A-NO-WORKITEM"]["expectedResult"])
        self.assertEqual("DENY", scenarios["R7-CASE-A"]["expectedResult"])
        self.assertEqual("DENY", scenarios["UNKNOWN-ACTION"]["expectedResult"])
        self.assertEqual("DENY", scenarios["LITERAL-SLASH-ACTION"]["expectedResult"])

    def test_policy_validation_fails_closed_for_unknowns_slashes_and_hrap_drift(self) -> None:
        policy = self.load("role-field-policy-rfp-1.0.0.json")

        unknown_role = copy.deepcopy(policy)
        unknown_role["roles"][0]["roleId"] = "R8"
        self.assert_reason(policy_issues(unknown_role), "AUTHORIZATION_ROLE_SET_INVALID")

        unknown_object = copy.deepcopy(policy)
        unknown_object["roles"][0]["objectClasses"].append("UnknownObject")
        self.assert_reason(policy_issues(unknown_object), "AUTHORIZATION_OBJECT_UNKNOWN")

        slash = copy.deepcopy(policy)
        slash["roles"][0]["actions"].append("care.read/candidate.review")
        self.assert_reason(policy_issues(slash), "AUTHORIZATION_ACTION_NOT_EXPANDED")

        unknown_field = copy.deepcopy(policy)
        unknown_field["roles"][0]["fieldVisibility"]["X"] = "C"
        self.assert_reason(policy_issues(unknown_field), "AUTHORIZATION_FIELD_MATRIX_INVALID")

        missing_hrap = copy.deepcopy(policy)
        missing_hrap["hrapMappings"].pop("transfer.submit")
        self.assert_reason(policy_issues(missing_hrap), "AUTHORIZATION_HRAP_MAPPING_INVALID")

        missing_version = copy.deepcopy(policy)
        missing_version.pop("policyVersion")
        self.assert_reason(policy_issues(missing_version), "AUTHORIZATION_POLICY_VERSION_INVALID")

    def test_default_surface_and_http_error_contracts_are_exact(self) -> None:
        surfaces = self.load("default-surface-manifest-1.0.0.json")
        self.assertEqual(
            {
                "R1": "care-workbench",
                "R2": "college-governance",
                "R3": "rule-operations-governance",
                "R4": "school-dashboard",
                "R5": "collaboration-orders",
                "R6": "data-quality",
                "R7": "technical-operations",
            },
            {item["roleId"]: item["surfaceId"] for item in surfaces["surfaces"]},
        )
        self.assertTrue(all(item["providerState"] == "not-installed" for item in surfaces["surfaces"]))

        errors = self.load("authorization-error-profile-1.0.0.json")
        self.assertEqual(
            {
                "object-unavailable": (404, "IDENTITY_AUTHORIZATION_OBJECT_UNAVAILABLE"),
                "surface-forbidden": (403, "IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN"),
                "dependency-unavailable": (503, "IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE"),
                "decision-stale": (409, "IDENTITY_AUTHORIZATION_DECISION_STALE"),
            },
            {
                item["condition"]: (item["httpStatus"], item["code"])
                for item in errors["errors"]
            },
        )
        self.assertEqual("no-store, no-cache", errors["responseHeaders"]["Cache-Control"])
        self.assertEqual("no-referrer", errors["responseHeaders"]["Referrer-Policy"])

    def test_authorized_shell_is_separate_from_frozen_current_session(self) -> None:
        schema = self.load("authorized-shell.schema.json")
        self.assertEqual(
            {
                "schemaVersion", "policyVersion", "fixtureVersion", "evaluatedAt",
                "defaultSurface", "menuItems", "entryCapabilities", "dependencyStatus",
            },
            set(schema["required"]),
        )
        current_session = json.loads(
            (PROJECT_ROOT / "contracts/identity/current-session.schema.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertNotIn("roles", current_session["properties"])
        self.assertNotIn("menuItems", current_session["properties"])

    def test_digest_lock_detects_any_authorization_contract_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "contracts/authorization"
            target.mkdir(parents=True)
            for source in AUTHORIZATION.rglob("*"):
                if source.is_file():
                    destination = target / source.relative_to(AUTHORIZATION)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(source.read_bytes())
            policy_path = target / "role-field-policy-rfp-1.0.0.json"
            policy_path.write_bytes(policy_path.read_bytes() + b"\n")

            issues = validate(root, include_successors=False)

        self.assert_reason(issues, "AUTHORIZATION_LOCK_DIGEST_MISMATCH")

    def test_audit_1_0_through_1_3_locks_remain_immutable(self) -> None:
        expected = {
            "audit-contract-lock-1.0.0.json": "24a4861ddbfa9ac11253a26f6e3c906f1735d73c4d774d713faf9cee9f8cb62f",
            "audit-contract-lock-1.1.0.json": "a07833c0c75ed28f6d0a534d697af0b09d3d5d8e0476f3c251c2bb8acd0439b9",
            "audit-contract-lock-1.2.0.json": "cabb6259c4c3c9405823dba93a29de16c81ef730fbb04592bb20a9ad61f48c19",
            "audit-contract-lock-1.3.0.json": "cd47617b3447c3476fa8b1036a2e488c2d527404eaf0de0f5a64444a74a29b7c",
        }
        for name, digest in expected.items():
            path = PROJECT_ROOT / "contracts/audit" / name
            self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest(), name)

    def test_release_manifest_binds_rfp_fixture_and_audit_successor(self) -> None:
        sys.path.insert(0, str(PROJECT_ROOT / "release"))
        from assembly import CONTROLLED_INPUTS  # noqa: PLC0415
        from manifests import REQUIRED_CONTROLLED_INPUT_IDS  # noqa: PLC0415

        self.assertEqual(
            ("RFP-1.0.0", "contracts/authorization/role-field-policy-rfp-1.0.0.json"),
            CONTROLLED_INPUTS["RoleField"],
        )
        self.assertEqual(
            ("RFP-FIXTURE-1.0.0", "contracts/authorization/rfp-fixture-1.0.0.json"),
            CONTROLLED_INPUTS["RoleFieldFixture"],
        )
        self.assertEqual(
            ("AUDIT-CONTRACT-LOCK-1.4.0", "contracts/audit/audit-contract-lock-1.4.0.json"),
            CONTROLLED_INPUTS["AuthorizationAudit"],
        )
        self.assertTrue(
            {"RoleField", "RoleFieldFixture", "AuthorizationAudit"}.issubset(
                REQUIRED_CONTROLLED_INPUT_IDS
            )
        )

    @staticmethod
    def load(name: str) -> dict:
        return json.loads((AUTHORIZATION / name).read_text(encoding="utf-8"))

    def assert_reason(self, issues: list[str], reason: str) -> None:
        self.assertTrue(
            any(value.startswith(reason) for value in issues),
            f"expected {reason}, got {issues}",
        )


if __name__ == "__main__":
    unittest.main()
