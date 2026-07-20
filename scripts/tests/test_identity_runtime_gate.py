from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_identity_runtime_evidence import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class IdentityRuntimeEvidenceGateTest(unittest.TestCase):
    def test_user_authorized_external_waiver_allows_controlled_review_completion(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT, require_complete=False))
        self.assertEqual([], validate(PROJECT_ROOT, require_complete=True))

    def test_controlled_completion_rejects_a_missing_or_broadened_waiver(self) -> None:
        with self.fixture() as root:
            waiver_path = root / "deploy/base/story-1.2-external-evidence-waiver-1.0.0.json"
            waiver_path.unlink()
            self.assertIn(
                "IDENTITY_RUNTIME_EXTERNAL_EVIDENCE_WAIVER_INVALID",
                validate(root, require_complete=True),
            )

    def test_complete_profile_requires_every_runtime_binding_and_signed_evidence(self) -> None:
        with self.fixture() as root:
            profile_path = root / "contracts/identity/identity-runtime-profile-1.0.0.json"
            profile = json.loads(profile_path.read_text(encoding="utf-8"))
            profile["status"] = "complete"
            profile["missingRequirements"] = []
            profile["crossSiteDecision"] = "same-site"
            profile["runtimeBindings"] = {
                key: f"config://stage/{key}"
                for key in profile["requiredRuntimeBindings"]
            }
            profile["evidence"] = {
                "hostSsoIntegration": "evidence://signed/host-sso-integration.json",
                "portalConfiguration": "evidence://signed/portal-configuration.json",
                "browserMatrix": "evidence://signed/browser-matrix.json",
                "redactedTrace": "evidence://signed/redacted-trace.json",
                "clockSynchronization": "evidence://signed/clock/clockSource.json",
            }
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            self.assertEqual([], validate(root, require_complete=True))

            broken = copy.deepcopy(profile)
            broken["runtimeBindings"].pop("issuer")
            profile_path.write_text(json.dumps(broken), encoding="utf-8")
            self.assertIn("IDENTITY_RUNTIME_BINDINGS_INCOMPLETE", validate(root, require_complete=True))

    def test_profile_cannot_contain_secret_material_or_unapproved_cookie_relaxation(self) -> None:
        with self.fixture() as root:
            profile_path = root / "contracts/identity/identity-runtime-profile-1.0.0.json"
            profile = json.loads(profile_path.read_text(encoding="utf-8"))
            profile["runtimeBindings"]["clientSecret"] = "plaintext-secret"
            profile["cookiePolicy"] = "SameSite=None"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            issues = validate(root, require_complete=False)
            self.assertIn("IDENTITY_RUNTIME_SECRET_MATERIAL_FORBIDDEN", issues)
            self.assertIn("IDENTITY_RUNTIME_COOKIE_POLICY_INVALID", issues)

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        source = PROJECT_ROOT / "contracts/identity"
        target = root / "contracts/identity"
        target.mkdir(parents=True)
        for path in source.glob("*"):
            if path.is_file():
                (target / path.name).write_bytes(path.read_bytes())
        waiver_source = PROJECT_ROOT / "deploy/base/story-1.2-external-evidence-waiver-1.0.0.json"
        waiver_target = root / "deploy/base/story-1.2-external-evidence-waiver-1.0.0.json"
        waiver_target.parent.mkdir(parents=True)
        waiver_target.write_bytes(waiver_source.read_bytes())

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
