#!/usr/bin/env python3
"""Strict SBOM component inventory and deterministic CycloneDX/SPDX rendering."""

from __future__ import annotations

import base64
import hashlib
import json
import re
import urllib.parse
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any


HEX64 = re.compile(r"^[0-9a-f]{64}$")
BACKEND_SPECIAL_LICENSES = {
    "pkg:maven/ch.qos.logback/logback-classic@1.5.34": "EPL-2.0 OR LGPL-2.1-only",
    "pkg:maven/ch.qos.logback/logback-core@1.5.34": "EPL-2.0 OR LGPL-2.1-only",
    "pkg:maven/jakarta.annotation/jakarta.annotation-api@3.0.0": (
        "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"
    ),
    "pkg:maven/org.hdrhistogram/HdrHistogram@2.2.2": "CC0-1.0 OR BSD-2-Clause",
    "pkg:maven/org.slf4j/jul-to-slf4j@2.0.18": "MIT",
    "pkg:maven/org.slf4j/slf4j-api@2.0.18": "MIT",
}
LICENSE_OBLIGATIONS = {
    "0BSD": ["retain-license-text"],
    "Apache-2.0": ["retain-license-text", "retain-notice", "state-modifications"],
    "BSD-2-Clause": ["retain-license-text", "retain-copyright"],
    "BSD-3-Clause": ["retain-license-text", "retain-copyright", "no-endorsement"],
    "CC0-1.0 OR BSD-2-Clause": ["retain-selected-license-text"],
    "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0": [
        "retain-selected-license-text",
        "publish-covered-source-when-modified",
    ],
    "EPL-2.0 OR LGPL-2.1-only": [
        "retain-selected-license-text",
        "publish-covered-source-when-modified",
        "preserve-relinking-rights-if-lgpl-selected",
    ],
    "ISC": ["retain-license-text", "retain-copyright"],
    "MIT": ["retain-license-text", "retain-copyright"],
    "MPL-2.0": ["retain-license-text", "publish-modified-covered-files-source"],
}
TRIVY_CERTIFICATE_IDENTITY = (
    "https://github.com/aquasecurity/trivy/.github/workflows/"
    "reusable-release.yaml@refs/tags/v0.72.0"
)
GITHUB_OIDC_ISSUER = "https://token.actions.githubusercontent.com"


@dataclass(frozen=True)
class ScanContext:
    trivy_version: str
    trivy_archive_sha256: str
    trivy_binary_sha256: str
    trivy_source_uri: str
    trivy_bundle_sha256: str
    trivy_certificate_identity: str
    trivy_oidc_issuer: str
    cosign_binary_sha256: str
    cosign_source_uri: str
    database_repository: str
    database_sha256: str
    database_metadata_sha256: str
    database_updated_at: str
    database_next_update: str


def scan_context_policy_issues(context: ScanContext) -> list[str]:
    issues: list[str] = []
    if context.trivy_version != "0.72.0":
        issues.append("SBOM_TRIVY_VERSION_FORBIDDEN")
    if context.database_repository != "ghcr.io/aquasecurity/trivy-db:2":
        issues.append("SBOM_TRIVY_DATABASE_REPOSITORY_INVALID")
    if context.trivy_certificate_identity != TRIVY_CERTIFICATE_IDENTITY:
        issues.append("SBOM_TRIVY_CERTIFICATE_IDENTITY_MISMATCH")
    if context.trivy_oidc_issuer != GITHUB_OIDC_ISSUER:
        issues.append("SBOM_TRIVY_OIDC_ISSUER_MISMATCH")
    for value, code in (
        (context.trivy_archive_sha256, "SBOM_TRIVY_ARCHIVE_DIGEST_INVALID"),
        (context.trivy_binary_sha256, "SBOM_TRIVY_BINARY_DIGEST_INVALID"),
        (context.trivy_bundle_sha256, "SBOM_TRIVY_BUNDLE_DIGEST_INVALID"),
        (context.cosign_binary_sha256, "SBOM_COSIGN_BINARY_DIGEST_INVALID"),
        (context.database_sha256, "SBOM_TRIVY_DATABASE_DIGEST_INVALID"),
        (context.database_metadata_sha256, "SBOM_TRIVY_DATABASE_METADATA_DIGEST_INVALID"),
    ):
        if HEX64.fullmatch(value) is None:
            issues.append(code)
    return sorted(set(issues))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _npm_name(path: str, metadata: dict[str, Any]) -> str:
    declared = metadata.get("name")
    if isinstance(declared, str) and declared:
        return declared
    suffix = path.rsplit("node_modules/", 1)[-1]
    parts = suffix.split("/")
    return "/".join(parts[:2]) if parts[0].startswith("@") else parts[0]


def _resolved_npm_name(source: str) -> str:
    parsed = urllib.parse.urlparse(source)
    if parsed.scheme != "https" or parsed.netloc != "registry.npmjs.org":
        raise ValueError(f"NPM_SOURCE_REGISTRY_INVALID: {source}")
    parts = urllib.parse.unquote(parsed.path).strip("/").split("/")
    if not parts or not parts[0]:
        raise ValueError(f"NPM_SOURCE_PATH_INVALID: {source}")
    return "/".join(parts[:2]) if parts[0].startswith("@") else parts[0]


def _npm_purl(name: str, version: str) -> str:
    return f"pkg:npm/{urllib.parse.quote(name, safe='/')}@{version}"


def _maven_purl(coordinate: str) -> str:
    group, artifact, version = coordinate.split(":")
    return f"pkg:maven/{group}/{artifact}@{version}"


def _integrity_hash(integrity: str) -> dict[str, str]:
    try:
        algorithm, encoded = integrity.split("-", 1)
        payload = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError("NPM_INTEGRITY_INVALID") from error
    normalized = algorithm.upper().replace("SHA", "SHA-")
    if normalized not in {"SHA-256", "SHA-384", "SHA-512"}:
        raise ValueError(f"NPM_INTEGRITY_ALGORITHM_UNSUPPORTED: {algorithm}")
    return {"alg": normalized, "content": payload.hex()}


def npm_components(lock: dict[str, Any]) -> list[dict[str, Any]]:
    packages = lock.get("packages")
    if not isinstance(packages, dict):
        raise ValueError("NPM_LOCK_PACKAGES_INVALID")
    by_purl: dict[str, dict[str, Any]] = {}
    for path, metadata in sorted(packages.items()):
        if not path:
            continue
        if not isinstance(metadata, dict):
            raise ValueError(f"NPM_LOCK_PACKAGE_INVALID: {path}")
        version = metadata.get("version")
        source = metadata.get("resolved")
        integrity = metadata.get("integrity")
        license_expression = metadata.get("license")
        if not all(isinstance(value, str) and value for value in (version, source, integrity, license_expression)):
            raise ValueError(f"NPM_LOCK_COMPONENT_INCOMPLETE: {path}")
        name = _resolved_npm_name(source)
        purl = _npm_purl(name, version)
        component = {
            "kind": "npm",
            "name": name,
            "version": version,
            "purl": purl,
            "sourceUri": source,
            "hashes": [_integrity_hash(integrity)],
            "licenseExpression": license_expression,
            "presence": "frontend-lock-and-bundled-build-input",
        }
        previous = by_purl.get(purl)
        if previous is not None and previous != component:
            raise ValueError(f"NPM_PURL_REBOUND: {purl}")
        by_purl[purl] = component
    return [by_purl[purl] for purl in sorted(by_purl)]


def npm_tree_reconciliation(
    lock: dict[str, Any], tree: dict[str, Any]
) -> tuple[dict[str, int], list[str]]:
    issues: list[str] = []
    expected: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    for path, metadata in lock.get("packages", {}).items():
        if not path or not isinstance(metadata, dict):
            continue
        source = metadata.get("resolved")
        version = metadata.get("version")
        if not isinstance(source, str) or not isinstance(version, str):
            issues.append(f"NPM_TREE_LOCK_COMPONENT_INVALID: {path}")
            continue
        identity = (_resolved_npm_name(source), version, source)
        expected.setdefault(identity, []).append(metadata)

    installed: set[tuple[str, str, str]] = set()
    unresolved: set[tuple[str, str]] = set()

    def visit(dependencies: Any, path: str) -> None:
        if not isinstance(dependencies, dict):
            return
        for name, metadata in dependencies.items():
            child_path = f"{path}>{name}"
            if not isinstance(metadata, dict):
                issues.append(f"NPM_TREE_NODE_INVALID: {child_path}")
                continue
            if not metadata:
                continue
            if metadata.get("missing") or metadata.get("invalid") or metadata.get("extraneous"):
                issues.append(f"NPM_TREE_NODE_STATE_INVALID: {child_path}")
            if metadata.get("problems"):
                issues.append(f"NPM_TREE_NODE_PROBLEMS: {child_path}")
            version = metadata.get("version")
            source = metadata.get("resolved")
            if isinstance(version, str) and isinstance(source, str):
                installed.add((_resolved_npm_name(source), version, source))
            elif isinstance(version, str):
                unresolved.add((name, version))
            else:
                issues.append(f"NPM_TREE_NODE_VERSION_INVALID: {child_path}")
            visit(metadata.get("dependencies", {}), child_path)

    if tree.get("problems") or tree.get("error"):
        issues.append("NPM_TREE_ROOT_PROBLEMS")
    visit(tree.get("dependencies"), "root")
    for identity in sorted(installed - set(expected)):
        issues.append(f"NPM_TREE_COMPONENT_UNEXPECTED: {identity[0]}@{identity[1]}")
    missing = set(expected) - installed
    omitted_optional = 0
    for identity in sorted(missing):
        if all(item.get("optional") is True or item.get("devOptional") is True for item in expected[identity]):
            omitted_optional += 1
        else:
            issues.append(f"NPM_TREE_COMPONENT_MISSING: {identity[0]}@{identity[1]}")
    installed_name_versions = {(name, version) for name, version, _ in installed}
    for name, version in sorted(unresolved):
        if (name, version) not in installed_name_versions and not any(
            expected_name == name and expected_version == version
            for expected_name, expected_version, _ in expected
        ):
            issues.append(f"NPM_TREE_DEDUP_REFERENCE_UNRESOLVED: {name}@{version}")
    return (
        {"installedUnique": len(installed), "omittedOptionalUnique": omitted_optional},
        sorted(set(issues)),
    )


def _nested_jar_payloads(jar: Path) -> dict[str, bytes]:
    payloads: dict[str, bytes] = {}
    with zipfile.ZipFile(jar) as archive:
        for info in archive.infolist():
            path = PurePosixPath(info.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in info.filename:
                raise ValueError(f"BACKEND_JAR_PATH_UNSAFE: {info.filename}")
            if info.filename.startswith("BOOT-INF/lib/") and info.filename.endswith(".jar"):
                name = path.name
                if name in payloads:
                    raise ValueError(f"BACKEND_NESTED_JAR_DUPLICATE: {name}")
                payloads[name] = archive.read(info)
    return payloads


def _backend_license(purl: str) -> str:
    return BACKEND_SPECIAL_LICENSES.get(purl, "Apache-2.0")


def backend_components(lock: dict[str, Any], jar: Path) -> list[dict[str, Any]]:
    payloads = _nested_jar_payloads(jar)
    components: list[dict[str, Any]] = []
    expected_names: set[str] = set()
    for item in lock.get("dependencies", []):
        coordinate = item["coordinate"]
        _, artifact, version = coordinate.split(":")
        name = f"{artifact}-{version}.jar"
        expected_names.add(name)
        payload = payloads.get(name)
        if payload is None:
            raise ValueError(f"BACKEND_SBOM_COMPONENT_MISSING_FROM_JAR: {coordinate}")
        digest = hashlib.sha256(payload).hexdigest()
        if digest != item.get("binarySha256"):
            raise ValueError(f"BACKEND_SBOM_COMPONENT_HASH_MISMATCH: {coordinate}")
        purl = _maven_purl(coordinate)
        components.append(
            {
                "kind": "maven-runtime",
                "name": artifact,
                "version": version,
                "purl": purl,
                "sourceUri": item["sourceUri"],
                "hashes": [{"alg": "SHA-256", "content": digest}],
                "licenseExpression": _backend_license(purl),
                "presence": "backend-jar-runtime",
            }
        )
    generated = "spring-boot-jarmode-tools-4.1.0.jar"
    expected_names.add(generated)
    if generated not in payloads:
        raise ValueError("BACKEND_SBOM_JARMODE_TOOLS_MISSING")
    generated_purl = "pkg:maven/org.springframework.boot/spring-boot-jarmode-tools@4.1.0"
    components.append(
        {
            "kind": "maven-generated-runtime",
            "name": "spring-boot-jarmode-tools",
            "version": "4.1.0",
            "purl": generated_purl,
            "sourceUri": (
                "https://repo.maven.apache.org/maven2/org/springframework/boot/"
                "spring-boot-loader-tools/4.1.0/spring-boot-loader-tools-4.1.0.jar"
            ),
            "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(payloads[generated]).hexdigest()}],
            "licenseExpression": "Apache-2.0",
            "presence": "generated-from-spring-boot-loader-tools",
        }
    )
    unexpected = sorted(set(payloads) - expected_names)
    if unexpected:
        raise ValueError(f"BACKEND_SBOM_UNEXPECTED_NESTED_JAR: {unexpected[0]}")
    return sorted(components, key=lambda item: item["purl"])


def aggregate_components(
    manifest: dict[str, Any],
    backend: list[dict[str, Any]],
    npm: list[dict[str, Any]],
    backend_lock: dict[str, Any],
) -> list[dict[str, Any]]:
    components = [copy_component(item) for item in [*backend, *npm]]
    for artifact in manifest.get("artifacts", []):
        name = artifact["name"]
        identity = name.removesuffix(".tar.gz").removesuffix(".jar")
        components.append(
            {
                "kind": "release-artifact",
                "name": identity,
                "version": artifact["binarySha256"],
                "purl": f"pkg:generic/{identity}@{artifact['binarySha256']}",
                "sourceUri": "https://github.com/keliihall/ScholarSense-bmad-method/actions",
                "hashes": [{"alg": "SHA-256", "content": artifact["binarySha256"]}],
                "licenseExpression": "LicenseRef-ScholarSense-First-Party",
                "presence": f"release-artifact:{artifact['mediaType']}",
            }
        )
    for plugin in backend_lock.get("plugins", []):
        _, artifact, version = plugin["coordinate"].split(":")
        components.append(
            {
                "kind": "maven-plugin",
                "name": artifact,
                "version": version,
                "purl": _maven_purl(plugin["coordinate"]),
                "sourceUri": plugin["sourceUri"],
                "hashes": [{"alg": "SHA-256", "content": plugin["binarySha256"]}],
                "licenseExpression": "Apache-2.0",
                "presence": "build-only-not-shipped",
            }
        )
    wrapper = backend_lock["wrapper"]
    _, artifact, version = wrapper["coordinate"].split(":")
    components.append(
        {
            "kind": "maven-wrapper",
            "name": artifact,
            "version": version,
            "purl": _maven_purl(wrapper["coordinate"]),
            "sourceUri": wrapper["sourceUri"],
            "hashes": [{"alg": "SHA-256", "content": wrapper["binarySha256"]}],
            "licenseExpression": "Apache-2.0",
            "presence": "build-tool-not-shipped",
        }
    )
    by_purl: dict[str, dict[str, Any]] = {}
    for component in components:
        purl = component["purl"]
        if purl in by_purl and by_purl[purl] != component:
            raise ValueError(f"AGGREGATE_SBOM_PURL_REBOUND: {purl}")
        by_purl[purl] = component
    return [by_purl[purl] for purl in sorted(by_purl)]


def copy_component(component: dict[str, Any]) -> dict[str, Any]:
    return {
        **component,
        "hashes": [dict(item) for item in component["hashes"]],
    }


def _cdx_component(component: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "library",
        "bom-ref": component["purl"],
        "name": component["name"],
        "version": component["version"],
        "purl": component["purl"],
        "hashes": component["hashes"],
        "licenses": [{"expression": component["licenseExpression"]}],
        "externalReferences": [{"type": "distribution", "url": component["sourceUri"]}],
        "properties": [
            {"name": "scholarsense:presence", "value": component["presence"]},
            {"name": "scholarsense:kind", "value": component["kind"]},
        ],
    }


def build_cyclonedx(
    subject: dict[str, Any],
    components: list[dict[str, Any]],
    context: ScanContext,
    vulnerabilities: list[dict[str, Any]],
) -> dict[str, Any]:
    subject_ref = f"artifact:sha256:{subject['binarySha256']}"
    rendered = [_cdx_component(component) for component in sorted(components, key=lambda item: item["purl"])]
    return {
        "$schema": "http://cyclonedx.org/schema/bom-1.7.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.7",
        "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, f"scholarsense:{subject_ref}:cyclonedx")),
        "version": 1,
        "metadata": {
            "timestamp": context.database_updated_at,
            "tools": {
                "components": [
                    {
                        "type": "application",
                        "name": "trivy",
                        "version": context.trivy_version,
                        "hashes": [{"alg": "SHA-256", "content": context.trivy_binary_sha256}],
                        "externalReferences": [{"type": "distribution", "url": context.trivy_source_uri}],
                        "properties": [
                            {"name": "scholarsense:archive-sha256", "value": context.trivy_archive_sha256},
                            {"name": "scholarsense:sigstore-bundle-sha256", "value": context.trivy_bundle_sha256},
                            {"name": "scholarsense:certificate-identity", "value": context.trivy_certificate_identity},
                            {"name": "scholarsense:oidc-issuer", "value": context.trivy_oidc_issuer},
                            {"name": "scholarsense:cosign-binary-sha256", "value": context.cosign_binary_sha256},
                        ],
                    }
                ]
            },
            "component": {
                "type": "file",
                "bom-ref": subject_ref,
                "name": subject["name"],
                "hashes": [{"alg": "SHA-256", "content": subject["binarySha256"]}],
                "properties": [
                    {"name": "scholarsense:subject-id", "value": subject["id"]},
                    {"name": "scholarsense:media-type", "value": subject["mediaType"]},
                    {"name": "scholarsense:size", "value": str(subject["size"])},
                ],
            },
            "properties": [
                {"name": "scholarsense:trivy-db-sha256", "value": context.database_sha256},
                {"name": "scholarsense:trivy-db-metadata-sha256", "value": context.database_metadata_sha256},
                {"name": "scholarsense:trivy-db-repository", "value": context.database_repository},
                {"name": "scholarsense:trivy-db-next-update", "value": context.database_next_update},
            ],
        },
        "components": rendered,
        "dependencies": [{"ref": subject_ref, "dependsOn": [item["bom-ref"] for item in rendered]}],
        "vulnerabilities": sorted(vulnerabilities, key=lambda item: (item.get("id", ""), json.dumps(item, sort_keys=True))),
    }


def _spdx_id(purl: str) -> str:
    return "SPDXRef-Package-" + hashlib.sha256(purl.encode("utf-8")).hexdigest()[:20]


def _spdx_checksums(hashes: list[dict[str, str]]) -> list[dict[str, str]]:
    return [
        {"algorithm": item["alg"].replace("-", ""), "checksumValue": item["content"]}
        for item in hashes
    ]


def build_spdx(subject: dict[str, Any], components: list[dict[str, Any]], context: ScanContext) -> dict[str, Any]:
    root_id = "SPDXRef-Artifact"
    packages = [
        {
            "name": subject["name"],
            "SPDXID": root_id,
            "downloadLocation": "NOASSERTION",
            "filesAnalyzed": False,
            "checksums": [{"algorithm": "SHA256", "checksumValue": subject["binarySha256"]}],
            "licenseConcluded": "NOASSERTION",
            "licenseDeclared": "NOASSERTION",
            "primaryPackagePurpose": "APPLICATION",
            "sourceInfo": f"subject-id={subject['id']};mediaType={subject['mediaType']};size={subject['size']}",
        }
    ]
    relationships: list[dict[str, str]] = []
    for component in sorted(components, key=lambda item: item["purl"]):
        identifier = _spdx_id(component["purl"])
        packages.append(
            {
                "name": component["name"],
                "SPDXID": identifier,
                "versionInfo": component["version"],
                "downloadLocation": component["sourceUri"],
                "filesAnalyzed": False,
                "checksums": _spdx_checksums(component["hashes"]),
                "licenseConcluded": component["licenseExpression"],
                "licenseDeclared": component["licenseExpression"],
                "externalRefs": [
                    {
                        "referenceCategory": "PACKAGE-MANAGER",
                        "referenceType": "purl",
                        "referenceLocator": component["purl"],
                    }
                ],
                "primaryPackagePurpose": "LIBRARY",
                "sourceInfo": f"kind={component['kind']};presence={component['presence']}",
            }
        )
        relationships.append(
            {"spdxElementId": root_id, "relationshipType": "DEPENDS_ON", "relatedSpdxElement": identifier}
        )
    namespace = f"https://scholarsense.suda.edu.cn/sbom/{subject['binarySha256']}/spdx"
    annotations = [
        f"scholarsense:trivy-archive-sha256={context.trivy_archive_sha256}",
        f"scholarsense:trivy-binary-sha256={context.trivy_binary_sha256}",
        f"scholarsense:trivy-bundle-sha256={context.trivy_bundle_sha256}",
        f"scholarsense:trivy-certificate-identity={context.trivy_certificate_identity}",
        f"scholarsense:trivy-oidc-issuer={context.trivy_oidc_issuer}",
        f"scholarsense:cosign-binary-sha256={context.cosign_binary_sha256}",
        f"scholarsense:trivy-db-sha256={context.database_sha256}",
        f"scholarsense:trivy-db-metadata-sha256={context.database_metadata_sha256}",
        f"scholarsense:trivy-db-repository={context.database_repository}",
        f"scholarsense:trivy-db-next-update={context.database_next_update}",
    ]
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"ScholarSense-{subject['id']}-SBOM",
        "documentNamespace": namespace,
        "creationInfo": {
            "created": context.database_updated_at,
            "creators": [f"Tool: Trivy-{context.trivy_version}", "Organization: ScholarSense"],
        },
        "documentDescribes": [root_id],
        "packages": packages,
        "relationships": relationships,
        "annotations": [
            {
                "annotator": f"Tool: Trivy-{context.trivy_version}",
                "annotationDate": context.database_updated_at,
                "annotationType": "OTHER",
                "comment": comment,
            }
            for comment in annotations
        ],
    }


def _property(properties: Any, name: str) -> str | None:
    if not isinstance(properties, list):
        return None
    for item in properties:
        if isinstance(item, dict) and item.get("name") == name and isinstance(item.get("value"), str):
            return item["value"]
    return None


def _spdx_purls(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for package in document.get("packages", []):
        if not isinstance(package, dict):
            continue
        for reference in package.get("externalRefs", []):
            if isinstance(reference, dict) and reference.get("referenceType") == "purl":
                locator = reference.get("referenceLocator")
                if isinstance(locator, str):
                    result[locator] = package
    return result


def sbom_pair_issues(
    cyclonedx: dict[str, Any],
    spdx: dict[str, Any],
    subject: dict[str, Any],
    components: list[dict[str, Any]],
    context: ScanContext,
) -> list[str]:
    issues: list[str] = []
    metadata = cyclonedx.get("metadata", {})
    subject_component = metadata.get("component", {}) if isinstance(metadata, dict) else {}
    subject_hashes = subject_component.get("hashes", []) if isinstance(subject_component, dict) else []
    if {item.get("content") for item in subject_hashes if isinstance(item, dict)} != {subject["binarySha256"]}:
        issues.append("SBOM_CYCLONEDX_SUBJECT_DIGEST_MISMATCH")
    root_packages = [item for item in spdx.get("packages", []) if item.get("SPDXID") == "SPDXRef-Artifact"]
    if len(root_packages) != 1:
        issues.append("SBOM_SPDX_SUBJECT_INVALID")
    else:
        root_hashes = {item.get("checksumValue") for item in root_packages[0].get("checksums", [])}
        if root_hashes != {subject["binarySha256"]}:
            issues.append("SBOM_SPDX_SUBJECT_DIGEST_MISMATCH")
    expected = {item["purl"]: item for item in components}
    cdx_items = {item.get("purl"): item for item in cyclonedx.get("components", []) if isinstance(item, dict)}
    spdx_items = _spdx_purls(spdx)
    if set(cdx_items) != set(expected):
        issues.append("SBOM_CYCLONEDX_COMPONENT_SET_MISMATCH")
    if set(spdx_items) != set(expected):
        issues.append("SBOM_SPDX_COMPONENT_SET_MISMATCH")
    for purl, component in expected.items():
        cdx = cdx_items.get(purl, {})
        spdx_component = spdx_items.get(purl, {})
        expected_hashes = {(item["alg"], item["content"]) for item in component["hashes"]}
        cdx_hashes = {(item.get("alg"), item.get("content")) for item in cdx.get("hashes", [])}
        spdx_hashes = {
            (item.get("algorithm", "").replace("SHA", "SHA-"), item.get("checksumValue"))
            for item in spdx_component.get("checksums", [])
        }
        if cdx_hashes != expected_hashes:
            issues.append(f"SBOM_CYCLONEDX_COMPONENT_HASH_MISMATCH: {purl}")
        if spdx_hashes != expected_hashes:
            issues.append(f"SBOM_SPDX_COMPONENT_HASH_MISMATCH: {purl}")
        expressions = {
            license_item.get("expression")
            for license_item in cdx.get("licenses", [])
            if isinstance(license_item, dict)
        }
        if expressions != {component["licenseExpression"]}:
            issues.append(f"SBOM_CYCLONEDX_LICENSE_MISMATCH: {purl}")
        if spdx_component.get("licenseConcluded") != component["licenseExpression"]:
            issues.append(f"SBOM_SPDX_LICENSE_MISMATCH: {purl}")
    tools = metadata.get("tools", {}).get("components", []) if isinstance(metadata, dict) else []
    if len(tools) != 1 or tools[0].get("version") != context.trivy_version:
        issues.append("SBOM_TRIVY_VERSION_MISMATCH")
    elif {item.get("content") for item in tools[0].get("hashes", [])} != {context.trivy_binary_sha256}:
        issues.append("SBOM_TRIVY_BINARY_DIGEST_MISMATCH")
    elif (
        _property(tools[0].get("properties"), "scholarsense:certificate-identity")
        != context.trivy_certificate_identity
        or _property(tools[0].get("properties"), "scholarsense:oidc-issuer") != context.trivy_oidc_issuer
        or _property(tools[0].get("properties"), "scholarsense:cosign-binary-sha256")
        != context.cosign_binary_sha256
    ):
        issues.append("SBOM_TRIVY_BUNDLE_VERIFICATION_CONTEXT_MISMATCH")
    if _property(metadata.get("properties"), "scholarsense:trivy-db-sha256") != context.database_sha256:
        issues.append("SBOM_TRIVY_DATABASE_DIGEST_MISMATCH")
    comments = {item.get("comment") for item in spdx.get("annotations", []) if isinstance(item, dict)}
    required_comments = {
        f"scholarsense:trivy-binary-sha256={context.trivy_binary_sha256}",
        f"scholarsense:trivy-certificate-identity={context.trivy_certificate_identity}",
        f"scholarsense:trivy-oidc-issuer={context.trivy_oidc_issuer}",
        f"scholarsense:cosign-binary-sha256={context.cosign_binary_sha256}",
        f"scholarsense:trivy-db-sha256={context.database_sha256}",
    }
    if not required_comments.issubset(comments):
        issues.append("SBOM_SPDX_SCAN_CONTEXT_MISMATCH")
    return sorted(set(issues))


def security_adjudications(
    npm: list[dict[str, Any]], backend_lock: dict[str, Any], package_lock: dict[str, Any]
) -> dict[str, Any]:
    by_purl = {item["purl"]: item for item in npm}
    sensitive = (
        "pkg:npm/%40vitejs/plugin-vue@6.0.8",
        "pkg:npm/%40types/node@24.13.3",
    )
    components: list[dict[str, Any]] = []
    for purl in sensitive:
        item = by_purl.get(purl)
        if item is None:
            raise ValueError(f"SBOM_SENSITIVE_NPM_COMPONENT_MISSING: {purl}")
        components.append(
            {
                "kind": "npm-sensitive",
                "identity": purl,
                "sourceUri": item["sourceUri"],
                "checksum": item["hashes"][0],
                "licenseExpression": item["licenseExpression"],
                "vulnerabilityDisposition": "no-blocked-finding",
                "decision": "approved",
            }
        )
    for plugin in backend_lock.get("plugins", []):
        components.append(
            {
                "kind": "maven-plugin",
                "identity": _maven_purl(plugin["coordinate"]),
                "sourceUri": plugin["sourceUri"],
                "checksum": {"alg": "SHA-256", "content": plugin["binarySha256"]},
                "licenseExpression": "Apache-2.0",
                "vulnerabilityDisposition": "no-blocked-finding",
                "decision": "approved",
            }
        )
    wrapper = backend_lock["wrapper"]
    components.append(
        {
            "kind": "maven-wrapper",
            "identity": _maven_purl(wrapper["coordinate"]),
            "sourceUri": wrapper["sourceUri"],
            "checksum": {"alg": "SHA-256", "content": wrapper["binarySha256"]},
            "licenseExpression": "Apache-2.0",
            "vulnerabilityDisposition": "no-blocked-finding",
            "decision": "approved",
        }
    )
    scripts: list[dict[str, str]] = []
    for path, metadata in sorted(package_lock.get("packages", {}).items()):
        if isinstance(metadata, dict) and metadata.get("hasInstallScript") is True:
            scripts.append(
                {
                    "path": path,
                    "version": str(metadata.get("version", "UNKNOWN")),
                    "decision": "blocked-not-executed",
                    "enforcement": "npm-ci-offline-ignore-scripts",
                }
            )
    return {
        "components": sorted(components, key=lambda item: (item["kind"], item["identity"])),
        "installScripts": scripts,
        "licenseObligations": [
            {"licenseExpression": expression, "obligations": obligations}
            for expression, obligations in sorted(LICENSE_OBLIGATIONS.items())
        ],
    }
