from __future__ import annotations

import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_contract_seeds import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class ContractSeedTest(unittest.TestCase):
    def test_production_contract_seeds_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_unapproved_openapi_path_is_rejected(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/openapi/envelope.openapi.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["paths"] = {"/api/v1/clues": {}}
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "OPENAPI_AUDIT_PATH_SET_INVALID")

    def test_public_event_envelope_stays_generic(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/events/envelope.schema.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["$defs"] = {"ClueCreated": {"type": "object"}}
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "EVENT_ENVELOPE_DATA_EXTENSION_INVALID")

    def test_event_type_pattern_accepts_legacy_and_pic_names(self) -> None:
        value = json.loads(
            (PROJECT_ROOT / "contracts/events/envelope.schema.json").read_text(
                encoding="utf-8"
            )
        )
        pattern = value["properties"]["type"]["pattern"]

        self.assertIsNotNone(
            re.fullmatch(pattern, "identity-access.local-audit-fact.recorded.v1")
        )
        self.assertIsNotNone(
            re.fullmatch(
                pattern,
                "scholarsense.identity-access.responsibility.changed.v1",
            )
        )
        self.assertIsNone(
            re.fullmatch(pattern, "scholarsense.identity-access.responsibility.changed")
        )

    def test_public_event_envelope_keeps_legacy_optional_extensions(self) -> None:
        value = json.loads(
            (PROJECT_ROOT / "contracts/events/envelope.schema.json").read_text(
                encoding="utf-8"
            )
        )

        self.assertEqual(
            {"specversion", "id", "source", "type", "time", "data"},
            set(value["required"]),
        )
        self.assertIn("subject", value["properties"])
        self.assertIn("datacontenttype", value["properties"])

    def test_sensitive_client_variable_is_rejected(self) -> None:
        with self.fixture() as root:
            path = root / "frontend/src/app/config/client-env-allowlist.json"
            path.write_text(json.dumps(["VITE_SCHOLARSENSE_PUBLIC_TOKEN"]), encoding="utf-8")
            self.assert_reason(root, "CLIENT_ALLOWLIST_SENSITIVE_OR_UNSCOPED")

    def test_runtime_port_schema_must_reject_values_above_65535(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/config/runtime-config.schema.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["properties"]["SCHOLARSENSE_HTTP_PORT"]["pattern"] = "^[0-9]+$"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "RUNTIME_SCHEMA_PORT_RANGE_INVALID")

    def test_worker_business_http_is_rejected(self) -> None:
        with self.fixture() as root:
            path = root / "deploy/base/roles.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["roles"]["worker"]["businessHttp"] = True
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "WORKER_EXPOSES_BUSINESS_HTTP")

    def test_worker_capabilities_are_split_and_identity_sync_is_not_production_eligible(self) -> None:
        roles = json.loads((PROJECT_ROOT / "deploy/base/roles.json").read_text(encoding="utf-8"))
        audit_keys = {
            "SCHOLARSENSE_AUDIT_INGESTION_POLICY_REF",
            "SCHOLARSENSE_AUDIT_HASH_PROFILE_REF",
            "SCHOLARSENSE_AUDIT_COLLECTOR_REF",
            "SCHOLARSENSE_AUDIT_VERIFIER_REF",
            "SCHOLARSENSE_AUDIT_ALERT_TRANSPORT_REF",
            "SCHOLARSENSE_AUDIT_METRIC_BINDING_REF",
            "SCHOLARSENSE_AUDIT_RETENTION_CAPABILITY_REF",
        }
        sync_keys = {
            "SCHOLARSENSE_IDENTITY_AUTHORITY_PROFILE_REF",
            "SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF",
            "SCHOLARSENSE_IDENTITY_SYNC_SECURITY_DIRECTORY",
            "SCHOLARSENSE_IDENTITY_SYNC_ACCESS_INVALIDATION_CONSUMER_JDBC_URL",
            "SCHOLARSENSE_IDENTITY_SYNC_ACCESS_INVALIDATION_CONSUMER_USERNAME",
            "SCHOLARSENSE_IDENTITY_SYNC_ACCESS_INVALIDATION_CONSUMER_PASSWORD",
            "SCHOLARSENSE_IDENTITY_SYNC_RESPONSIBILITY_V2_CUTOVER_JDBC_URL",
            "SCHOLARSENSE_IDENTITY_SYNC_RESPONSIBILITY_V2_CUTOVER_USERNAME",
            "SCHOLARSENSE_IDENTITY_SYNC_RESPONSIBILITY_V2_CUTOVER_PASSWORD",
        }
        web_token_keys = {
            "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_PATH",
            "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_VERSION",
        }
        web_required = {
            *web_token_keys,
            "SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION",
            "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION",
            "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_URL",
            "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_USERNAME",
            "SCHOLARSENSE_SUBJECT_REGISTRY_DATASOURCE_PASSWORD",
            "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_URL",
            "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_USERNAME",
            "SCHOLARSENSE_SUBJECT_REGISTRY_RELAY_DATASOURCE_PASSWORD",
            "SCHOLARSENSE_SUBJECT_REGISTRY_ENVIRONMENT",
            "SCHOLARSENSE_SUBJECT_REGISTRY_KEY_REF",
            "SCHOLARSENSE_SUBJECT_REGISTRY_KEY_VERSION",
            "SCHOLARSENSE_SUBJECT_REGISTRY_ENCRYPTION_KEY_PATH",
            "SCHOLARSENSE_SUBJECT_REGISTRY_SEARCH_KEY_PATH",
        }

        self.assertTrue(
            (audit_keys | sync_keys | web_required).isdisjoint(
                roles["requiredEnvironment"]
            )
        )
        self.assertEqual(
            web_required,
            set(roles["roles"]["web-api"]["requiredEnvironment"]),
        )
        self.assertEqual(
            ["test", "stage", "prod"],
            roles["roles"]["web-api"]["allowedEnvironments"],
        )
        self.assertEqual(audit_keys, set(roles["roles"]["worker"]["requiredEnvironment"]))
        self.assertNotIn("allowedEnvironments", roles["roles"]["worker"])
        self.assertFalse(
            roles["roles"]["worker"]["environment"]["SCHOLARSENSE_IDENTITY_SYNC_ENABLED"]
            == "true"
        )
        sync = roles["roles"]["identity-sync-worker"]
        self.assertEqual(sync_keys, set(sync["requiredEnvironment"]))
        self.assertEqual("worker", sync["environment"]["SCHOLARSENSE_ROLE"])
        self.assertEqual("true", sync["environment"]["SCHOLARSENSE_IDENTITY_SYNC_ENABLED"])
        self.assertEqual("false", sync["environment"]["SCHOLARSENSE_AUDIT_LEDGER_ENABLED"])
        self.assertEqual(["dev", "test", "stage"], sync["allowedEnvironments"])
        self.assertFalse(sync["businessHttp"])

    def test_web_audit_token_key_mount_is_declared_and_fail_closed(self) -> None:
        profile = json.loads((
            PROJECT_ROOT
            / "deploy/base/ingestion-quality-runtime-1.0.0.json"
        ).read_text(encoding="utf-8"))
        tokenization = profile["identityAuditTokenization"]

        self.assertEqual(
            {
                "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_PATH",
                "SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_VERSION",
            },
            {
                tokenization["keyPathEnvironment"],
                tokenization["keyVersionEnvironment"],
            },
        )
        self.assertEqual(
            "absolute-protected-regular-non-symlink-mounted-file",
            tokenization["location"],
        )
        self.assertEqual("startup-fail-closed", tokenization["failureMode"])
        self.assertFalse(tokenization["committedMaterialAllowed"])
        self.assertEqual(
            ["test", "stage", "prod"],
            profile["sameArtifactRoles"]["web-api"]["allowedEnvironments"],
        )

        with self.fixture() as root:
            path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
            mutated = json.loads(path.read_text(encoding="utf-8"))
            mutated["identityAuditTokenization"]["failureMode"] = "fallback"
            path.write_text(json.dumps(mutated), encoding="utf-8")
            self.assert_reason(root, "IDENTITY_AUDIT_TOKEN_BINDING_INVALID")

        with self.fixture() as root:
            path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
            mutated = json.loads(path.read_text(encoding="utf-8"))
            mutated["sameArtifactRoles"]["web-api"]["allowedEnvironments"] = [
                "dev", "test", "stage", "prod"
            ]
            path.write_text(json.dumps(mutated), encoding="utf-8")
            self.assert_reason(root, "INGESTION_QUALITY_WEB_BINDINGS_INVALID")

        with self.fixture() as root:
            path = root / "deploy/base/roles.json"
            mutated = json.loads(path.read_text(encoding="utf-8"))
            mutated["roles"]["web-api"]["allowedEnvironments"].insert(0, "dev")
            path.write_text(json.dumps(mutated), encoding="utf-8")
            self.assert_reason(root, "WEB_API_DEV_DISABLED_INVALID")

    def test_release_and_runtime_share_the_deployment_owned_handoff_floor(self) -> None:
        profile = json.loads((
            PROJECT_ROOT
            / "deploy/base/ingestion-quality-runtime-1.0.0.json"
        ).read_text(encoding="utf-8"))
        floor = profile["targetHandoffAntiRollback"]
        maximum = 9007199254740991

        self.assertEqual(1, floor["minimum"])
        self.assertEqual(maximum, floor["maximum"])
        self.assertEqual(
            {
                "SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION",
                "DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION",
            },
            set(floor["inputConstraints"]),
        )
        for constraint in floor["inputConstraints"].values():
            self.assertEqual({"minimum": 1, "maximum": maximum}, constraint)
        self.assertEqual("deployment", floor["ownership"])
        self.assertEqual("monotonic-non-decreasing", floor["updateInvariant"])
        self.assertEqual(
            "release-and-runtime-use-same-floor",
            floor["crossSurfaceInvariant"],
        )
        self.assertEqual("startup-fail-closed", floor["belowFloor"])

        with self.fixture() as root:
            path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
            mutated = json.loads(path.read_text(encoding="utf-8"))
            mutated["targetHandoffAntiRollback"]["crossSurfaceInvariant"] = \
                "independent-floors"
            path.write_text(json.dumps(mutated), encoding="utf-8")
            self.assert_reason(
                root, "INGESTION_QUALITY_HANDOFF_ANTI_ROLLBACK_INVALID"
            )

        for environment_name in floor["inputConstraints"]:
            for field, invalid in (
                ("minimum", 0),
                ("maximum", maximum + 1),
            ):
                with self.subTest(environment=environment_name, field=field), \
                        self.fixture() as root:
                    path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
                    mutated = json.loads(path.read_text(encoding="utf-8"))
                    mutated["targetHandoffAntiRollback"]["inputConstraints"][
                        environment_name
                    ][field] = invalid
                    path.write_text(json.dumps(mutated), encoding="utf-8")
                    self.assert_reason(
                        root,
                        "INGESTION_QUALITY_HANDOFF_ANTI_ROLLBACK_INVALID",
                    )

    def test_ingestion_quality_database_logins_are_distinct_exclusive_members(self) -> None:
        profile = json.loads((
            PROJECT_ROOT
            / "deploy/base/ingestion-quality-runtime-1.0.0.json"
        ).read_text(encoding="utf-8"))
        gate = profile["databaseStartupGate"]

        self.assertEqual("180004", gate["requiredServerVersionNum"])
        self.assertEqual(
            "select current_setting('server_version_num')",
            gate["serverVersionQuery"],
        )
        self.assertEqual(
            "session_user=current_user=SPRING_DATASOURCE_USERNAME",
            gate["sessionIdentityInvariant"],
        )
        self.assertEqual("inherited-membership", gate["roleActivation"])
        self.assertFalse(gate["setRoleAllowed"])
        self.assertEqual(
            {"inherit": True, "set": False},
            gate["membershipGrantOptions"],
        )
        self.assertEqual(["web-api", "worker"], gate["distinctWorkloadLogins"])
        self.assertEqual(
            {
                "web-api": {
                    "requiredGroupRole": "scholarsense_ingestion_quality_online",
                    "forbiddenGroupRoles": [
                        "scholarsense_ingestion_quality_relay"
                    ],
                },
                "worker": {
                    "requiredGroupRole": "scholarsense_ingestion_quality_relay",
                    "forbiddenGroupRoles": [
                        "scholarsense_ingestion_quality_online"
                    ],
                },
            },
            gate["workloadRoleBindings"],
        )
        self.assertEqual(
            "exact-table-and-column-matrix",
            gate["effectivePrivilegeVerification"],
        )

    def test_ingestion_quality_database_role_downgrades_are_rejected(self) -> None:
        mutations = {
            "INGESTION_QUALITY_DATABASE_SERVER_VERSION_GATE_INVALID": (
                "requiredServerVersionNum", "180003"
            ),
            "INGESTION_QUALITY_DATABASE_SET_ROLE_BOUNDARY_INVALID": (
                "setRoleAllowed", True
            ),
            "INGESTION_QUALITY_DATABASE_PRIVILEGE_GATE_INVALID": (
                "effectivePrivilegeVerification", "table-only"
            ),
        }
        for reason, (key, value) in mutations.items():
            with self.subTest(reason=reason), self.fixture() as root:
                path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
                profile = json.loads(path.read_text(encoding="utf-8"))
                profile["databaseStartupGate"][key] = value
                path.write_text(json.dumps(profile), encoding="utf-8")
                self.assert_reason(root, reason)

        with self.fixture() as root:
            path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
            profile = json.loads(path.read_text(encoding="utf-8"))
            profile["databaseStartupGate"]["membershipGrantOptions"]["set"] = True
            path.write_text(json.dumps(profile), encoding="utf-8")
            self.assert_reason(
                root, "INGESTION_QUALITY_DATABASE_SET_ROLE_BOUNDARY_INVALID"
            )

    def test_derived_subject_objects_reuse_frozen_source_owner_bindings(self) -> None:
        profile = json.loads((
            PROJECT_ROOT / "deploy/base/ingestion-quality-runtime-1.0.0.json"
        ).read_text(encoding="utf-8"))
        owners = profile["ownerBindings"]

        self.assertEqual(
            {"SOURCE", "DEPENDENCY", "SUBJECT_MAPPING_EXCEPTION", "JOB"},
            set(owners["objectClasses"]),
        )
        self.assertEqual(
            {
                "bindingLookupClass": "SOURCE",
                "bindingKey": "exception-source-id",
                "authorizationTokenDigest": "sha256(sourceId)",
                "scopeAnchors": ["owned-source"],
            },
            owners["derivedObjectBindings"]["SUBJECT_MAPPING_EXCEPTION"],
        )
        self.assertEqual(
            {
                "bindingLookupClass": "SOURCE",
                "bindingKey": "persisted-owner-source-id",
                "authorizationTokenDigest": "sha256(ownerSourceId)",
                "scopeAnchors": ["owned-source", "technical-object"],
            },
            owners["derivedObjectBindings"]["JOB"],
        )

        with self.fixture() as root:
            path = root / "deploy/base/ingestion-quality-runtime-1.0.0.json"
            mutated = json.loads(path.read_text(encoding="utf-8"))
            mutated["ownerBindings"]["derivedObjectBindings"]["JOB"][
                "bindingKey"
            ] = "job-id"
            path.write_text(json.dumps(mutated), encoding="utf-8")
            self.assert_reason(
                root, "INGESTION_QUALITY_DERIVED_OWNER_BINDINGS_INVALID"
            )

    def test_subject_runtime_requires_exact_database_and_kms_boundaries(self) -> None:
        profile = json.loads((
            PROJECT_ROOT / "deploy/base/subject-registry-runtime-1.0.0.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(
            "approved-school-kms-to-protected-mount",
            profile["identifierProtection"]["materialDelivery"],
        )
        self.assertTrue(
            profile["identifierProtection"][
                "runtimeEvidenceRequiredForProductionCompletion"
            ]
        )
        self.assertEqual(
            "exact-table-column-function-matrix",
            profile["databaseStartupGates"]["effectivePrivilegeVerification"],
        )

        with self.fixture() as root:
            path = root / "deploy/base/subject-registry-runtime-1.0.0.json"
            mutated = json.loads(path.read_text(encoding="utf-8"))
            mutated["identifierProtection"]["materialDelivery"] = "plain-file"
            path.write_text(json.dumps(mutated), encoding="utf-8")
            self.assert_reason(
                root, "SUBJECT_REGISTRY_PROTECTION_BINDING_INVALID"
            )

    def test_role_artifact_matches_maven_output_name(self) -> None:
        roles = json.loads((PROJECT_ROOT / "deploy/base/roles.json").read_text(encoding="utf-8"))
        pom = (PROJECT_ROOT / "backend/pom.xml").read_text(encoding="utf-8")
        self.assertIn("<version>0.1.0-SNAPSHOT</version>", pom)
        self.assertIn("<finalName>scholarsense-backend</finalName>", pom)
        self.assertIn("<project.build.outputTimestamp>", pom)
        expected = "backend/target/scholarsense-backend.jar"

        self.assertEqual(
            {expected},
            {definition["artifact"] for definition in roles["roles"].values()},
        )

    def test_openapi_version_conflict_exposes_required_context(self) -> None:
        value = json.loads(
            (PROJECT_ROOT / "contracts/openapi/envelope.openapi.json").read_text(encoding="utf-8")
        )
        envelope = value["components"]["schemas"]["ErrorEnvelope"]
        self.assertEqual("integer", envelope["properties"]["currentVersion"]["type"])
        self.assertEqual("string", envelope["properties"]["latestOperator"]["type"])
        self.assertEqual("date-time", envelope["properties"]["latestChangedAt"]["format"])
        conflict_rule = envelope["allOf"][0]
        self.assertEqual("_VERSION_CONFLICT$", conflict_rule["if"]["properties"]["code"]["pattern"])
        self.assertEqual(
            {"currentVersion", "latestOperator", "latestChangedAt"},
            set(conflict_rule["then"]["required"]),
        )

    def test_runtime_reference_patterns_are_guarded(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/config/runtime-config.schema.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["properties"]["SCHOLARSENSE_ACCOUNT_REF"]["pattern"] = "^account://.*$"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "RUNTIME_SCHEMA_REFERENCE_PATTERN_INVALID")

    def test_audit_runtime_examples_reject_stale_controlled_versions(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/config/examples/test.env.example"
            content = path.read_text(encoding="utf-8").replace(
                "audit-verifier-1-0-0", "audit-verifier-0-9-0"
            )
            path.write_text(content, encoding="utf-8")
            self.assert_reason(root, "CONFIG_EXAMPLE_AUDIT_REFERENCE_STALE")

    def test_external_uri_schema_forbids_fragments(self) -> None:
        value = json.loads(
            (PROJECT_ROOT / "contracts/config/runtime-config.schema.json").read_text(encoding="utf-8")
        )
        pattern = value["properties"]["SCHOLARSENSE_EXTERNAL_BASE_URI"]["pattern"]

        self.assertIsNone(re.fullmatch(pattern, "https://test.invalid/path#fragment"))

    def test_empty_example_reference_path_is_rejected(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/config/examples/dev.env.example"
            content = path.read_text(encoding="utf-8").replace(
                "account://dev/example-school", "account://dev/"
            )
            path.write_text(content, encoding="utf-8")
            self.assert_reason(root, "CONFIG_EXAMPLE_REFERENCE_INVALID")

    def test_example_namespace_requires_complete_kebab_case(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/config/examples/test.env.example"
            content = path.read_text(encoding="utf-8").replace(
                "SCHOLARSENSE_STORAGE_NAMESPACE=scholarsense-test",
                "SCHOLARSENSE_STORAGE_NAMESPACE=BAD_namespace-test",
            )
            path.write_text(content, encoding="utf-8")
            self.assert_reason(root, "CONFIG_EXAMPLE_NAMESPACE_INVALID")

    def test_web_probe_paths_are_guarded(self) -> None:
        with self.fixture() as root:
            path = root / "deploy/base/roles.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["roles"]["web-api"]["probe"]["livenessPath"] = "/health"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "WEB_API_PROBE_INVALID")

    def test_role_artifact_must_match_maven_output(self) -> None:
        with self.fixture() as root:
            path = root / "deploy/base/roles.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            for definition in value["roles"].values():
                definition["artifact"] = "backend/target/wrong.jar"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "ROLE_ARTIFACT_MISMATCH")

    def test_role_artifact_respects_maven_final_name(self) -> None:
        with self.fixture() as root:
            path = root / "backend/pom.xml"
            content = path.read_text(encoding="utf-8").replace(
                "<build>", "<build>\n    <finalName>custom-runtime</finalName>", 1
            )
            path.write_text(content, encoding="utf-8")

            self.assert_reason(root, "ROLE_ARTIFACT_MISMATCH")

    def test_old_snapshot_path_and_one_role_only_changes_are_rejected(self) -> None:
        old_path = "backend/target/scholarsense-backend-0.1.0-SNAPSHOT.jar"
        with self.fixture() as root:
            roles_path = root / "deploy/base/roles.json"
            roles = json.loads(roles_path.read_text(encoding="utf-8"))
            for definition in roles["roles"].values():
                definition["artifact"] = old_path
            roles_path.write_text(json.dumps(roles), encoding="utf-8")
            self.assert_reason(root, "ROLE_ARTIFACT_MISMATCH")
        with self.fixture() as root:
            roles_path = root / "deploy/base/roles.json"
            roles = json.loads(roles_path.read_text(encoding="utf-8"))
            roles["roles"]["worker"]["artifact"] = old_path
            roles_path.write_text(json.dumps(roles), encoding="utf-8")
            self.assert_reason(root, "ROLE_ARTIFACT_MISMATCH")

    def test_event_property_semantics_are_guarded(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/events/envelope.schema.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["properties"]["id"]["type"] = "integer"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "EVENT_ENVELOPE_PROPERTY_INVALID")

    def test_openapi_version_conflict_rule_is_guarded(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/openapi/envelope.openapi.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["components"]["schemas"]["ErrorEnvelope"].pop("allOf")
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "OPENAPI_VERSION_CONFLICT_CONTEXT_INVALID")

    def test_field_error_child_types_are_guarded(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/openapi/envelope.openapi.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["components"]["schemas"]["FieldError"]["properties"]["field"]["type"] = "integer"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "OPENAPI_FIELD_ERROR_INVALID")

    def assert_reason(self, root: Path, reason: str) -> None:
        actual = validate(root)
        self.assertTrue(any(value.startswith(reason) for value in actual), f"expected {reason}, got {actual}")

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in ("contracts", "deploy", "frontend"):
            shutil.copytree(PROJECT_ROOT / relative, root / relative)
        (root / "backend").mkdir()
        shutil.copy2(PROJECT_ROOT / "backend/pom.xml", root / "backend/pom.xml")

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
