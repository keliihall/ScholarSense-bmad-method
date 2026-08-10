from __future__ import annotations

import contextlib
import hashlib
import importlib
import inspect
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Callable
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1]
PROJECT_ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = SCRIPTS / "check_story_2_3_task0_readiness.py"
VERIFY_CORE = SCRIPTS / "verify_core.sh"
sys.path.insert(0, str(SCRIPTS))

EXPECTED_CHILD_CHECKERS = (
    ("data-catalog", "check_data_catalog_contracts"),
    ("field-projection", "check_field_projection_contracts"),
    ("audit-retention", "check_audit_retention_contracts"),
    ("ingestion-batch", "check_ingestion_batch_contracts"),
)
EXPECTED_SOURCE_IDS = frozenset({
    "SRC-P0-ACCOMMODATION-001",
    "SRC-P0-CALENDAR-001",
    "SRC-P0-CAMPUS-ACCESS-001",
    "SRC-P0-CARD-001",
    "SRC-P0-DEVICE-001",
    "SRC-P0-DORM-ACCESS-001",
    "SRC-P0-LEAVE-001",
    "SRC-P0-RESPONSIBILITY-001",
    "SRC-P0-STUDENT-001",
    "SRC-P0-TIMETABLE-001",
    "SRC-P1-ACADEMIC-001",
    "SRC-P1-AID-001",
    "SRC-P1-CARE-LIST-001",
    "SRC-P1-NETWORK-001",
    "SRC-P1-OFFCAMPUS-001",
    "SRC-P1-PSYCH-DEID-001",
    "SRC-P1-WORK-VISIT-001",
})

INGESTION_LOCK = Path(
    "contracts/ingestion-quality/batch-quality/"
    "executable-quality-contract-lock-1.0.0.json"
)
METRIC_VECTORS = Path(
    "contracts/ingestion-quality/batch-quality/fixtures/valid/"
    "quality-metric-vectors-1.0.0.json"
)
FIELD_LOCK = Path(
    "contracts/field-projection/field-projection-contract-lock-1.1.0.json"
)
AUDIT_LOCK = Path(
    "contracts/audit-retention/audit-retention-contract-lock-1.0.0.json"
)
POLICY_CANONICAL_DIGEST = (
    "sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8"
)
RETENTION_CANONICAL_DIGEST = (
    "sha256:1770fc6fa8e58b6853dbdca7dde3dec9aac0da8a89f7840c86ce252f00ebcae0"
)
FIELD_LOCK_RAW_DIGEST = (
    "sha256:91270f357115d3e7f9273723b677143e6d3adbc5a9a0e3f249279c64ebbd5a11"
)
AUDIT_LOCK_RAW_DIGEST = (
    "sha256:321755d18d6de7ee8eef436bd070a5d5f6ad94773bbbe686ba89b0cf21926c8b"
)

# A readiness checker may elect to pin Story 2.3's release predecessors.  If it
# does, the guard is deliberately closed to the already controlled v1-v4
# manifest/index schemas and the existing v1/v2 fixtures; it must not sweep the
# release directory or invent a broader predecessor denominator.
EXPECTED_RELEASE_PREDECESSOR_RAW_GUARDS = {
    "contracts/release/release-manifest.schema.json":
        "9ee461f8772441366396cb181dd358d3df848aa834837ba19ff76d484a5b1f6e",
    "contracts/release/evidence-index.schema.json":
        "c9ad274343c42f3b73ed6f6ee62a8adfdb79c490f60fd6b4c95456b094b6629a",
    "contracts/release/fixtures/valid/release-manifest.json":
        "717acd02b76737c709002e68804b0b8d98e5ca844fc0f05ae7c04ae9d4676f10",
    "contracts/release/fixtures/valid/evidence-index.json":
        "ac45ac4b53f3e5bf663254e2320a83bf60edc9896b0a2f84ea6aa116d1724e8d",
    "contracts/release/release-manifest-2.schema.json":
        "a012807699695cab091808fc5fbb1f4ebe7db56aa8ce8789cec79f4f68c50aac",
    "contracts/release/evidence-index-2.schema.json":
        "959134c8e3d481131d7c76ea403b5ae6304bf8f8eb58a77f8a79a83910f7dfde",
    "contracts/release/fixtures/valid/release-manifest-2.json":
        "fef2472545834b7417a1a0e1d97130318deed3672d8e68c18bf1263365a6d2f7",
    "contracts/release/fixtures/valid/evidence-index-2.json":
        "be396b5e339ced0651126f906fc1dcd0100114a59dab6cb33e59a3992ab3fea7",
    "contracts/release/release-manifest-3.schema.json":
        "f5506bb2b535c404ab1f029fea1329d614a23a8ea9bd98a4d876707132428625",
    "contracts/release/evidence-index-3.schema.json":
        "401117e68de1267d0bc677cce2114a2820a7476d39430fda8df93ea2e7b6ae9c",
    "contracts/release/release-manifest-4.schema.json":
        "6483e1421c50363beca5aad5a469ca2734daedf2c84636c75fc143bd834cb094",
    "contracts/release/evidence-index-4.schema.json":
        "6bddb59a22d4408139d562793b566d2e5098eaef2f3bbce7b41c3acfd3927d7e",
}

try:
    readiness = importlib.import_module("check_story_2_3_task0_readiness")
    READINESS_IMPORT_ERROR: BaseException | None = None
except BaseException as error:  # fail closed for syntax/import-time failures too
    readiness = None
    READINESS_IMPORT_ERROR = error


def _sha256(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, document: dict) -> None:
    path.write_text(
        json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


class Story23Task0ReadinessAvailabilityTest(unittest.TestCase):
    def test_checker_file_import_and_validate_entrypoint_are_required(self) -> None:
        self.assertTrue(CHECKER_PATH.is_file(), f"missing readiness checker: {CHECKER_PATH}")
        self.assertIsNone(
            READINESS_IMPORT_ERROR,
            f"readiness checker is not importable: {READINESS_IMPORT_ERROR!r}",
        )
        self.assertTrue(callable(getattr(readiness, "validate", None)))


@unittest.skipIf(readiness is None, "readiness checker is intentionally RED/absent")
class Story23Task0ReadinessContractTest(unittest.TestCase):
    def _green_validators(
        self,
        calls: list[str] | None = None,
    ) -> dict[str, Callable[[Path], list[str]]]:
        validators: dict[str, Callable[[Path], list[str]]] = {}
        for checker_id, _module_name in EXPECTED_CHILD_CHECKERS:
            def validate_child(root: Path, current: str = checker_id) -> list[str]:
                self.assertEqual(PROJECT_ROOT.resolve(), root.resolve())
                if calls is not None:
                    calls.append(current)
                return []

            validators[checker_id] = validate_child
        return validators

    def _copy_contract_root(self) -> tempfile.TemporaryDirectory[str]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(PROJECT_ROOT / "contracts", root / "contracts")
        for relative in (
            Path("_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md"),
            Path("_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-08.md"),
        ):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(PROJECT_ROOT / relative, destination)
        return temporary

    @staticmethod
    def _all_green_for(root: Path) -> dict[str, Callable[[Path], list[str]]]:
        return {
            checker_id: (lambda _root: [])
            for checker_id, _module_name in EXPECTED_CHILD_CHECKERS
        }

    def assert_has_prefix(self, issues: list[str], prefix: str) -> None:
        self.assertTrue(
            any(issue.startswith(prefix) for issue in issues),
            f"expected {prefix!r}, observed {issues!r}",
        )

    def test_exact_four_child_checker_ids_and_modules_are_frozen(self) -> None:
        self.assertEqual(EXPECTED_CHILD_CHECKERS, readiness.CHILD_CHECKERS)

    def test_real_baseline_composes_all_four_child_validate_functions_and_is_green(self) -> None:
        self.assertEqual([], readiness.validate(PROJECT_ROOT))

    def test_injected_children_are_each_called_once_with_the_project_root(self) -> None:
        calls: list[str] = []
        self.assertEqual(
            [],
            readiness.validate(
                PROJECT_ROOT,
                child_validators=self._green_validators(calls),
            ),
        )
        self.assertEqual(
            [checker_id for checker_id, _module in EXPECTED_CHILD_CHECKERS],
            calls,
        )

    def test_each_child_issue_is_fail_closed_with_a_stable_checker_prefix(self) -> None:
        for checker_id, _module_name in EXPECTED_CHILD_CHECKERS:
            with self.subTest(checker_id=checker_id):
                validators = self._green_validators()
                validators[checker_id] = lambda _root: ["SENTINEL_ISSUE"]
                issues = readiness.validate(PROJECT_ROOT, child_validators=validators)
                self.assertIn(
                    f"TASK0_READINESS_CHILD_ISSUE[{checker_id}]: SENTINEL_ISSUE",
                    issues,
                )

    def test_each_child_exception_is_fail_closed_without_leaking_exception_text(self) -> None:
        for checker_id, _module_name in EXPECTED_CHILD_CHECKERS:
            with self.subTest(checker_id=checker_id):
                validators = self._green_validators()

                def explode(_root: Path) -> list[str]:
                    raise RuntimeError("studentRef=forbidden-secret")

                validators[checker_id] = explode
                issues = readiness.validate(PROJECT_ROOT, child_validators=validators)
                self.assertIn(
                    f"TASK0_READINESS_CHILD_EXCEPTION[{checker_id}]: RuntimeError",
                    issues,
                )
                self.assertFalse(any("forbidden-secret" in issue for issue in issues))

    def test_missing_or_non_callable_child_validate_is_fail_closed(self) -> None:
        checker_id = EXPECTED_CHILD_CHECKERS[0][0]
        for candidate in (None, "not-callable"):
            with self.subTest(candidate=candidate):
                validators = self._green_validators()
                validators[checker_id] = candidate  # type: ignore[assignment]
                issues = readiness.validate(PROJECT_ROOT, child_validators=validators)
                self.assertIn(
                    f"TASK0_READINESS_CHILD_VALIDATE_MISSING[{checker_id}]",
                    issues,
                )

    def test_invalid_child_result_shapes_are_fail_closed(self) -> None:
        checker_id = EXPECTED_CHILD_CHECKERS[0][0]
        for candidate in (None, (), [""], [1]):
            with self.subTest(candidate=candidate):
                validators = self._green_validators()
                validators[checker_id] = lambda _root, value=candidate: value
                self.assertIn(
                    f"TASK0_READINESS_CHILD_RESULT_INVALID[{checker_id}]",
                    readiness.validate(
                        PROJECT_ROOT,
                        child_validators=validators,
                    ),
                )

    def test_child_stdout_is_not_an_oracle_and_business_semantics_are_not_copied(self) -> None:
        validators = self._green_validators()

        def noisy_green(_root: Path) -> list[str]:
            print("FAKE_CHILD_FAIL: stdout is not the API")
            return []

        validators[EXPECTED_CHILD_CHECKERS[0][0]] = noisy_green
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(
                [],
                readiness.validate(PROJECT_ROOT, child_validators=validators),
            )

        source = inspect.getsource(readiness)
        for forbidden in (
            "subprocess",
            "capture_output",
            "redirect_stdout",
            "_catalog_issues(",
            "_binding_issues(",
            "metric_vector_issues(",
            "retention_policy_issues(",
            "schema_issues(",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, source)

    def test_exact_task0_handoff_baseline_is_frozen(self) -> None:
        lock = _load(PROJECT_ROOT / INGESTION_LOCK)
        vectors = _load(PROJECT_ROOT / METRIC_VECTORS)
        digests = lock["digests"]
        self.assertEqual(48, len(digests))
        self.assertNotIn(INGESTION_LOCK.as_posix(), digests)
        self.assertEqual(POLICY_CANONICAL_DIGEST, lock["policyCanonicalDigest"])
        self.assertEqual(RETENTION_CANONICAL_DIGEST, lock["retentionCanonicalDigest"])
        self.assertEqual(FIELD_LOCK_RAW_DIGEST, _sha256(PROJECT_ROOT / FIELD_LOCK))
        self.assertEqual(AUDIT_LOCK_RAW_DIGEST, _sha256(PROJECT_ROOT / AUDIT_LOCK))
        self.assertEqual(
            EXPECTED_SOURCE_IDS,
            frozenset(
                case["sourceId"]
                for case in vectors["cases"]
                if isinstance(case.get("sourceId"), str)
            ),
        )
        self.assertEqual(
            [],
            readiness.validate(
                PROJECT_ROOT,
                child_validators=self._green_validators(),
            ),
        )

    def test_handoff_mutations_fail_closed_with_stable_codes(self) -> None:
        mutations: list[tuple[str, Callable[[Path], None]]] = []

        def wrong_entry_count(root: Path) -> None:
            lock = _load(root / INGESTION_LOCK)
            lock["digests"].pop(next(iter(lock["digests"])))
            _write(root / INGESTION_LOCK, lock)

        mutations.append(("TASK0_READINESS_LOCK_ENTRY_COUNT_INVALID", wrong_entry_count))

        def self_reference(root: Path) -> None:
            lock = _load(root / INGESTION_LOCK)
            lock["digests"][INGESTION_LOCK.as_posix()] = "sha256:" + "0" * 64
            _write(root / INGESTION_LOCK, lock)

        mutations.append(("TASK0_READINESS_LOCK_SELF_REFERENCE", self_reference))

        def field_lock_drift(root: Path) -> None:
            path = root / FIELD_LOCK
            path.write_bytes(path.read_bytes() + b" ")

        mutations.append(("TASK0_READINESS_FIELD_LOCK_RAW_MISMATCH", field_lock_drift))

        def audit_lock_drift(root: Path) -> None:
            path = root / AUDIT_LOCK
            path.write_bytes(path.read_bytes() + b" ")

        mutations.append(("TASK0_READINESS_AUDIT_LOCK_RAW_MISMATCH", audit_lock_drift))

        def policy_digest_drift(root: Path) -> None:
            lock = _load(root / INGESTION_LOCK)
            lock["policyCanonicalDigest"] = "sha256:" + "0" * 64
            _write(root / INGESTION_LOCK, lock)

        mutations.append(
            ("TASK0_READINESS_POLICY_CANONICAL_DIGEST_INVALID", policy_digest_drift)
        )

        def retention_digest_drift(root: Path) -> None:
            lock = _load(root / INGESTION_LOCK)
            lock["retentionCanonicalDigest"] = "sha256:" + "0" * 64
            _write(root / INGESTION_LOCK, lock)

        mutations.append(
            ("TASK0_READINESS_RETENTION_CANONICAL_DIGEST_INVALID", retention_digest_drift)
        )

        def source_set_drift(root: Path) -> None:
            vectors = _load(root / METRIC_VECTORS)
            target = next(
                case
                for case in vectors["cases"]
                if case.get("sourceId") == "SRC-P0-ACCOMMODATION-001"
            )
            target["sourceId"] = "SRC-UNAPPROVED-001"
            _write(root / METRIC_VECTORS, vectors)

        mutations.append(("TASK0_READINESS_VECTOR_SOURCE_SET_INVALID", source_set_drift))

        for expected_prefix, mutate in mutations:
            with self.subTest(expected_prefix=expected_prefix):
                temporary = self._copy_contract_root()
                try:
                    root = Path(temporary.name)
                    mutate(root)
                    issues = readiness.validate(
                        root,
                        child_validators=self._all_green_for(root),
                    )
                    self.assert_has_prefix(issues, expected_prefix)
                finally:
                    temporary.cleanup()

    def test_release_predecessor_guard_is_the_exact_approved_raw_denominator(self) -> None:
        guards = getattr(readiness, "RELEASE_PREDECESSOR_RAW_GUARDS", {})
        self.assertEqual(EXPECTED_RELEASE_PREDECESSOR_RAW_GUARDS, guards)

    def test_release_predecessor_raw_byte_mutation_is_rejected(self) -> None:
        guarded = getattr(readiness, "RELEASE_PREDECESSOR_RAW_GUARDS", {})
        self.assertTrue(guarded)
        relative = next(iter(guarded))
        temporary = self._copy_contract_root()
        try:
            root = Path(temporary.name)
            path = root / relative
            path.write_bytes(path.read_bytes() + b" ")
            self.assertIn(
                f"TASK0_READINESS_RELEASE_BASELINE_MISMATCH: {relative}",
                readiness.validate(
                    root,
                    child_validators=self._all_green_for(root),
                ),
            )
        finally:
            temporary.cleanup()

    def test_verify_core_runs_the_readiness_gate_after_its_children(self) -> None:
        script = VERIFY_CORE.read_text(encoding="utf-8")
        readiness_command = "scripts/check_story_2_3_task0_readiness.py ."
        self.assertEqual(1, script.count(readiness_command))
        readiness_position = script.index(readiness_command)
        for child_command in (
            "scripts/check_data_catalog_contracts.py .",
            "scripts/check_field_projection_contracts.py .",
            "scripts/check_ingestion_batch_contracts.py .",
            "scripts/check_audit_retention_contracts.py .",
        ):
            self.assertLess(script.index(child_command), readiness_position)

    def test_cli_exit_code_is_bound_to_validate_result(self) -> None:
        with mock.patch.object(readiness, "validate", return_value=[]):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, readiness.main(["readiness"]))
            self.assertIn("STORY_2_3_TASK_0_READINESS_OK", output.getvalue())

        with mock.patch.object(
            readiness,
            "validate",
            return_value=["SENTINEL_READINESS_FAILURE"],
        ):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, readiness.main(["readiness"]))
            self.assertIn("SENTINEL_READINESS_FAILURE", output.getvalue())


if __name__ == "__main__":
    unittest.main()
