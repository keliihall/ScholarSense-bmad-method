from __future__ import annotations

import copy
import base64
import hashlib
import json
import sys
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))

from promotion import (  # noqa: E402
    CommandResult,
    GitRefLedger,
    InMemoryPromotionAdapter,
    OrasDigestStore,
    PromotionError,
    PromotionEvidence,
    PromotionRequest,
)


NOW = datetime(2026, 7, 19, 1, 0, tzinfo=timezone.utc)


def evidence(seed: str = "a") -> PromotionEvidence:
    artifact_digest = "sha256:" + seed * 64
    return PromotionEvidence(
        artifact_uri=f"ghcr.io/keliihall/scholarsense-release-candidate@{artifact_digest}",
        sbom_uri="ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "b" * 64,
        attestation_uri="ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "c" * 64,
        web_uri="ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "d" * 64,
        manifest_uri="ghcr.io/keliihall/scholarsense-release-manifest@sha256:" + "e" * 64,
        signature_uri="ghcr.io/keliihall/scholarsense-release-evidence@sha256:" + "f" * 64,
        index_uri="ghcr.io/keliihall/scholarsense-release-manifest@sha256:" + "1" * 64,
        artifact_oci_digest=artifact_digest,
        manifest_sha256="2" * 64,
        evidence_index_sha256="3" * 64,
    )


def request(
    selected: PromotionEvidence | None = None,
    *,
    target: str = "stage",
    rollback_of: str | None = None,
) -> PromotionRequest:
    return PromotionRequest(
        release_version="1.0.0",
        from_environment="candidate" if target == "stage" else "stage",
        target_environment=target,
        evidence=selected or evidence(),
        actor="release-job",
        approver=f"protected-environment:{target}",
        run_id="29670000000",
        run_attempt=1,
        rollback_of_ledger_uri=rollback_of,
    )


class PromotionContractTest(unittest.TestCase):
    def adapter(self, selected: PromotionEvidence | None = None) -> InMemoryPromotionAdapter:
        chosen = selected or evidence()
        adapter = InMemoryPromotionAdapter(
            verifier_identity="protected-release-independent-verifier",
            receipt_ttl=timedelta(minutes=15),
        )
        adapter.seed_candidate(chosen.artifact_uri)
        adapter.seed_evidence(chosen)
        return adapter

    def test_digest_only_first_write_and_same_material_replay_are_idempotent(self) -> None:
        selected = evidence()
        adapter = self.adapter(selected)

        first = adapter.promote(request(selected), NOW)
        replay = adapter.promote(request(selected), NOW + timedelta(minutes=1))

        self.assertEqual("promoted", first.result)
        self.assertEqual("replayed", replay.result)
        self.assertEqual(first.ledger_uri, replay.ledger_uri)
        self.assertEqual(1, adapter.ledger_write_count)
        self.assertEqual(selected.artifact_oci_digest, first.record["artifactOciDigest"])
        self.assertEqual(
            "ghcr.io/keliihall/scholarsense-release-stage@" + selected.artifact_oci_digest,
            first.record["targetArtifactUri"],
        )
        self.assertNotIn(":latest", str(first.record))

    def test_same_key_different_digest_is_stable_conflict_without_partial_state(self) -> None:
        selected = evidence()
        adapter = self.adapter(selected)
        adapter.promote(request(selected), NOW)
        other = evidence("9")
        adapter.seed_candidate(other.artifact_uri)
        adapter.seed_evidence(other)

        with self.assertRaisesRegex(PromotionError, "PROMOTION_IDEMPOTENCY_CONFLICT"):
            adapter.promote(request(other), NOW + timedelta(minutes=1))

        self.assertEqual(1, adapter.ledger_write_count)
        self.assertNotIn(adapter.target_tag_uri(request(other)), adapter.target_tags)
        self.assertEqual(selected.artifact_oci_digest, adapter.reconcile("1.0.0", "stage", NOW)["artifactOciDigest"])

    def test_concurrent_same_key_has_one_winner_and_only_replays(self) -> None:
        adapter = self.adapter()
        barrier = threading.Barrier(8)
        outcomes: list[str] = []
        failures: list[BaseException] = []

        def worker() -> None:
            try:
                barrier.wait()
                outcomes.append(adapter.promote(request(), NOW).result)
            except BaseException as error:  # pragma: no cover - assertion reports the captured error
                failures.append(error)

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual([], failures)
        self.assertEqual(1, outcomes.count("promoted"))
        self.assertEqual(7, outcomes.count("replayed"))
        self.assertEqual(1, adapter.ledger_write_count)

    def test_missing_expired_or_tampered_evidence_fails_before_store_write(self) -> None:
        cases = ("missing", "expired", "tampered")
        for case in cases:
            with self.subTest(case=case):
                adapter = self.adapter()
                if case == "missing":
                    adapter.remove_evidence("signature")
                elif case == "expired":
                    adapter.receipt_ttl = timedelta(seconds=-1)
                else:
                    adapter.tamper_evidence("manifest_sha256", "8" * 64)
                with self.assertRaises(PromotionError):
                    adapter.promote(request(), NOW)
                self.assertEqual({}, adapter.target_tags)
                self.assertEqual({}, adapter.ledger_records)

    def test_failure_after_copy_rolls_back_tag_and_never_writes_ledger(self) -> None:
        adapter = self.adapter()
        adapter.fail_after_copy = True

        with self.assertRaisesRegex(PromotionError, "PROMOTION_INJECTED_FAILURE"):
            adapter.promote(request(), NOW)

        self.assertEqual({}, adapter.target_tags)
        self.assertEqual({}, adapter.ledger_records)

    def test_reconcile_blocks_target_tag_and_store_ledger_drift(self) -> None:
        adapter = self.adapter()
        outcome = adapter.promote(request(), NOW)
        tag_uri = outcome.record["targetTagUri"]
        adapter.target_tags[tag_uri] = "sha256:" + "7" * 64
        with self.assertRaisesRegex(PromotionError, "PROMOTION_TARGET_TAG_DRIFT"):
            adapter.reconcile("1.0.0", "stage", NOW)

        adapter.target_tags[tag_uri] = outcome.record["artifactOciDigest"]
        key = ("1.0.0", "stage")
        adapter.ledger_records[key] = {**copy.deepcopy(outcome.record), "targetArtifactUri": "ghcr.io/keliihall/scholarsense-release-stage@sha256:" + "6" * 64}
        with self.assertRaisesRegex(PromotionError, "PROMOTION_STORE_LEDGER_DRIFT"):
            adapter.reconcile("1.0.0", "stage", NOW)

    def test_rollback_reuses_historical_signed_digest_and_runs_current_gates(self) -> None:
        adapter = self.adapter()
        historical = adapter.promote(request(), NOW).record
        verification_calls = adapter.verification_count
        rollback = request(target="production", rollback_of=historical["ledgerUri"])

        outcome = adapter.rollback(rollback, historical, NOW + timedelta(minutes=2))

        self.assertEqual("promoted", outcome.result)
        self.assertEqual(historical["artifactOciDigest"], outcome.record["artifactOciDigest"])
        self.assertEqual(historical["manifestSha256"], outcome.record["manifestSha256"])
        self.assertEqual(historical["evidenceIndexSha256"], outcome.record["evidenceIndexSha256"])
        self.assertEqual(historical["ledgerUri"], outcome.record["rollbackOfLedgerUri"])
        self.assertEqual(verification_calls + 1, adapter.verification_count)
        self.assertEqual(0, adapter.build_count)

    def test_rollback_rejects_rebuild_or_material_substitution(self) -> None:
        adapter = self.adapter()
        historical = adapter.promote(request(), NOW).record
        substituted = request(evidence("9"), target="production", rollback_of=historical["ledgerUri"])
        adapter.seed_candidate(substituted.evidence.artifact_uri)
        adapter.seed_evidence(substituted.evidence)

        with self.assertRaisesRegex(PromotionError, "PROMOTION_ROLLBACK_MATERIAL_MISMATCH"):
            adapter.rollback(substituted, historical, NOW + timedelta(minutes=2))
        self.assertEqual(0, adapter.build_count)


class PromotionProductionSurfaceTest(unittest.TestCase):
    def test_real_adapter_and_entrypoint_are_present_and_digest_only(self) -> None:
        implementation = (PROJECT_ROOT / "release/promotion.py").read_text(encoding="utf-8")
        entrypoint = (PROJECT_ROOT / "scripts/promote-release.sh").read_text(encoding="utf-8")
        self.assertIn("class OrasDigestStore", implementation)
        self.assertIn("class GitRefLedger", implementation)
        self.assertIn("oras", implementation)
        self.assertIn("refs/tags/promotion-ledger/", implementation)
        self.assertIn("verify-release.sh", entrypoint)
        self.assertNotIn(":latest", implementation + entrypoint)

    def test_release_workflow_promotes_only_after_independent_verifier(self) -> None:
        workflow = (PROJECT_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        verifier = workflow.index("  independent-verifier:")
        promotion = workflow.index("  promotion:")
        self.assertLess(verifier, promotion)
        body = workflow[promotion:]
        self.assertIn("needs:", body)
        self.assertIn("independent-verifier", body)
        self.assertIn("environment: ${{ inputs.target_environment }}", body)
        self.assertIn("scripts/promote-release.sh", body)
        self.assertIn("GH_TOKEN: ${{ github.token }}", body)
        self.assertNotIn("id-token: write", body)

    def test_rollback_workflow_reuses_history_without_a_build_job(self) -> None:
        workflow = (PROJECT_ROOT / ".github/workflows/rollback.yml").read_text(encoding="utf-8")
        self.assertIn("environment: production", workflow)
        self.assertIn("scripts/rollback-release.sh", workflow)
        self.assertIn("GH_TOKEN: ${{ github.token }}", workflow)
        self.assertIn("scripts/verify-release.sh", (PROJECT_ROOT / "scripts/rollback-release.sh").read_text(encoding="utf-8"))
        self.assertNotIn("scripts/build-release.sh", workflow)
        self.assertNotIn("build-cas:", workflow)
        self.assertNotIn("id-token: write", workflow)


class _FakeOrasRunner:
    def __init__(self) -> None:
        self.descriptors: dict[str, str] = {}
        self.commands: list[list[str]] = []
        self.package_versions: list[dict] = []
        self.repo_tags_error: str | None = None

    def run(self, arguments, *, environment=None, stdin=None) -> CommandResult:
        command = list(arguments)
        self.commands.append(command)
        if command[:2] == ["gh", "api"] and "--method" not in command:
            return CommandResult(0, json.dumps([self.package_versions]), "")
        if command[:4] == ["gh", "api", "--method", "DELETE"]:
            version_id = int(command[-1].rsplit("/", 1)[1])
            version = next(item for item in self.package_versions if item["id"] == version_id)
            for tag in version["metadata"]["container"]["tags"]:
                suffix = f":{tag}"
                for uri in list(self.descriptors):
                    if uri.endswith(suffix):
                        self.descriptors.pop(uri, None)
            self.package_versions = [item for item in self.package_versions if item["id"] != version_id]
            return CommandResult(0, "", "")
        if command[1:3] == ["repo", "tags"]:
            if self.repo_tags_error is not None:
                return CommandResult(1, "", self.repo_tags_error)
            repository = command[3]
            tags = sorted(
                uri.removeprefix(f"{repository}:")
                for uri in self.descriptors
                if uri.startswith(f"{repository}:")
            )
            return CommandResult(0, "\n".join(tags), "")
        if command[1:3] == ["manifest", "fetch"]:
            digest = self.descriptors.get(command[3])
            return CommandResult(0, json.dumps({"digest": digest}), "") if digest else CommandResult(1, "", "missing")
        if command[1] == "copy":
            source, target = command[2:4]
            digest = source.rpartition("@")[2]
            if self.descriptors.get(source) != digest:
                return CommandResult(1, "", "missing source")
            self.descriptors[target] = digest
            repository = target.rsplit(":", 1)[0]
            self.descriptors[f"{repository}@{digest}"] = digest
            return CommandResult(0, "", "")
        if command[1:4] == ["manifest", "delete", "--force"]:
            self.descriptors.pop(command[4], None)
            return CommandResult(0, "", "")
        return CommandResult(1, "", "unexpected")


class _FakeGitHubRunner:
    def __init__(self) -> None:
        self.blobs: dict[str, str] = {}
        self.trees: dict[str, dict] = {}
        self.commits: dict[str, dict] = {}
        self.refs: dict[str, str] = {}
        self.counter = 0
        self.ref_read_error: str | None = None
        self.wrap_blob_content = False

    def oid(self, label: str) -> str:
        self.counter += 1
        return hashlib.sha1(f"{label}-{self.counter}".encode(), usedforsecurity=False).hexdigest()

    def run(self, arguments, *, environment=None, stdin=None) -> CommandResult:
        command = list(arguments)
        method = command[command.index("--method") + 1]
        endpoint = next(item for item in command if item.startswith("repos/")).split("/", 3)[3]
        payload = json.loads(stdin) if stdin is not None else None
        if method == "POST" and endpoint == "git/blobs":
            oid = self.oid("blob")
            self.blobs[oid] = payload["content"]
            return CommandResult(0, json.dumps({"sha": oid}), "")
        if method == "POST" and endpoint == "git/trees":
            oid = self.oid("tree")
            self.trees[oid] = payload
            return CommandResult(0, json.dumps({"sha": oid}), "")
        if method == "POST" and endpoint == "git/commits":
            oid = self.oid("commit")
            self.commits[oid] = {"tree": {"sha": payload["tree"]}}
            return CommandResult(0, json.dumps({"sha": oid}), "")
        if method == "POST" and endpoint == "git/refs":
            ref = payload["ref"].removeprefix("refs/")
            if ref in self.refs:
                return CommandResult(1, "", "Reference already exists")
            self.refs[ref] = payload["sha"]
            return CommandResult(0, json.dumps({"ref": payload["ref"]}), "")
        if method == "GET" and endpoint.startswith("git/ref/"):
            if self.ref_read_error is not None:
                return CommandResult(1, "", self.ref_read_error)
            ref = endpoint.removeprefix("git/ref/")
            oid = self.refs.get(ref)
            return CommandResult(0, json.dumps({"object": {"sha": oid}}), "") if oid else CommandResult(1, "", "missing")
        if method == "GET" and endpoint.startswith("git/commits/"):
            value = self.commits.get(endpoint.removeprefix("git/commits/"))
            return CommandResult(0, json.dumps(value), "") if value else CommandResult(1, "", "missing")
        if method == "GET" and endpoint.startswith("git/trees/"):
            value = self.trees.get(endpoint.removeprefix("git/trees/"))
            if value:
                return CommandResult(0, json.dumps(value), "")
            return CommandResult(1, "", "missing")
        if method == "GET" and endpoint.startswith("git/blobs/"):
            content = self.blobs.get(endpoint.removeprefix("git/blobs/"))
            if content and self.wrap_blob_content:
                content = "\n".join(content[index:index + 60] for index in range(0, len(content), 60)) + "\n"
            return CommandResult(0, json.dumps({"encoding": "base64", "content": content}), "") if content else CommandResult(1, "", "missing")
        return CommandResult(1, "", "unexpected")


class PromotionRealAdapterUnitTest(unittest.TestCase):
    def test_ghcr_prepare_treats_registry_name_unknown_as_an_absent_repository(self) -> None:
        runner = _FakeOrasRunner()
        runner.repo_tags_error = (
            "Error response from registry: name unknown: "
            "repository name not known to registry"
        )
        store = OrasDigestStore(Path("/controlled/oras"), runner)

        store.prepare("1.0.0", "ghcr.io/keliihall/scholarsense-release-stage")

        self.assertFalse(any(len(item) > 1 and item[1] == "copy" for item in runner.commands))

    def test_oras_adapter_copies_and_reads_back_only_the_selected_digest(self) -> None:
        runner = _FakeOrasRunner()
        selected = evidence()
        runner.descriptors[selected.artifact_uri] = selected.artifact_oci_digest
        store = OrasDigestStore(Path("/controlled/oras"), runner)
        tag = "ghcr.io/keliihall/scholarsense-release-stage:release-1.0.0-aaaaaaaaaaaaaaaa"

        copied = store.copy_by_digest(
            selected.artifact_uri,
            "ghcr.io/keliihall/scholarsense-release-stage",
            tag,
        )

        self.assertTrue(copied.tag_created)
        self.assertEqual(selected.artifact_oci_digest, store.tag_digest(tag))
        self.assertTrue(store.artifact_exists(copied.target_artifact_uri))
        copy_commands = [item for item in runner.commands if len(item) > 1 and item[1] == "copy"]
        self.assertEqual([["/controlled/oras", "copy", selected.artifact_uri, tag]], copy_commands)

    def test_oras_adapter_rejects_tag_collision_without_copying(self) -> None:
        runner = _FakeOrasRunner()
        selected = evidence()
        runner.descriptors[selected.artifact_uri] = selected.artifact_oci_digest
        tag = "ghcr.io/keliihall/scholarsense-release-stage:release-1.0.0-aaaaaaaaaaaaaaaa"
        runner.descriptors[tag] = "sha256:" + "9" * 64
        store = OrasDigestStore(Path("/controlled/oras"), runner)

        with self.assertRaisesRegex(PromotionError, "PROMOTION_TARGET_TAG_COLLISION"):
            store.copy_by_digest(selected.artifact_uri, "ghcr.io/keliihall/scholarsense-release-stage", tag)
        self.assertFalse(any(len(item) > 1 and item[1] == "copy" for item in runner.commands))

    def test_ghcr_cleanup_deletes_only_the_single_tagged_package_version(self) -> None:
        runner = _FakeOrasRunner()
        digest = evidence().artifact_oci_digest
        tag = "ghcr.io/keliihall/scholarsense-release-stage:release-1.0.0-aaaaaaaaaaaaaaaa"
        runner.descriptors[tag] = digest
        runner.package_versions = [{
            "id": 42,
            "name": digest,
            "metadata": {"container": {"tags": ["release-1.0.0-aaaaaaaaaaaaaaaa"]}},
        }]
        store = OrasDigestStore(Path("/controlled/oras"), runner)

        store.delete_tag(tag)

        self.assertIsNone(store.tag_digest(tag))
        self.assertTrue(any(command[:4] == ["gh", "api", "--method", "DELETE"] for command in runner.commands))
        self.assertFalse(any(command[1:4] == ["manifest", "delete", "--force"] for command in runner.commands))

    def test_ghcr_prepare_removes_only_unledgered_tags_for_the_same_release(self) -> None:
        runner = _FakeOrasRunner()
        digest = evidence().artifact_oci_digest
        repository = "ghcr.io/keliihall/scholarsense-release-stage"
        stale = f"{repository}:release-1.0.0-aaaaaaaaaaaaaaaa"
        other = f"{repository}:release-2.0.0-bbbbbbbbbbbbbbbb"
        runner.descriptors.update({stale: digest, other: digest})
        runner.package_versions = [{
            "id": 42,
            "name": digest,
            "metadata": {"container": {"tags": ["release-1.0.0-aaaaaaaaaaaaaaaa"]}},
        }]
        store = OrasDigestStore(Path("/controlled/oras"), runner)

        store.prepare("1.0.0", repository)

        self.assertIsNone(store.tag_digest(stale))
        self.assertEqual(digest, store.tag_digest(other))

    def test_ghcr_cleanup_refuses_to_delete_a_shared_package_version(self) -> None:
        runner = _FakeOrasRunner()
        digest = evidence().artifact_oci_digest
        tag = "ghcr.io/keliihall/scholarsense-release-stage:release-1.0.0-aaaaaaaaaaaaaaaa"
        runner.descriptors[tag] = digest
        runner.package_versions = [{
            "id": 42,
            "name": digest,
            "metadata": {"container": {"tags": ["release-1.0.0-aaaaaaaaaaaaaaaa", "retain"]}},
        }]
        store = OrasDigestStore(Path("/controlled/oras"), runner)

        with self.assertRaisesRegex(PromotionError, "PROMOTION_PARTIAL_TAG_SHARED_VERSION"):
            store.delete_tag(tag)

        self.assertEqual(digest, store.tag_digest(tag))

    def test_git_ref_ledger_is_create_only_and_reads_canonical_record(self) -> None:
        runner = _FakeGitHubRunner()
        ledger = GitRefLedger("keliihall/ScholarSense-bmad-method", runner=runner)
        adapter = InMemoryPromotionAdapter(
            verifier_identity="protected-release-independent-verifier",
            receipt_ttl=timedelta(minutes=15),
        )
        adapter.seed_candidate(evidence().artifact_uri)
        adapter.seed_evidence(evidence())
        record = adapter.promote(request(), NOW).record

        self.assertTrue(ledger.create("1.0.0", "stage", record))
        self.assertFalse(ledger.create("1.0.0", "stage", {**record, "artifactOciDigest": "sha256:" + "9" * 64}))
        self.assertEqual(record, ledger.read("1.0.0", "stage"))
        stored_blob = next(iter(runner.blobs.values()))
        raw = base64.b64decode(stored_blob)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertNotIn(b" ", raw)

    def test_git_ref_ledger_rejects_tampered_blob_with_stable_error(self) -> None:
        runner = _FakeGitHubRunner()
        ledger = GitRefLedger("keliihall/ScholarSense-bmad-method", runner=runner)
        adapter = InMemoryPromotionAdapter(
            verifier_identity="protected-release-independent-verifier",
            receipt_ttl=timedelta(minutes=15),
        )
        adapter.seed_candidate(evidence().artifact_uri)
        adapter.seed_evidence(evidence())
        record = adapter.promote(request(), NOW).record
        self.assertTrue(ledger.create("1.0.0", "stage", record))
        blob_oid = next(iter(runner.blobs))
        runner.blobs[blob_oid] = "not-base64!"

        with self.assertRaisesRegex(PromotionError, "PROMOTION_LEDGER_RECORD_INVALID"):
            ledger.read("1.0.0", "stage")

    def test_git_ref_ledger_reads_github_line_wrapped_base64_blob_content(self) -> None:
        runner = _FakeGitHubRunner()
        ledger = GitRefLedger("keliihall/ScholarSense-bmad-method", runner=runner)
        adapter = InMemoryPromotionAdapter(
            verifier_identity="protected-release-independent-verifier",
            receipt_ttl=timedelta(minutes=15),
        )
        adapter.seed_candidate(evidence().artifact_uri)
        adapter.seed_evidence(evidence())
        record = adapter.promote(request(), NOW).record
        self.assertTrue(ledger.create("1.0.0", "stage", record))
        runner.wrap_blob_content = True

        self.assertEqual(record, ledger.read("1.0.0", "stage"))

    def test_git_ref_ledger_does_not_treat_auth_failure_as_a_missing_record(self) -> None:
        runner = _FakeGitHubRunner()
        runner.ref_read_error = "authentication required"
        ledger = GitRefLedger("keliihall/ScholarSense-bmad-method", runner=runner)

        with self.assertRaisesRegex(PromotionError, "PROMOTION_LEDGER_REF_READ_FAILED"):
            ledger.read("1.0.0", "stage")


if __name__ == "__main__":
    unittest.main()
