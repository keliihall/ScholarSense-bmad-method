#!/usr/bin/env python3
"""Validate the Story 1.7 RFP, oracle, HTTP, audit successor, and digest locks."""

from __future__ import annotations

import copy
import hashlib
import sys
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import load_json, schema_definition_issues, schema_issues  # noqa: E402


AUTHORIZATION = Path("contracts/authorization")
AUDIT = Path("contracts/audit")
POLICY = AUTHORIZATION / "role-field-policy-rfp-1.0.0.json"
FIXTURE = AUTHORIZATION / "rfp-fixture-1.0.0.json"
AUTHORIZATION_LOCK = AUTHORIZATION / "authorization-contract-lock-1.0.0.json"
AUDIT_LOCK = AUDIT / "audit-contract-lock-1.4.0.json"

ROLE_OBJECTS = {
    "R1": {"Candidate", "Clue", "CareAction", "Observation", "Task", "TransferOrder", "DelegationGrant"},
    "R2": {"AggregateReport", "Candidate", "Clue", "CareAction", "Governance"},
    "R3": {"Rule", "Tag", "Program", "Governance", "Strategy", "AggregateReport", "Candidate", "Clue", "CareAction"},
    "R4": {"AggregateReport", "Governance"},
    "R5": {"TransferOrder"},
    "R6": {"Source", "Dependency", "QualitySnapshot", "RecoveryTask", "SubjectMappingException"},
    "R7": {"Runtime", "Job", "Delivery", "Telemetry", "Config"},
}
ROLE_ACTIONS = {
    "R1": {"care.read", "candidate.review", "clue.follow-up", "observation.manage", "transfer.submit", "transfer.read", "transfer.resubmit", "delegation.issue", "delegation.revoke"},
    "R2": {"aggregate.read", "aggregate.export", "care.read", "governance-action.record", "governance-action.manage"},
    "R3": {"governance.read", "governance.edit", "governance.review", "governance.publish", "governance.rollback", "whitelist.change", "bulk.execute", "aggregate.read", "aggregate.export", "governance-action.record", "governance-action.manage", "audit.search-business-metadata", "sensitive-export.create", "sensitive-export.download"},
    "R4": {"aggregate.read", "aggregate.export", "governance-action.record", "governance-action.manage"},
    "R5": {"care.read", "transfer.read", "transfer.accept", "transfer.process", "transfer.request-supplement", "transfer.fill-result", "transfer.close"},
    "R6": {"data-quality.read", "data-quality.repair", "data-quality.reconcile", "quality-fuse.recover", "platform.read-source", "sensitive-export.create", "sensitive-export.download"},
    "R7": {"platform.read", "platform.diagnose", "platform.retry", "platform.reconcile", "role-binding.apply-approved", "audit.search-technical-metadata"},
}
FIELD_MATRIX = {
    "R1": {"B": "C", "I": "C", "C": "C", "S": "C", "E": "C", "N": "C", "G": "C", "T": "M"},
    "R2": {"B": "C", "I": "M", "C": "H", "S": "H", "E": "M", "N": "H", "G": "C", "T": "M"},
    "R3": {"B": "C", "I": "M", "C": "H", "S": "M", "E": "M", "N": "H", "G": "C", "T": "M"},
    "R4": {"B": "C", "I": "H", "C": "H", "S": "H", "E": "H", "N": "H", "G": "C", "T": "M"},
    "R5": {"B": "C", "I": "C", "C": "C", "S": "C", "E": "M", "N": "C", "G": "H", "T": "M"},
    "R6": {"B": "C", "I": "M", "C": "H", "S": "H", "E": "C", "N": "H", "G": "C", "T": "C"},
    "R7": {"B": "C", "I": "H", "C": "H", "S": "H", "E": "H", "N": "H", "G": "M", "T": "C"},
}
HRAP_MAPPINGS = {
    "delegation.issue": "temporary-grant.issue",
    "delegation.revoke": "temporary-grant.revoke",
    "whitelist.change": "whitelist.create-or-change",
    "bulk.execute": "bulk-governance.execute",
    "Rule:governance.publish": "rule.publish",
    "Rule:governance.rollback": "rule.rollback",
    "Strategy:governance.publish": "strategy.publish",
    "Strategy:governance.rollback": "strategy.rollback",
    "governance-action.record": "leader-action.record",
    "sensitive-export.create": "sensitive-export.create",
    "sensitive-export.download": "sensitive-export.download",
    "quality-fuse.recover": "quality-fuse.recover",
    "transfer.submit": "transfer.submit",
}
SURFACES = {
    "R1": "care-workbench",
    "R2": "college-governance",
    "R3": "rule-operations-governance",
    "R4": "school-dashboard",
    "R5": "collaboration-orders",
    "R6": "data-quality",
    "R7": "technical-operations",
}
OLD_AUDIT_LOCK_DIGESTS = {
    "audit-contract-lock-1.0.0.json": "24a4861ddbfa9ac11253a26f6e3c906f1735d73c4d774d713faf9cee9f8cb62f",
    "audit-contract-lock-1.1.0.json": "a07833c0c75ed28f6d0a534d697af0b09d3d5d8e0476f3c251c2bb8acd0439b9",
    "audit-contract-lock-1.2.0.json": "cabb6259c4c3c9405823dba93a29de16c81ef730fbb04592bb20a9ad61f48c19",
    "audit-contract-lock-1.3.0.json": "cd47617b3447c3476fa8b1036a2e488c2d527404eaf0de0f5a64444a74a29b7c",
}

AUTHORIZATION_SCHEMAS = {
    AUTHORIZATION / "role-field-policy-rfp-1.0.0.json": AUTHORIZATION / "role-field-policy.schema.json",
    AUTHORIZATION / "rfp-fixture-1.0.0.json": AUTHORIZATION / "rfp-fixture.schema.json",
    AUTHORIZATION / "default-surface-manifest-1.0.0.json": AUTHORIZATION / "default-surface-manifest.schema.json",
    AUTHORIZATION / "authorization-error-profile-1.0.0.json": AUTHORIZATION / "authorization-error-profile.schema.json",
    AUTHORIZATION / "fixtures/valid/authorized-shell.json": AUTHORIZATION / "authorized-shell.schema.json",
}
AUDIT_SUCCESSOR_SCHEMAS = {
    AUDIT / "action-catalog-1.4.0.json": AUDIT / "action-catalog-1.4.schema.json",
    AUDIT / "identity-access-vocabulary-1.4.0.json": AUDIT / "identity-access-vocabulary-1.4.schema.json",
    AUDIT / "fixtures/valid/authorization-decision.json": AUDIT / "authorization-audit-context.schema.json",
}
AUTHORIZATION_LOCKED_FILES = frozenset(
    {
        "contracts/authorization/role-field-policy.schema.json",
        "contracts/authorization/role-field-policy-rfp-1.0.0.json",
        "contracts/authorization/rfp-fixture.schema.json",
        "contracts/authorization/rfp-fixture-1.0.0.json",
        "contracts/authorization/default-surface-manifest.schema.json",
        "contracts/authorization/default-surface-manifest-1.0.0.json",
        "contracts/authorization/authorization-error-profile.schema.json",
        "contracts/authorization/authorization-error-profile-1.0.0.json",
        "contracts/authorization/authorized-shell.schema.json",
        "contracts/authorization/fixtures/valid/authorized-shell.json",
        "contracts/authorization/fixtures/invalid/negative-fixtures-1.0.0.json",
    }
)
AUDIT_LOCKED_FILES = frozenset(
    {
        "contracts/audit/action-catalog-1.4.0.json",
        "contracts/audit/action-catalog-1.4.schema.json",
        "contracts/audit/identity-access-vocabulary-1.4.0.json",
        "contracts/audit/identity-access-vocabulary-1.4.schema.json",
        "contracts/audit/authorization-audit-context.schema.json",
        "contracts/audit/fixtures/valid/authorization-decision.json",
    }
)


def validate(project_root: Path, *, include_successors: bool = True) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    documents: dict[Path, Any] = {}
    issues.extend(_schema_contract_issues(root, AUTHORIZATION_SCHEMAS, documents, "AUTHORIZATION"))
    policy = documents.get(POLICY)
    fixture = documents.get(FIXTURE)
    issues.extend(policy_issues(policy))
    issues.extend(fixture_issues(policy, fixture))
    issues.extend(_surface_issues(documents.get(AUTHORIZATION / "default-surface-manifest-1.0.0.json")))
    issues.extend(_error_profile_issues(documents.get(AUTHORIZATION / "authorization-error-profile-1.0.0.json")))
    issues.extend(_negative_fixture_issues(root, policy))
    issues.extend(_lock_issues(root, AUTHORIZATION_LOCK, AUTHORIZATION_LOCKED_FILES, "AUTHORIZATION"))
    if include_successors:
        successor_documents: dict[Path, Any] = {}
        issues.extend(_schema_contract_issues(root, AUDIT_SUCCESSOR_SCHEMAS, successor_documents, "AUTHORIZATION_AUDIT"))
        issues.extend(_audit_successor_issues(successor_documents))
        issues.extend(_old_audit_lock_issues(root))
        issues.extend(_lock_issues(root, AUDIT_LOCK, AUDIT_LOCKED_FILES, "AUTHORIZATION_AUDIT"))
        issues.extend(_release_binding_issues(root))
    return sorted(set(issues))


def policy_issues(policy: Any) -> list[str]:
    if not isinstance(policy, dict):
        return ["AUTHORIZATION_POLICY_INVALID"]
    issues: list[str] = []
    if policy.get("policyVersion") != "RFP-1.0.0" or policy.get("fixtureVersion") != "RFP-FIXTURE-1.0.0":
        issues.append("AUTHORIZATION_POLICY_VERSION_INVALID")
    if policy.get("status") != "approved" or policy.get("unknownSemantics") != "fail-closed":
        issues.append("AUTHORIZATION_POLICY_STATE_INVALID")
    roles = {
        item.get("roleId"): item
        for item in policy.get("roles", [])
        if isinstance(item, dict) and isinstance(item.get("roleId"), str)
    }
    if set(roles) != set(ROLE_ACTIONS) or len(roles) != len(policy.get("roles", [])):
        issues.append("AUTHORIZATION_ROLE_SET_INVALID")
    known_objects = set(policy.get("objectClasses", []))
    if known_objects != set().union(*ROLE_OBJECTS.values()):
        issues.append("AUTHORIZATION_OBJECT_SET_INVALID")
    known_actions = set().union(*ROLE_ACTIONS.values())
    for role_id, role in roles.items():
        objects = set(role.get("objectClasses", []))
        actions = set(role.get("actions", []))
        if not objects <= known_objects:
            issues.append(f"AUTHORIZATION_OBJECT_UNKNOWN: {role_id}")
        if role_id in ROLE_OBJECTS and objects != ROLE_OBJECTS[role_id]:
            issues.append(f"AUTHORIZATION_ROLE_OBJECT_MATRIX_INVALID: {role_id}")
        if any("/" in action for action in actions if isinstance(action, str)):
            issues.append(f"AUTHORIZATION_ACTION_NOT_EXPANDED: {role_id}")
        if not actions <= known_actions:
            issues.append(f"AUTHORIZATION_ACTION_UNKNOWN: {role_id}")
        if role_id in ROLE_ACTIONS and actions != ROLE_ACTIONS[role_id]:
            issues.append(f"AUTHORIZATION_ROLE_ACTION_MATRIX_INVALID: {role_id}")
        if role_id in FIELD_MATRIX and role.get("fieldVisibility") != FIELD_MATRIX[role_id]:
            issues.append(f"AUTHORIZATION_FIELD_MATRIX_INVALID: {role_id}")
        for scope_rule in role.get("scopeRules", []):
            if isinstance(scope_rule, dict) and not set(scope_rule.get("objectClasses", [])) <= objects:
                issues.append(f"AUTHORIZATION_SCOPE_OBJECT_INVALID: {role_id}")
            if isinstance(scope_rule, dict) and not set(scope_rule.get("anyOfAnchors", [])) <= set(policy.get("scopeAnchors", [])):
                issues.append(f"AUTHORIZATION_SCOPE_ANCHOR_INVALID: {role_id}")
    if set(policy.get("fieldClasses", [])) != set("BICSENGT") or policy.get("visibilityOrder") != ["C", "M", "H"]:
        issues.append("AUTHORIZATION_FIELD_MATRIX_INVALID")
    if policy.get("hrapMappings") != HRAP_MAPPINGS or set(policy.get("hrapRequiredActions", [])) != set(HRAP_MAPPINGS):
        issues.append("AUTHORIZATION_HRAP_MAPPING_INVALID")
    selection = policy.get("defaultSurfaceSelection", {})
    if selection.get("stableTieBreaker") != ["R1", "R5", "R3", "R6", "R2", "R4", "R7"]:
        issues.append("AUTHORIZATION_SURFACE_TIE_BREAKER_INVALID")
    if {role_id: item.get("defaultSurfaceId") for role_id, item in roles.items()} != SURFACES:
        issues.append("AUTHORIZATION_DEFAULT_SURFACE_INVALID")
    delegation = policy.get("delegationSemantics", {})
    if delegation != {"timeWindow": "[startAt,endAt)", "effectivePermission": "base-intersection-grant", "preCommitRecheck": ["relation", "grant", "policy", "objectVersion"], "mayExpand": False}:
        issues.append("AUTHORIZATION_DELEGATION_SEMANTICS_INVALID")
    return issues


def fixture_issues(policy: Any, fixture: Any) -> list[str]:
    if not isinstance(policy, dict) or not isinstance(fixture, dict):
        return ["AUTHORIZATION_FIXTURE_INVALID"]
    issues: list[str] = []
    if fixture.get("fixtureVersion") != policy.get("fixtureVersion") or fixture.get("policyVersion") != policy.get("policyVersion"):
        issues.append("AUTHORIZATION_FIXTURE_VERSION_INVALID")
    if fixture.get("serverNow") != "2026-07-17T08:00:00Z":
        issues.append("AUTHORIZATION_FIXTURE_CLOCK_INVALID")
    subjects = {item.get("subjectId"): item for item in fixture.get("subjects", []) if isinstance(item, dict)}
    required_subjects = {f"SUBJECT-R{index}" for index in range(1, 8)} | {"SUBJECT-R1+R2", "SUBJECT-R1+R7"}
    if set(subjects) != required_subjects:
        issues.append("AUTHORIZATION_FIXTURE_SUBJECT_SET_INVALID")
    objects = {item.get("objectToken"): item for item in fixture.get("objects", []) if isinstance(item, dict)}
    required_objects = {"CASE-A", "CASE-B", "WORKITEM-A", "TRANSFER-A", "TRANSFER-B", "REPORT-COL-A", "REPORT-SCHOOL", "RULE-1", "DQ-A", "DQ-B", "JOB-1"}
    if set(objects) != required_objects:
        issues.append("AUTHORIZATION_FIXTURE_OBJECT_SET_INVALID")
    scenarios = {item.get("scenarioId"): item for item in fixture.get("oracle", []) if isinstance(item, dict)}
    required_scenarios = {
        "R1-CASE-A", "R1-CASE-B", "R1-TRANSFER-A", "R2-REPORT-COL",
        "R2-CASE-A-WORKITEM", "R2-CASE-A-NO-WORKITEM", "R3-RULE-READ",
        "R3-RULE-PUBLISH", "R3-AUDIT-BUSINESS", "R4-SCHOOL-REPORT", "R4-CASE-A",
        "R5-TRANSFER-A", "R5-TRANSFER-B", "R6-DQ-A", "R6-DQ-B", "R7-JOB-1",
        "R7-CASE-A", "R1R2-CASE-A", "R1R7-CASE-A", "UNKNOWN-ACTION",
        "LITERAL-SLASH-ACTION",
    }
    if set(scenarios) != required_scenarios:
        issues.append("AUTHORIZATION_FIXTURE_ORACLE_SET_INVALID")
    for scenario_id, scenario in scenarios.items():
        if scenario.get("subjectId") not in subjects or scenario.get("objectToken") not in objects:
            issues.append(f"AUTHORIZATION_FIXTURE_REFERENCE_INVALID: {scenario_id}")
        action = scenario.get("actionId")
        if isinstance(action, str) and "/" in action and scenario.get("expectedReason") != "ACTION_NOT_EXPANDED":
            issues.append(f"AUTHORIZATION_FIXTURE_SLASH_SEMANTICS_INVALID: {scenario_id}")
    transfer_a = objects.get("TRANSFER-A", {})
    if set(transfer_a.get("fieldAllowlist", [])) != {"studentContactPhone", "requestedServiceCode", "referralSummary", "resultSummary"}:
        issues.append("AUTHORIZATION_FIXTURE_TRANSFER_FIELDS_INVALID")
    return issues


def _schema_contract_issues(root: Path, mapping: dict[Path, Path], documents: dict[Path, Any], prefix: str) -> list[str]:
    issues: list[str] = []
    schemas: dict[Path, Any] = {}
    for schema_path in sorted(set(mapping.values())):
        try:
            schema = load_json(root / schema_path)
        except (OSError, ValueError):
            issues.append(f"{prefix}_SCHEMA_INVALID: {schema_path}")
            continue
        schemas[schema_path] = schema
        issues.extend(f"{prefix}_SCHEMA_INVALID: {schema_path}: {issue}" for issue in schema_definition_issues(schema))
    for document_path, schema_path in mapping.items():
        try:
            document = load_json(root / document_path)
        except (OSError, ValueError):
            issues.append(f"{prefix}_DOCUMENT_INVALID: {document_path}")
            continue
        documents[document_path] = document
        schema = schemas.get(schema_path)
        if schema is not None:
            found = schema_issues(document, schema)
            if found:
                issues.append(f"{prefix}_SCHEMA_REJECTED: {document_path}: {found[0]}")
    return issues


def _surface_issues(manifest: Any) -> list[str]:
    if not isinstance(manifest, dict):
        return ["AUTHORIZATION_SURFACE_MANIFEST_INVALID"]
    surfaces = {item.get("roleId"): item for item in manifest.get("surfaces", []) if isinstance(item, dict)}
    if {role_id: item.get("surfaceId") for role_id, item in surfaces.items()} != SURFACES:
        return ["AUTHORIZATION_SURFACE_MANIFEST_INVALID"]
    if any(item.get("providerState") != "not-installed" or item.get("runtimeEvidenceClaim") != "none" for item in surfaces.values()):
        return ["AUTHORIZATION_SURFACE_RUNTIME_EVIDENCE_FORGED"]
    return []


def _error_profile_issues(profile: Any) -> list[str]:
    if not isinstance(profile, dict):
        return ["AUTHORIZATION_ERROR_PROFILE_INVALID"]
    actual = {item.get("condition"): (item.get("httpStatus"), item.get("code")) for item in profile.get("errors", []) if isinstance(item, dict)}
    expected = {
        "object-unavailable": (404, "IDENTITY_AUTHORIZATION_OBJECT_UNAVAILABLE"),
        "surface-forbidden": (403, "IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN"),
        "dependency-unavailable": (503, "IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE"),
        "decision-stale": (409, "IDENTITY_AUTHORIZATION_DECISION_STALE"),
    }
    headers = profile.get("responseHeaders")
    return [] if actual == expected and headers == {"Cache-Control": "no-store, no-cache", "Pragma": "no-cache", "Referrer-Policy": "no-referrer"} else ["AUTHORIZATION_ERROR_PROFILE_INVALID"]


def _negative_fixture_issues(root: Path, policy: Any) -> list[str]:
    try:
        negative = load_json(root / AUTHORIZATION / "fixtures/invalid/negative-fixtures-1.0.0.json")
    except (OSError, ValueError):
        return ["AUTHORIZATION_NEGATIVE_FIXTURES_INVALID"]
    if not isinstance(policy, dict) or not isinstance(negative, dict):
        return ["AUTHORIZATION_NEGATIVE_FIXTURES_INVALID"]
    issues: list[str] = []
    for case in negative.get("cases", []):
        if not isinstance(case, dict):
            issues.append("AUTHORIZATION_NEGATIVE_FIXTURES_INVALID")
            continue
        candidate = copy.deepcopy(policy)
        mutation = case.get("mutation")
        value = case.get("value")
        if mutation == "replace-role-id":
            candidate["roles"][0]["roleId"] = value
        elif mutation == "append-object-class":
            candidate["roles"][0]["objectClasses"].append(value)
        elif mutation == "append-action":
            candidate["roles"][0]["actions"].append(value)
        elif mutation == "append-field":
            candidate["roles"][0]["fieldVisibility"][value] = "C"
        elif mutation == "remove-policy-version":
            candidate.pop("policyVersion", None)
        elif mutation == "remove-hrap-mapping":
            candidate["hrapMappings"].pop(value, None)
        else:
            issues.append(f"AUTHORIZATION_NEGATIVE_FIXTURE_MUTATION_UNKNOWN: {case.get('caseId')}")
            continue
        expected = case.get("expectedReason")
        if not any(found.startswith(str(expected)) for found in policy_issues(candidate)):
            issues.append(f"AUTHORIZATION_NEGATIVE_FIXTURE_NOT_REJECTED: {case.get('caseId')}")
    return issues


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _lock_issues(root: Path, lock_path: Path, expected_files: frozenset[str], prefix: str) -> list[str]:
    try:
        lock = load_json(root / lock_path)
    except (OSError, ValueError):
        return [f"{prefix}_LOCK_INVALID"]
    entries = lock.get("files", []) if isinstance(lock, dict) else []
    by_path = {item.get("path"): item for item in entries if isinstance(item, dict)}
    issues: list[str] = []
    if set(by_path) != set(expected_files) or len(by_path) != len(entries):
        issues.append(f"{prefix}_LOCK_FILE_SET_INVALID")
    for relative, item in by_path.items():
        path = root / str(relative)
        if not path.is_file() or item.get("sha256") != _sha256(path):
            issues.append(f"{prefix}_LOCK_DIGEST_MISMATCH: {relative}")
    return issues


def _audit_successor_issues(documents: dict[Path, Any]) -> list[str]:
    catalog = documents.get(AUDIT / "action-catalog-1.4.0.json")
    vocabulary = documents.get(AUDIT / "identity-access-vocabulary-1.4.0.json")
    context = documents.get(AUDIT / "fixtures/valid/authorization-decision.json")
    if not isinstance(catalog, dict) or not isinstance(vocabulary, dict) or not isinstance(context, dict):
        return ["AUTHORIZATION_AUDIT_SUCCESSOR_INVALID"]
    actions = {item.get("code") for item in catalog.get("actions", []) if isinstance(item, dict)}
    checks = (
        catalog.get("version") == "AUDIT-ACTION-CATALOG-1.4.0",
        catalog.get("supersedes") == "AUDIT-ACTION-CATALOG-1.3.0",
        actions == {"authorization.object.decided", "authorization.shell.viewed", "authorization.decision.rechecked"},
        vocabulary.get("version") == "IDENTITY-AUDIT-VOCABULARY-1.4.0",
        vocabulary.get("supersedes") == "IDENTITY-AUDIT-VOCABULARY-1.3.0",
        vocabulary.get("roleIds") == ["R1", "R2", "R3", "R4", "R5", "R6", "R7"],
        vocabulary.get("policyVersions", {}).get("roleFieldPolicy") == "RFP-1.0.0",
        context.get("policyVersion") == "RFP-1.0.0",
        context.get("result") in {"ALLOW", "DENY", "DEPENDENCY_UNAVAILABLE"},
    )
    return [] if all(checks) else ["AUTHORIZATION_AUDIT_SUCCESSOR_INVALID"]


def _old_audit_lock_issues(root: Path) -> list[str]:
    issues: list[str] = []
    for name, expected in OLD_AUDIT_LOCK_DIGESTS.items():
        path = root / AUDIT / name
        if not path.is_file() or _sha256(path) != expected:
            issues.append(f"AUTHORIZATION_OLD_AUDIT_LOCK_CHANGED: {name}")
    return issues


def _release_binding_issues(root: Path) -> list[str]:
    try:
        assembly = (root / "release/assembly.py").read_text(encoding="utf-8")
        manifests = (root / "release/manifests.py").read_text(encoding="utf-8")
        schema = load_json(root / "contracts/release/release-manifest.schema.json")
    except (OSError, ValueError):
        return ["AUTHORIZATION_RELEASE_BINDING_INVALID"]
    required = (
        '"RoleField": ("RFP-1.0.0", "contracts/authorization/role-field-policy-rfp-1.0.0.json")',
        '"RoleFieldFixture": ("RFP-FIXTURE-1.0.0", "contracts/authorization/rfp-fixture-1.0.0.json")',
        '"AuthorizationAudit": ("AUDIT-CONTRACT-LOCK-1.4.0", "contracts/audit/audit-contract-lock-1.4.0.json")',
        '"RoleFieldFixture"',
        '"AuthorizationAudit"',
    )
    controlled = schema.get("properties", {}).get("controlledInputs", {}) if isinstance(schema, dict) else {}
    if not all(value in assembly + manifests for value in required) or controlled.get("minItems") != 19 or controlled.get("maxItems") != 19:
        return ["AUTHORIZATION_RELEASE_BINDING_INVALID"]
    return []


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("authorization-contracts: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
