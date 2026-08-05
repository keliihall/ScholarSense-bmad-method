from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_data_catalog_contracts import (  # noqa: E402
    EXPECTED_DEPENDENCIES,
    EXPECTED_SOURCES,
    execute_compatibility_fixture,
    execute_negative_fixture,
    load_json,
    schema_issues,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = PROJECT_ROOT / "contracts/data-catalog"
OPENAPI = PROJECT_ROOT / "contracts/openapi/data-source-catalog.openapi.json"


def _openapi_schema_issues(
    openapi: dict[str, object], schema_name: str, instance: object
) -> list[str]:
    schemas = openapi["components"]["schemas"]

    def resolve(reference: str) -> dict[str, object]:
        prefix = "#/components/schemas/"
        if not reference.startswith(prefix):
            raise AssertionError(f"unsupported test reference: {reference}")
        return schemas[reference.removeprefix(prefix)]

    def type_matches(value: object, declared: object) -> bool:
        if isinstance(declared, list):
            return any(type_matches(value, item) for item in declared)
        return {
            "array": lambda: isinstance(value, list),
            "boolean": lambda: isinstance(value, bool),
            "integer": lambda: isinstance(value, int) and not isinstance(value, bool),
            "null": lambda: value is None,
            "object": lambda: isinstance(value, dict),
            "string": lambda: isinstance(value, str),
        }.get(declared, lambda: True)()

    def inspect(value: object, schema: dict[str, object], path: str) -> list[str]:
        if "$ref" in schema:
            return inspect(value, resolve(schema["$ref"]), path)
        issues: list[str] = []
        for branch in schema.get("allOf", []):
            issues.extend(inspect(value, branch, path))
        alternatives = schema.get("oneOf")
        if alternatives is not None:
            matches = sum(not inspect(value, branch, path) for branch in alternatives)
            if matches != 1:
                issues.append(f"oneOf:{path}")
            return issues
        declared = schema.get("type")
        if declared is not None and not type_matches(value, declared):
            return [f"type:{path}"]
        if "const" in schema and value != schema["const"]:
            issues.append(f"const:{path}")
        if "enum" in schema and value not in schema["enum"]:
            issues.append(f"enum:{path}")
        if isinstance(value, dict):
            properties = schema.get("properties", {})
            for required in schema.get("required", []):
                if required not in value:
                    issues.append(f"required:{path}.{required}")
            for key, child in value.items():
                if key in properties:
                    issues.extend(inspect(child, properties[key], f"{path}.{key}"))
                elif schema.get("additionalProperties", True) is False:
                    issues.append(f"additional:{path}.{key}")
        if isinstance(value, list):
            if len(value) < schema.get("minItems", len(value)):
                issues.append(f"minItems:{path}")
            if len(value) > schema.get("maxItems", len(value)):
                issues.append(f"maxItems:{path}")
            if schema.get("uniqueItems") and len({
                json.dumps(item, ensure_ascii=False, sort_keys=True)
                for item in value
            }) != len(value):
                issues.append(f"uniqueItems:{path}")
            if "contains" in schema:
                matching = sum(
                    not inspect(child, schema["contains"], f"{path}[{index}]")
                    for index, child in enumerate(value)
                )
                if matching < schema.get("minContains", 1):
                    issues.append(f"minContains:{path}")
                maximum = schema.get("maxContains")
                if maximum is not None and matching > maximum:
                    issues.append(f"maxContains:{path}")
            if "items" in schema:
                for index, child in enumerate(value):
                    issues.extend(inspect(child, schema["items"], f"{path}[{index}]"))
        if isinstance(value, str) and "pattern" in schema:
            if re.search(schema["pattern"], value) is None:
                issues.append(f"pattern:{path}")
        if isinstance(value, int) and not isinstance(value, bool):
            if value < schema.get("minimum", value):
                issues.append(f"minimum:{path}")
            if value > schema.get("maximum", value):
                issues.append(f"maximum:{path}")
        return issues

    return inspect(instance, schemas[schema_name], "$")


def _java_catalog_json(status: str, *, detail: bool) -> dict[str, object]:
    published = status == "PUBLISHED"
    value: dict[str, object] = {
        "catalogId": "019fc6b8-9400-7000-8000-000000000011",
        "catalogReleaseId": (
            "019fc6b8-9400-7000-8000-000000000012" if published else None
        ),
        "contractVersion": "DCC-1.0.0",
        "status": status,
        "aggregateVersion": 3 if published else 1,
        "currentPointerVersion": 1 if published else 0,
        "contentDigest": "sha256:" + "a" * 64,
        "evidenceSetDigest": "sha256:" + "b" * 64 if published else None,
        "validationFailures": [],
        "updatedAt": "2026-08-05T01:00:00Z",
        "publishedAt": "2026-08-05T01:00:00Z" if published else None,
    }
    if detail:
        catalog = json.loads((CONTRACT_ROOT / "dcc-1.0.0.json").read_text())
        sources: list[dict[str, object]] = []
        for index, descriptor in enumerate(catalog["sources"]):
            source_id = descriptor["sourceId"]
            sources.append({
                "sourceId": source_id,
                "purpose": descriptor["purpose"],
                "schemaVersion": descriptor["schemaVersion"],
                "qualityGateVersion": descriptor["qualityGateVersion"],
                "evidenceUri": (
                    "evidence+sha256://" + f"{index + 1:064x}" + f"#source={source_id}"
                    if published
                    else f"evidence://pending/{source_id}"
                ),
                "runtimeEvidenceClaim": "TARGET_VERIFIED" if published else "NONE",
                "metadata": {
                    "ownerDepartment": descriptor["ownerDepartment"],
                    "ownerName": descriptor["ownerName"],
                    "responsibleRole": descriptor["responsibleRole"],
                    "businessDefinition": descriptor["businessDefinition"],
                    "businessKeys": descriptor["businessKeys"],
                    "effectiveInterval": descriptor["effectiveInterval"],
                    "updateFrequency": descriptor["updateFrequency"],
                    "slo": descriptor["slo"],
                    "coverage": descriptor["coverage"],
                    "sensitivity": descriptor["sensitivity"],
                    "schemaRef": descriptor["schemaRef"],
                    "reconciliation": descriptor["reconciliation"]["schedule"],
                    "reconciliationMinimumBasisPoints": descriptor["reconciliation"]["minimumBasisPoints"],
                    "backfillWindowDays": descriptor["backfill"]["windowDays"],
                    "watermarkRequired": descriptor["backfill"]["watermarkRequired"],
                    "contractTests": descriptor["contractTests"],
                    "consumerMode": descriptor["consumerMode"],
                    "status": descriptor["status"],
                },
            })
        value["sources"] = sources
        value["dependencies"] = [
            {
                "sourceId": source_id,
                "dependencyId": dependency_id,
                "requirement": "REQUIRED",
                "operator": "ALL_OF",
            }
            for source_id, dependency_id in EXPECTED_DEPENDENCIES.items()
        ]
    return value


class DataCatalogContractTest(unittest.TestCase):
    def test_catalog_operation_responses_are_exact_and_reference_the_right_projection(self) -> None:
        openapi = json.loads(OPENAPI.read_text(encoding="utf-8"))
        paths = openapi["paths"]
        listing = paths["/api/v1/data-source-catalogs"]["get"]["responses"]
        detail = paths["/api/v1/data-source-catalogs/{catalogId}"]["get"]["responses"]
        validation = paths[
            "/api/v1/data-source-catalogs/{catalogId}/validations"
        ]["post"]["responses"]
        publication = paths[
            "/api/v1/data-source-catalogs/{catalogId}/publications"
        ]["post"]["responses"]

        self.assertEqual({"200", "400", "404", "503"}, set(listing))
        self.assertEqual({"200", "400", "404", "503"}, set(detail))
        self.assertEqual({"200", "400", "404", "409", "422", "503"}, set(validation))
        self.assertEqual({"200", "400", "404", "409", "422", "503"}, set(publication))
        self.assertEqual(
            {"$ref": "#/components/responses/Unavailable"}, validation["503"]
        )
        self.assertEqual(
            "#/components/schemas/CatalogDetail",
            detail["200"]["content"]["application/json"]["schema"]["$ref"],
        )
        for response in (validation, publication):
            self.assertEqual(
                "#/components/schemas/CatalogSummary",
                response["200"]["content"]["application/json"]["schema"]["$ref"],
            )
        page_items = openapi["components"]["schemas"]["CatalogPage"]["properties"][
            "items"
        ]
        self.assertEqual(100, page_items["maxItems"])
        self.assertEqual("#/components/schemas/CatalogSummary", page_items["items"]["$ref"])
        detail_properties = openapi["components"]["schemas"]["CatalogDetail"]["properties"]
        self.assertEqual((17, 17), (
            detail_properties["sources"]["minItems"],
            detail_properties["sources"]["maxItems"],
        ))
        self.assertEqual((11, 11), (
            detail_properties["dependencies"]["minItems"],
            detail_properties["dependencies"]["maxItems"],
        ))
        self.assertEqual(
            EXPECTED_SOURCES,
            set(openapi["components"]["schemas"]["SourceContract"]
                ["properties"]["sourceId"]["enum"]),
        )
        dependency_schema = openapi["components"]["schemas"]["DependencyBinding"]
        alternatives = dependency_schema["allOf"][0]["oneOf"]
        self.assertEqual(
            EXPECTED_DEPENDENCIES,
            {
                branch["properties"]["sourceId"]["const"]:
                    branch["properties"]["dependencyId"]["const"]
                for branch in alternatives
            },
        )
        self.assertEqual(
            "REQUIRED", dependency_schema["properties"]["requirement"]["const"]
        )
        self.assertEqual(
            "ALL_OF", dependency_schema["properties"]["operator"]["const"]
        )

    def test_openapi_declares_session_and_csrf_boundaries(self) -> None:
        openapi = json.loads(OPENAPI.read_text(encoding="utf-8"))
        self.assertEqual([{"sessionCookie": []}], openapi["security"])
        self.assertEqual(
            {"type": "apiKey", "in": "cookie", "name": "SESSION"},
            openapi["components"]["securitySchemes"]["sessionCookie"],
        )
        csrf = openapi["components"]["parameters"]["CsrfToken"]
        self.assertEqual(("X-CSRF-TOKEN", "header", True), (
            csrf["name"], csrf["in"], csrf["required"],
        ))
        for suffix in ("validations", "publications"):
            parameters = openapi["paths"][
                f"/api/v1/data-source-catalogs/{{catalogId}}/{suffix}"
            ]["post"]["parameters"]
            self.assertIn({"$ref": "#/components/parameters/CsrfToken"}, parameters)

    def test_summary_and_detail_schemas_reject_cross_layer_or_partial_shapes(self) -> None:
        openapi = json.loads(OPENAPI.read_text(encoding="utf-8"))
        summary = _java_catalog_json("DRAFT", detail=False)

        summary_with_sources = copy.deepcopy(summary)
        summary_with_sources["sources"] = []
        self.assertTrue(
            _openapi_schema_issues(openapi, "CatalogSummary", summary_with_sources)
        )

        detail_without_sources = _java_catalog_json("DRAFT", detail=True)
        detail_without_sources.pop("sources")
        self.assertTrue(
            _openapi_schema_issues(openapi, "CatalogDetail", detail_without_sources)
        )

        partial_detail = _java_catalog_json("DRAFT", detail=True)
        partial_detail["sources"].pop()
        self.assertIn(
            "minItems:$.sources",
            _openapi_schema_issues(openapi, "CatalogDetail", partial_detail),
        )

        for key in ("catalogReleaseId", "evidenceSetDigest", "publishedAt"):
            missing = copy.deepcopy(summary)
            missing.pop(key)
            with self.subTest(missing=key):
                self.assertTrue(
                    _openapi_schema_issues(openapi, "CatalogSummary", missing)
                )

        published_without_evidence = _java_catalog_json("DRAFT", detail=False)
        published_without_evidence["status"] = "PUBLISHED"
        self.assertTrue(
            _openapi_schema_issues(
                openapi, "CatalogSummary", published_without_evidence
            )
        )
        draft_with_publication = _java_catalog_json("PUBLISHED", detail=False)
        draft_with_publication["status"] = "DRAFT"
        self.assertTrue(
            _openapi_schema_issues(openapi, "CatalogSummary", draft_with_publication)
        )

        false_runtime_claim = _java_catalog_json("DRAFT", detail=True)
        false_runtime_claim["sources"][0]["runtimeEvidenceClaim"] = "TARGET_VERIFIED"
        self.assertTrue(
            _openapi_schema_issues(openapi, "CatalogDetail", false_runtime_claim)
        )

        duplicate_source = _java_catalog_json("DRAFT", detail=True)
        replacement = copy.deepcopy(duplicate_source["sources"][0])
        replacement["purpose"] = "different object with the same source identity"
        duplicate_source["sources"][-1] = replacement
        duplicate_issues = _openapi_schema_issues(
            openapi, "CatalogDetail", duplicate_source
        )
        self.assertIn("maxContains:$.sources", duplicate_issues)
        self.assertIn("minContains:$.sources", duplicate_issues)

        wrong_pending_source = _java_catalog_json("DRAFT", detail=True)
        wrong_pending_source["sources"][0]["evidenceUri"] = (
            f"evidence://pending/{wrong_pending_source['sources'][1]['sourceId']}"
        )
        self.assertTrue(
            _openapi_schema_issues(openapi, "CatalogDetail", wrong_pending_source)
        )

        wrong_verified_source = _java_catalog_json("PUBLISHED", detail=True)
        wrong_verified_source["sources"][0]["evidenceUri"] = (
            "evidence+sha256://" + "f" * 64
            + f"#source={wrong_verified_source['sources'][1]['sourceId']}"
        )
        self.assertTrue(
            _openapi_schema_issues(openapi, "CatalogDetail", wrong_verified_source)
        )

    def test_openapi_numeric_versions_share_the_javascript_safe_integer_ceiling(self) -> None:
        openapi = json.loads(OPENAPI.read_text(encoding="utf-8"))
        maximum = 9_007_199_254_740_991
        summary = _java_catalog_json("DRAFT", detail=False)
        summary["aggregateVersion"] = maximum
        summary["currentPointerVersion"] = maximum
        self.assertEqual([], _openapi_schema_issues(openapi, "CatalogSummary", summary))
        for field in ("aggregateVersion", "currentPointerVersion"):
            invalid = copy.deepcopy(summary)
            invalid[field] = maximum + 1
            self.assertIn(
                f"maximum:$.{field}",
                _openapi_schema_issues(openapi, "CatalogSummary", invalid),
            )

        error = {
            "code": "INGESTION_QUALITY_VERSION_CONFLICT",
            "traceId": "a" * 32,
            "currentVersion": maximum,
            "fieldErrors": [],
        }
        self.assertEqual([], _openapi_schema_issues(openapi, "Error", error))
        error["currentVersion"] = maximum + 1
        self.assertIn(
            "maximum:$.currentVersion",
            _openapi_schema_issues(openapi, "Error", error),
        )

    def test_draft_and_published_java_summary_and_detail_json_are_valid(self) -> None:
        openapi = json.loads(OPENAPI.read_text(encoding="utf-8"))
        for status in ("DRAFT", "PUBLISHED"):
            for detail in (False, True):
                schema = "CatalogDetail" if detail else "CatalogSummary"
                with self.subTest(status=status, schema=schema):
                    self.assertEqual(
                        [],
                        _openapi_schema_issues(
                            openapi, schema, _java_catalog_json(status, detail=detail)
                        ),
                    )

    def test_compatibility_successor_preserves_original_lock_and_fixtures(self) -> None:
        expected = {
            "data-catalog-contract-lock-1.0.0.json": "1bc3bc26526732a144d58672b43b0e3c524e12e1a1a806c476be79830e7065c3",
            "fixtures/compatibility/optional-addition-1.1.0.json": "63242c5a42fe445094a438a94b778fdf7bd44a03310c831aff3f84eb42d2dc41",
            "fixtures/compatibility/breaking-without-major.json": "1b35b1f627c95c6f764c866b0848d44cfaf2e5388658f077ab26151c12e182c9",
        }
        for relative, digest in expected.items():
            with self.subTest(relative=relative):
                self.assertEqual(
                    digest,
                    hashlib.sha256((CONTRACT_ROOT / relative).read_bytes()).hexdigest(),
                )

    def test_locked_package_passes_and_exact_sets_are_derived(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))
        catalog = json.loads((CONTRACT_ROOT / "dcc-1.0.0.json").read_text())
        registry = json.loads(
            (CONTRACT_ROOT / "dependency-registry-1.0.0.json").read_text()
        )
        self.assertEqual(EXPECTED_SOURCES, {item["sourceId"] for item in catalog["sources"]})
        self.assertEqual(
            EXPECTED_DEPENDENCIES,
            {item["sourceId"]: item["dependencyId"] for item in registry["bindings"]},
        )

    def test_negative_fixtures_execute_declared_inputs_and_codes(self) -> None:
        fixture = json.loads(
            (CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json").read_text()
        )
        for case in fixture["cases"]:
            with self.subTest(case=case["id"]):
                self.assertIn("input", case)
                self.assertEqual(case["expectedCode"], execute_negative_fixture(case, PROJECT_ROOT))

        invented = copy.deepcopy(fixture["cases"][0])
        invented["mutation"] = "invented"
        self.assertEqual("DCC_FIXTURE_INVALID", execute_negative_fixture(invented, PROJECT_ROOT))

    def test_compatibility_allows_optional_only_and_rejects_breaking_same_major(self) -> None:
        for name in (
            "optional-addition-actual-1.1.0.json",
            "breaking-actual-without-major.json",
            "version-regression-actual.json",
            "same-version-mutation-actual.json",
        ):
            document = json.loads((CONTRACT_ROOT / "fixtures/compatibility" / name).read_text())
            with self.subTest(name=name):
                self.assertEqual(document["expectedCode"], execute_compatibility_fixture(document))

        optional = json.loads(
            (
                CONTRACT_ROOT
                / "fixtures/compatibility/optional-addition-actual-1.1.0.json"
            ).read_text()
        )
        declared_lie = copy.deepcopy(optional)
        declared_lie["change"] = {"kind": "add-required", "field": "activityCategory"}
        self.assertEqual("DCC_COMPATIBLE", execute_compatibility_fixture(declared_lie))

        actually_required = copy.deepcopy(optional)
        actually_required["afterSchema"]["required"].append("activityCategory")
        self.assertEqual(
            "DCC_BREAKING_CHANGE_REQUIRES_MAJOR",
            execute_compatibility_fixture(actually_required),
        )

        removed_property = copy.deepcopy(optional)
        removed_property["afterSchema"] = copy.deepcopy(removed_property["beforeSchema"])
        removed_property["afterSchema"]["properties"].pop("activityStartsAt")
        self.assertEqual(
            "DCC_BREAKING_CHANGE_REQUIRES_MAJOR",
            execute_compatibility_fixture(removed_property),
        )

    def test_target_and_runtime_evidence_schemas_reject_open_nested_shapes(self) -> None:
        target_schema = load_json(CONTRACT_ROOT / "data-catalog-target-report.schema.json")
        fake_report = {
            "reportVersion": "DCC-TARGET-REPORT-1.0.0",
            "contractVersion": "DCC-1.0.0",
            "qualityGateVersion": "QG-1.0.0",
            "authority": "stage.catalog-owner",
            "environment": "stage",
            "candidateCommit": "a" * 40,
            "candidateTree": "b" * 40,
            "handoffRevision": 1,
            "handoffDigest": "sha256:" + "c" * 64,
            "catalogDigest": "sha256:" + "d" * 64,
            "qualityGateDigest": "sha256:" + "e" * 64,
            "sourceCount": 17,
            "sources": [{} for _ in range(17)],
            "privacy": {"studentPlaintextStored": False, "rawBodyStored": False},
            "occurredAt": "2026-08-05T01:00:00Z",
            "runtimeEvidenceClaim": "target-verified",
            "result": "pass",
            "evidenceDigest": "sha256:" + "f" * 64,
        }
        self.assertTrue(schema_issues(fake_report, target_schema))

        runtime_schema = load_json(CONTRACT_ROOT / "data-catalog-runtime-evidence.schema.json")
        source_evidence = {
            "sourceId": "SRC-P0-CALENDAR-001",
            "contractVersion": "DCC-1.0.0",
            "schemaVersion": "BC-1.0.0",
            "qualityGateVersion": "QG-1.0.0",
            "environment": "stage",
            "authority": "stage.catalog-owner",
            "candidateCommit": "a" * 40,
            "candidateTree": "b" * 40,
            "handoffRevision": 1,
            "handoffDigest": "sha256:" + "c" * 64,
            "inputDigest": "sha256:" + "d" * 64,
            "scenarios": [{
                "id": "calendar-boundary",
                "result": "pass",
                "observationDigest": "sha256:" + "e" * 64,
                "rawResponse": "must-not-be-accepted",
            }],
            "result": "pass",
            "occurredAt": "2026-08-05T01:00:00Z",
            "signatureDigest": "sha256:" + "f" * 64,
            "evidenceDigest": "sha256:" + "1" * 64,
            "cleanupResult": "pass",
            "runtimeEvidenceClaim": "target-verified",
        }
        self.assertTrue(schema_issues(source_evidence, runtime_schema))

    def test_non_rule_sources_never_receive_dependency_ids(self) -> None:
        catalog = json.loads((CONTRACT_ROOT / "dcc-1.0.0.json").read_text())
        mapped = set(EXPECTED_DEPENDENCIES)
        for descriptor in catalog["sources"]:
            if descriptor["sourceId"] not in mapped:
                self.assertNotIn("dependencyId", descriptor)
                self.assertEqual("purpose-isolated", descriptor["consumerMode"])

    def test_calendar_and_timetable_minimal_schemas_are_privacy_bounded(self) -> None:
        calendar = json.loads(
            (CONTRACT_ROOT / "sources/src-p0-calendar-001.schema.json").read_text()
        )
        timetable = json.loads(
            (CONTRACT_ROOT / "sources/src-p0-timetable-001.schema.json").read_text()
        )
        self.assertEqual("Asia/Shanghai", calendar["properties"]["businessTimezone"]["const"])
        self.assertEqual(
            {"workday", "weekend", "statutory-holiday", "makeup-workday", "school-holiday", "emergency-closure"},
            set(calendar["properties"]["dayType"]["enum"]),
        )
        forbidden = {"grade", "ranking", "thesis", "fullAcademicRecord"}
        self.assertTrue(forbidden.isdisjoint(timetable["properties"]))
        self.assertFalse(timetable["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
