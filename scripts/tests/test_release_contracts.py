from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_json import (  # noqa: E402
    canonical_bytes,
    canonical_sha256,
    evidence_graph_issues,
    load_json,
    parse_json_bytes,
    release_document_issues,
    schema_definition_issues,
    schema_issues,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
RELEASE_CONTRACTS = PROJECT_ROOT / "contracts/release"


class ReleaseCanonicalContractTest(unittest.TestCase):
    def test_python_node_and_verifier_match_controlled_vectors_byte_for_byte(self) -> None:
        vectors = load_json(RELEASE_CONTRACTS / "canonical-json-test-vectors-1.0.0.json")
        for vector in vectors["vectors"]:
            with self.subTest(vector=vector["id"]):
                expected = vector["canonicalUtf8"].encode("utf-8")
                self.assertEqual(expected, canonical_bytes(vector["value"]))
                self.assertEqual(vector["sha256"], canonical_sha256(vector["value"]))
                result = subprocess.run(
                    ["node", str(PROJECT_ROOT / "release/canonical-json.mjs")],
                    input=json.dumps(vector["value"], ensure_ascii=False),
                    text=True,
                    encoding="utf-8",
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(vector["canonicalUtf8"], result.stdout)

    def test_parser_rejects_ambiguous_or_non_profile_json(self) -> None:
        cases = {
            "duplicate": b'{"a":1,"a":2}',
            "bom": b'\xef\xbb\xbf{"a":1}',
            "float": b'{"a":1.0}',
            "negative zero": b'{"a":-0}',
            "nan": b'{"a":NaN}',
            "infinity": b'{"a":Infinity}',
            "unsafe integer": b'{"a":9007199254740992}',
            "lone surrogate": b'{"a":"\\ud800"}',
            "invalid utf8": b'{"a":"\xff"}',
        }
        for label, payload in cases.items():
            with self.subTest(label=label), self.assertRaises(ValueError):
                parse_json_bytes(payload)

    def test_canonicalizer_rejects_invalid_runtime_values(self) -> None:
        for value in ({"number": 1.5}, {"number": 2**53}, {"text": "\udfff"}):
            with self.subTest(value=repr(value)), self.assertRaises(ValueError):
                canonical_bytes(value)

    def test_node_parser_rejects_raw_non_profile_json_before_canonicalization(self) -> None:
        for payload in ('{"a":1,"a":2}', '{"a":1.0}', '{"a":1e2}', '{"a":-0}'):
            with self.subTest(payload=payload):
                result = subprocess.run(
                    ["node", str(PROJECT_ROOT / "release/canonical-json.mjs")],
                    input=payload,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                self.assertNotEqual(0, result.returncode)


class ReleaseSchemaContractTest(unittest.TestCase):
    def test_all_release_schemas_use_the_controlled_fail_closed_subset(self) -> None:
        schemas = sorted(RELEASE_CONTRACTS.glob("*.schema.json"))
        self.assertGreaterEqual(len(schemas), 13)
        for path in schemas:
            with self.subTest(schema=path.name):
                schema = load_json(path)
                self.assertEqual([], schema_definition_issues(schema))

    def test_subset_rejects_unknown_keyword_format_remote_ref_and_generic_sha(self) -> None:
        cases = (
            {"type": "string", "minContains": 1},
            {"type": "string", "format": "email"},
            {"$ref": "https://attacker.example/schema.json"},
            {"type": "object", "properties": {"sha": {"type": "string"}}},
        )
        for schema in cases:
            with self.subTest(schema=schema):
                self.assertTrue(schema_definition_issues(schema))

    def test_schema_fixtures_accept_valid_and_reject_negative_examples(self) -> None:
        index = load_json(RELEASE_CONTRACTS / "fixtures/index.json")
        self.assertGreaterEqual(len(index["contracts"]), 13)
        for contract in index["contracts"]:
            schema = load_json(RELEASE_CONTRACTS / contract["schema"])
            valid = load_json(RELEASE_CONTRACTS / contract["valid"])
            invalid = load_json(RELEASE_CONTRACTS / contract["invalid"])
            with self.subTest(contract=contract["id"], fixture="valid"):
                self.assertEqual([], schema_issues(valid, schema))
            with self.subTest(contract=contract["id"], fixture="invalid"):
                self.assertTrue(schema_issues(invalid, schema))

    def test_date_time_format_and_additional_properties_are_enforced(self) -> None:
        schema = {
            "type": "object",
            "properties": {"at": {"type": "string", "format": "date-time"}},
            "required": ["at"],
            "additionalProperties": False,
        }
        self.assertEqual([], schema_issues({"at": "2026-07-19T05:32:52+08:00"}, schema))
        self.assertTrue(schema_issues({"at": "yesterday"}, schema))
        self.assertTrue(schema_issues({"at": "2026-07-19T05:32:52Z", "extra": True}, schema))


class ReleaseSemanticContractTest(unittest.TestCase):
    def test_evidence_graph_rejects_self_reference_cycles_order_and_version_rebind(self) -> None:
        valid = {
            "subjectDigest": "a" * 64,
            "evidence": [
                {"id": "artifact", "sha256": "b" * 64, "dependsOn": []},
                {"id": "signature", "sha256": "c" * 64, "dependsOn": ["artifact"]},
            ],
            "versionBindings": [{"version": "1.0.0", "manifestSha256": "d" * 64}],
        }
        self.assertEqual([], evidence_graph_issues(valid))
        cases = []
        self_reference = copy.deepcopy(valid)
        self_reference["evidence"][0]["sha256"] = valid["subjectDigest"]
        cases.append(self_reference)
        cycle = copy.deepcopy(valid)
        cycle["evidence"][0]["dependsOn"] = ["signature"]
        cases.append(cycle)
        wrong_order = copy.deepcopy(valid)
        wrong_order["evidence"].reverse()
        cases.append(wrong_order)
        rebound = copy.deepcopy(valid)
        rebound["versionBindings"].append({"version": "1.0.0", "manifestSha256": "e" * 64})
        cases.append(rebound)
        for candidate in cases:
            with self.subTest(candidate=candidate):
                self.assertTrue(evidence_graph_issues(candidate))

    def test_release_documents_reject_placeholders_local_paths_and_identity_confusion(self) -> None:
        valid = {
            "actionCommitOid": "a" * 40,
            "binarySha256": "b" * 64,
            "ociDigest": "sha256:" + "c" * 64,
            "evidenceUri": "https://github.com/keliihall/ScholarSense-bmad-method/actions/runs/1",
        }
        self.assertEqual([], release_document_issues(valid))
        cases = (
            {**valid, "evidenceUri": "TBD"},
            {**valid, "evidenceUri": "/Users/example/release.json"},
            {**valid, "actionCommitOid": "v4"},
            {**valid, "binarySha256": "sha256:" + "b" * 64},
            {**valid, "ociDigest": "latest"},
            {**valid, "sha": "d" * 64},
        )
        for candidate in cases:
            with self.subTest(candidate=candidate):
                self.assertTrue(release_document_issues(candidate))

    def test_invalid_fixture_execution_does_not_mutate_controlled_contracts(self) -> None:
        before = canonical_sha256(load_json(RELEASE_CONTRACTS / "canonical-json-profile-1.0.0.json"))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "negative.json"
            path.write_bytes(b'{"duplicate":1,"duplicate":2}')
            with self.assertRaises(ValueError):
                load_json(path)
        after = canonical_sha256(load_json(RELEASE_CONTRACTS / "canonical-json-profile-1.0.0.json"))
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
