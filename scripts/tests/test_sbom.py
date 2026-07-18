from __future__ import annotations

import copy
from dataclasses import replace
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from release_json import load_json  # noqa: E402
from release_policy import license_issues  # noqa: E402
from sbom import (  # noqa: E402
    ScanContext,
    aggregate_components,
    backend_components,
    build_cyclonedx,
    build_spdx,
    npm_components,
    npm_tree_reconciliation,
    sbom_pair_issues,
    scan_context_policy_issues,
    security_adjudications,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = PROJECT_ROOT / "contracts/release"
PROOF = PROJECT_ROOT / "release-out/task2-proof"


class SbomContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.context = ScanContext(
            trivy_version="0.72.0",
            trivy_archive_sha256="8" * 64,
            trivy_binary_sha256="7" * 64,
            trivy_source_uri="https://github.com/aquasecurity/trivy/releases/download/v0.72.0/trivy.tar.gz",
            trivy_bundle_sha256="6" * 64,
            trivy_certificate_identity="https://github.com/aquasecurity/trivy/.github/workflows/reusable-release.yaml@refs/tags/v0.72.0",
            trivy_oidc_issuer="https://token.actions.githubusercontent.com",
            cosign_binary_sha256="3" * 64,
            cosign_source_uri="https://github.com/sigstore/cosign/releases/download/v3.1.2/cosign-darwin-arm64",
            database_repository="ghcr.io/aquasecurity/trivy-db:2",
            database_sha256="5" * 64,
            database_metadata_sha256="4" * 64,
            database_updated_at="2026-07-18T18:43:59Z",
            database_next_update="2026-07-19T18:43:59Z",
        )

    def test_frontend_lock_produces_exact_unique_hashed_purls(self) -> None:
        components = npm_components(load_json(PROJECT_ROOT / "frontend/package-lock.json"))
        self.assertEqual(156, len(components))
        by_purl = {component["purl"]: component for component in components}
        self.assertIn("pkg:npm/%40sxzz/popperjs-es@2.11.8", by_purl)
        self.assertIn("pkg:npm/%40typescript/typescript6@6.0.2", by_purl)
        self.assertNotIn("pkg:npm/%40popperjs/core@2.11.8", by_purl)
        self.assertTrue(all(component["hashes"] for component in components))
        self.assertTrue(all(component["licenseExpression"] != "UNKNOWN" for component in components))

    def test_backend_artifact_reconciles_lock_and_embedded_jarmode(self) -> None:
        components = backend_components(
            load_json(CONTRACTS / "backend-lock-1.0.0.json"),
            PROOF / "scholarsense-backend.jar",
        )
        self.assertEqual(42, len(components))
        purls = {component["purl"] for component in components}
        self.assertIn("pkg:maven/org.springframework.boot/spring-boot-jarmode-tools@4.1.0", purls)
        self.assertTrue(all(component["hashes"] for component in components))

    def test_cyclonedx_and_spdx_bind_same_subject_components_tool_and_database(self) -> None:
        components = npm_components(load_json(PROJECT_ROOT / "frontend/package-lock.json"))[:3]
        subject = {
            "id": "frontend",
            "name": "scholarsense-frontend.tar.gz",
            "mediaType": "application/gzip",
            "binarySha256": "a" * 64,
            "size": 123,
        }
        cyclonedx = build_cyclonedx(subject, components, self.context, [])
        spdx = build_spdx(subject, components, self.context)
        self.assertEqual([], sbom_pair_issues(cyclonedx, spdx, subject, components, self.context))
        for mutation in ("subject", "component", "tool", "database"):
            with self.subTest(mutation=mutation):
                broken = copy.deepcopy(cyclonedx)
                if mutation == "subject":
                    broken["metadata"]["component"]["hashes"][0]["content"] = "b" * 64
                elif mutation == "component":
                    broken["components"].pop()
                elif mutation == "tool":
                    broken["metadata"]["tools"]["components"][0]["hashes"][0]["content"] = "b" * 64
                else:
                    broken["metadata"]["properties"][0]["value"] = "b" * 64
                self.assertTrue(sbom_pair_issues(broken, spdx, subject, components, self.context))

    def test_license_policy_explicitly_adjudicates_current_expressions(self) -> None:
        policy = load_json(CONTRACTS / "license-policy-1.0.0.json")
        allowed = [
            "MIT",
            "Apache-2.0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "ISC",
            "0BSD",
            "MPL-2.0",
            "EPL-2.0 OR LGPL-2.1-only",
            "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
            "CC0-1.0 OR BSD-2-Clause",
        ]
        self.assertEqual(
            [],
            license_issues(
                [{"purl": f"pkg:generic/license-{index}@1", "license": value} for index, value in enumerate(allowed)],
                policy,
            ),
        )
        for value in ("UNKNOWN", "AGPL-3.0-only", "MIT AND AGPL-3.0-only"):
            self.assertTrue(license_issues([{"purl": "pkg:generic/bad@1", "license": value}], policy))

    def test_security_adjudication_covers_sensitive_inputs_and_blocked_scripts(self) -> None:
        npm = npm_components(load_json(PROJECT_ROOT / "frontend/package-lock.json"))
        backend_lock = load_json(CONTRACTS / "backend-lock-1.0.0.json")
        evidence = security_adjudications(
            npm,
            backend_lock,
            load_json(PROJECT_ROOT / "frontend/package-lock.json"),
        )
        decisions = {item["identity"]: item for item in evidence["components"]}
        self.assertEqual("approved", decisions["pkg:npm/%40vitejs/plugin-vue@6.0.8"]["decision"])
        self.assertEqual("approved", decisions["pkg:npm/%40types/node@24.13.3"]["decision"])
        self.assertEqual(6, sum(item["kind"] == "maven-plugin" for item in evidence["components"]))
        self.assertEqual(1, sum(item["kind"] == "maven-wrapper" for item in evidence["components"]))
        self.assertEqual(3, len(evidence["installScripts"]))
        self.assertTrue(all(item["decision"] == "blocked-not-executed" for item in evidence["installScripts"]))
        obligations = {item["licenseExpression"] for item in evidence["licenseObligations"]}
        self.assertIn("Apache-2.0", obligations)
        self.assertIn("MPL-2.0", obligations)

    def test_aggregate_release_covers_artifacts_runtime_frontend_plugins_and_wrapper(self) -> None:
        manifest = load_json(PROOF / "build-manifest.json")
        npm = npm_components(load_json(PROJECT_ROOT / "frontend/package-lock.json"))
        backend_lock = load_json(CONTRACTS / "backend-lock-1.0.0.json")
        backend = backend_components(backend_lock, PROOF / "scholarsense-backend.jar")
        aggregate = aggregate_components(manifest, backend, npm, backend_lock)
        self.assertEqual(207, len(aggregate))
        kinds = {item["kind"] for item in aggregate}
        self.assertTrue(
            {"release-artifact", "maven-runtime", "maven-generated-runtime", "npm", "maven-plugin", "maven-wrapper"}
            <= kinds
        )
        self.assertEqual(len(aggregate), len({item["purl"] for item in aggregate}))

    def test_npm_ls_must_match_lock_except_uninstalled_optional_platform_components(self) -> None:
        lock = {
            "packages": {
                "": {"name": "root"},
                "node_modules/a": {
                    "version": "1.0.0",
                    "resolved": "https://registry.npmjs.org/a/-/a-1.0.0.tgz",
                    "integrity": "sha512-" + "YQ==",
                    "license": "MIT",
                },
                "node_modules/b-linux": {
                    "version": "2.0.0",
                    "resolved": "https://registry.npmjs.org/b-linux/-/b-linux-2.0.0.tgz",
                    "integrity": "sha512-" + "Yg==",
                    "license": "MIT",
                    "optional": True,
                },
            }
        }
        tree = {
            "dependencies": {
                "a": {
                    "version": "1.0.0",
                    "resolved": "https://registry.npmjs.org/a/-/a-1.0.0.tgz",
                },
                "optional-peer-placeholder": {},
            }
        }
        summary, issues = npm_tree_reconciliation(lock, tree)
        self.assertEqual([], issues)
        self.assertEqual({"installedUnique": 1, "omittedOptionalUnique": 1}, summary)
        extra = copy.deepcopy(tree)
        extra["dependencies"]["evil"] = {
            "version": "9.0.0",
            "resolved": "https://registry.npmjs.org/evil/-/evil-9.0.0.tgz",
        }
        self.assertTrue(npm_tree_reconciliation(lock, extra)[1])

    def test_vulnerable_or_unapproved_trivy_versions_are_rejected(self) -> None:
        self.assertEqual([], scan_context_policy_issues(self.context))
        for version in ("0.69.4", "0.69.5", "0.69.6", "0.71.0", "latest"):
            with self.subTest(version=version):
                self.assertTrue(scan_context_policy_issues(replace(self.context, trivy_version=version)))


if __name__ == "__main__":
    unittest.main()
