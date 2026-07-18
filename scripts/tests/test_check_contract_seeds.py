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

    def test_business_openapi_path_is_rejected(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/openapi/envelope.openapi.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["paths"] = {"/api/v1/clues": {}}
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "OPENAPI_BUSINESS_PATHS_PREMATURE")

    def test_business_event_contract_is_rejected(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/events/envelope.schema.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["$defs"] = {"ClueCreated": {"type": "object"}}
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "EVENT_BUSINESS_CONTRACT_PREMATURE")

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

    def test_role_artifact_matches_maven_output_name(self) -> None:
        roles = json.loads((PROJECT_ROOT / "deploy/base/roles.json").read_text(encoding="utf-8"))
        pom = (PROJECT_ROOT / "backend/pom.xml").read_text(encoding="utf-8")
        artifact_id = re.search(r"<artifactId>(scholarsense-backend)</artifactId>", pom).group(1)
        version = re.search(r"<version>(0\.1\.0-SNAPSHOT)</version>", pom).group(1)
        expected = f"backend/target/{artifact_id}-{version}.jar"

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
