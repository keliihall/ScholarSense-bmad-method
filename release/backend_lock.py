#!/usr/bin/env python3
"""Generate and verify the resolved Maven runtime/plugin/Wrapper lock."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any


CENTRAL = "https://repo.maven.apache.org/maven2/"
WRAPPER_COORDINATE = "org.apache.maven:apache-maven:3.9.16"
WRAPPER_SOURCE = CENTRAL + "org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip"
WRAPPER_SHA256 = "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce"
BUILD_PLUGINS = (
    ("org.springframework.boot", "spring-boot-maven-plugin", "4.1.0"),
    ("org.apache.maven.plugins", "maven-clean-plugin", "3.5.0"),
    ("org.apache.maven.plugins", "maven-resources-plugin", "3.5.0"),
    ("org.apache.maven.plugins", "maven-jar-plugin", "3.5.0"),
    ("org.apache.maven.plugins", "maven-compiler-plugin", "3.15.0"),
    ("org.apache.maven.plugins", "maven-surefire-plugin", "3.5.6"),
)
DYNAMIC = re.compile(r"(?i)(?:SNAPSHOT|LATEST|RELEASE|\[|\]|\(|\)|\+|\*)")
GENERATED_RUNTIME_LIBS = {"spring-boot-jarmode-tools-4.1.0.jar"}
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _component(group: str, artifact: str, version: str, repository: Path) -> dict[str, str]:
    relative = Path(*group.split(".")) / artifact / version / f"{artifact}-{version}.jar"
    path = repository / relative
    if not path.is_file():
        raise ValueError(f"BACKEND_LOCK_ARTIFACT_MISSING: {group}:{artifact}:{version}")
    return {
        "coordinate": f"{group}:{artifact}:{version}",
        "sourceUri": CENTRAL + relative.as_posix(),
        "binarySha256": _sha256(path),
    }


def _coordinate_from_path(path: Path, repository: Path) -> tuple[str, str, str]:
    relative = path.relative_to(repository)
    if len(relative.parts) < 4:
        raise ValueError(f"BACKEND_LOCK_PATH_INVALID: {relative}")
    artifact = relative.parts[-3]
    version = relative.parts[-2]
    group = ".".join(relative.parts[:-3])
    if path.name != f"{artifact}-{version}.jar":
        raise ValueError(f"BACKEND_LOCK_CLASSIFIER_UNSUPPORTED: {relative}")
    return group, artifact, version


def generate_backend_lock(project_root: Path, repository: Path | None = None) -> dict[str, Any]:
    root = project_root.resolve()
    maven_repository = (repository or Path.home() / ".m2/repository").resolve()
    jar = root / "backend/target/scholarsense-backend.jar"
    if not jar.is_file():
        raise ValueError("BACKEND_RELEASE_JAR_REQUIRED")
    names: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                names.append(Path(name).name)
    dependencies: list[dict[str, str]] = []
    for name in sorted(names):
        if name in GENERATED_RUNTIME_LIBS:
            continue
        matches = sorted(maven_repository.rglob(name))
        exact = [path for path in matches if path.is_file()]
        if len(exact) != 1:
            raise ValueError(f"BACKEND_LOCK_ARTIFACT_AMBIGUOUS: {name}: {len(exact)}")
        dependencies.append(_component(*_coordinate_from_path(exact[0], maven_repository), maven_repository))
    plugins = [_component(group, artifact, version, maven_repository) for group, artifact, version in BUILD_PLUGINS]
    return {
        "version": "BACKEND-LOCK-1.0.0",
        "dependencies": sorted(dependencies, key=lambda item: item["coordinate"]),
        "plugins": sorted(plugins, key=lambda item: item["coordinate"]),
        "wrapper": {
            "coordinate": WRAPPER_COORDINATE,
            "sourceUri": WRAPPER_SOURCE,
            "binarySha256": WRAPPER_SHA256,
        },
    }


def _declared_plugins(project_root: Path) -> tuple[dict[str, str], dict[str, str]]:
    pom = ElementTree.parse(project_root / "backend/pom.xml").getroot()

    def collect(xpath: str) -> dict[str, str]:
        result: dict[str, str] = {}
        for plugin in pom.findall(xpath, MAVEN_NAMESPACE):
            group = plugin.findtext("m:groupId", default="org.apache.maven.plugins", namespaces=MAVEN_NAMESPACE)
            artifact = plugin.findtext("m:artifactId", namespaces=MAVEN_NAMESPACE)
            version = plugin.findtext("m:version", namespaces=MAVEN_NAMESPACE)
            if artifact and version:
                result[f"{group}:{artifact}"] = version
        return result

    return (
        collect("m:build/m:plugins/m:plugin"),
        collect("m:build/m:pluginManagement/m:plugins/m:plugin"),
    )


def _wrapper_properties(project_root: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in (project_root / "backend/.mvn/wrapper/maven-wrapper.properties").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


def plugin_resolution_graph(output: str, repository: Path) -> list[dict[str, Any]]:
    roots: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    seen_by_root: dict[str, set[str]] = {}
    for raw in output.splitlines():
        if raw.startswith("[INFO] "):
            raw = raw.removeprefix("[INFO] ")
        if not re.match(r"^ {3}(?: {3})?[^ ]", raw):
            continue
        indentation = len(raw) - len(raw.lstrip(" "))
        rendered = raw.strip()
        coordinate, separator, absolute = rendered.rpartition(":")
        path = Path(absolute)
        if not separator or not path.is_absolute() or not path.is_file():
            continue
        fields = coordinate.split(":")
        if len(fields) not in {4, 5} or fields[2] != "jar":
            raise ValueError(f"BACKEND_LOCK_PLUGIN_RESOLUTION_COORDINATE_INVALID: {coordinate}")
        group, artifact = fields[0], fields[1]
        version = fields[-1]
        expected_parent = repository / Path(*group.split(".")) / artifact / version
        try:
            relative = path.resolve().relative_to(repository.resolve())
        except ValueError as error:
            raise ValueError(f"BACKEND_LOCK_PLUGIN_RESOLUTION_PATH_INVALID: {coordinate}") from error
        if path.parent.resolve() != expected_parent.resolve():
            raise ValueError(f"BACKEND_LOCK_PLUGIN_RESOLUTION_PATH_INVALID: {coordinate}")
        pom = expected_parent / f"{artifact}-{version}.pom"
        if not pom.is_file():
            raise ValueError(f"BACKEND_LOCK_PLUGIN_POM_MISSING: {coordinate}")
        entry = {
            "coordinate": coordinate,
            "sourceUri": CENTRAL + relative.as_posix(),
            "binarySha256": _sha256(path),
            "pomSourceUri": CENTRAL + pom.relative_to(repository).as_posix(),
            "pomSha256": _sha256(pom),
        }
        if indentation == 3:
            root_coordinate = f"{group}:{artifact}:{version}"
            current = {"root": root_coordinate, "artifacts": []}
            roots.append(current)
            seen_by_root[root_coordinate] = set()
        if current is None:
            raise ValueError("BACKEND_LOCK_PLUGIN_RESOLUTION_ROOT_MISSING")
        seen = seen_by_root[current["root"]]
        if coordinate not in seen:
            current["artifacts"].append(entry)
            seen.add(coordinate)
    for root in roots:
        root["artifacts"] = sorted(root["artifacts"], key=lambda item: item["coordinate"])
    return sorted(roots, key=lambda item: item["root"])


def resolve_plugin_graph(project_root: Path) -> list[dict[str, Any]]:
    result = subprocess.run(
        [
            str(project_root / "_bmad/scripts/with_pab_toolchain.sh"),
            str(project_root / "backend/mvnw"),
            "-f", str(project_root / "backend/pom.xml"),
            "-o", "dependency:resolve-plugins",
            "-DincludeTransitive=true",
            "-DoutputAbsoluteArtifactFilename=true",
        ],
        cwd=project_root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError("BACKEND_LOCK_PLUGIN_RESOLUTION_FAILED")
    return plugin_resolution_graph(result.stdout, Path.home() / ".m2/repository")


def validate_backend_lock(lock: dict[str, Any], project_root: Path) -> list[str]:
    issues: list[str] = []
    if lock.get("version") != "BACKEND-LOCK-1.0.0":
        issues.append("BACKEND_LOCK_VERSION_INVALID")
    repository = Path.home() / ".m2/repository"
    for section in ("dependencies", "plugins"):
        components = lock.get(section)
        if not isinstance(components, list):
            issues.append(f"BACKEND_LOCK_{section.upper()}_INVALID")
            continue
        coordinates: set[str] = set()
        for component in components:
            if not isinstance(component, dict):
                issues.append(f"BACKEND_LOCK_COMPONENT_INVALID: {section}")
                continue
            coordinate = component.get("coordinate")
            if not isinstance(coordinate, str) or len(coordinate.split(":")) != 3:
                issues.append(f"BACKEND_LOCK_COORDINATE_INVALID: {coordinate}")
                continue
            if coordinate in coordinates:
                issues.append(f"BACKEND_LOCK_COORDINATE_DUPLICATE: {coordinate}")
            coordinates.add(coordinate)
            if DYNAMIC.search(coordinate):
                issues.append(f"BACKEND_LOCK_DYNAMIC_VERSION: {coordinate}")
            group, artifact, version = coordinate.split(":")
            relative = Path(*group.split(".")) / artifact / version / f"{artifact}-{version}.jar"
            expected_uri = CENTRAL + relative.as_posix()
            if component.get("sourceUri") != expected_uri:
                issues.append(f"BACKEND_LOCK_SOURCE_URI_MISMATCH: {coordinate}")
            path = repository / relative
            if not path.is_file():
                issues.append(f"BACKEND_LOCK_LOCAL_ARTIFACT_MISSING: {coordinate}")
            elif component.get("binarySha256") != _sha256(path):
                issues.append(f"BACKEND_LOCK_BINARY_SHA256_MISMATCH: {coordinate}")
    wrapper = lock.get("wrapper", {})
    if wrapper != {
        "coordinate": WRAPPER_COORDINATE,
        "sourceUri": WRAPPER_SOURCE,
        "binarySha256": WRAPPER_SHA256,
    }:
        issues.append("BACKEND_LOCK_WRAPPER_MISMATCH")
    resolution = lock.get("pluginResolution")
    extensions = lock.get("extensions")
    if not isinstance(resolution, list) or not resolution:
        issues.append("BACKEND_LOCK_PLUGIN_RESOLUTION_MISSING")
    else:
        try:
            if resolution != resolve_plugin_graph(project_root):
                issues.append("BACKEND_LOCK_PLUGIN_RESOLUTION_DRIFT")
        except ValueError as error:
            issues.append(str(error))
    if extensions != []:
        issues.append("BACKEND_LOCK_EXTENSION_SET_INVALID")
    runtime_graph = lock.get("runtimeGraph")
    graph_coordinates: set[str] = set()

    def collect_runtime(node: Any) -> None:
        if not isinstance(node, dict):
            return
        values = (node.get("groupId"), node.get("artifactId"), node.get("version"))
        if all(isinstance(value, str) and value for value in values):
            graph_coordinates.add(":".join(values))
        for child in node.get("children", []):
            collect_runtime(child)

    collect_runtime(runtime_graph)
    locked_runtime = {
        item.get("coordinate") for item in lock.get("dependencies", []) if isinstance(item, dict)
    }
    supplement = lock.get("runtimeGraphSupplement")
    if (
        not isinstance(runtime_graph, dict)
        or not isinstance(supplement, list)
        or not locked_runtime.issubset(graph_coordinates | set(supplement))
        or not set(supplement).issubset(locked_runtime)
    ):
        issues.append("BACKEND_LOCK_RUNTIME_GRAPH_INCOMPLETE")
    try:
        build_plugins, managed_plugins = _declared_plugins(project_root)
        expected_build = {"org.springframework.boot:spring-boot-maven-plugin": "4.1.0"}
        expected_managed = {
            f"{group}:{artifact}": version
            for group, artifact, version in BUILD_PLUGINS
            if group == "org.apache.maven.plugins"
        }
        expected_managed["org.apache.maven.plugins:maven-wrapper-plugin"] = "3.3.4"
        if build_plugins != expected_build:
            issues.append("BACKEND_LOCK_BUILD_PLUGIN_DECLARATION_DRIFT")
        if managed_plugins != expected_managed:
            issues.append("BACKEND_LOCK_PLUGIN_MANAGEMENT_DECLARATION_DRIFT")
    except (OSError, ElementTree.ParseError):
        issues.append("BACKEND_LOCK_POM_UNREADABLE")
    try:
        properties = _wrapper_properties(project_root)
        if properties.get("distributionUrl") != WRAPPER_SOURCE:
            issues.append("BACKEND_LOCK_WRAPPER_SOURCE_DRIFT")
        if properties.get("distributionSha256Sum") != WRAPPER_SHA256:
            issues.append("BACKEND_LOCK_WRAPPER_SHA256_DRIFT")
    except OSError:
        issues.append("BACKEND_LOCK_WRAPPER_PROPERTIES_UNREADABLE")
    jar = project_root / "backend/target/scholarsense-backend.jar"
    if jar.is_file():
        try:
            actual = generate_backend_lock(project_root)
            if lock.get("dependencies") != actual["dependencies"]:
                issues.append("BACKEND_LOCK_RUNTIME_DEPENDENCY_DRIFT")
            if lock.get("plugins") != actual["plugins"]:
                issues.append("BACKEND_LOCK_PLUGIN_DRIFT")
        except ValueError as error:
            issues.append(str(error))
    return sorted(set(issues))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: backend_lock.py PLUGIN_RESOLUTION_OUTPUT")
    print(json.dumps(plugin_resolution_graph(Path(sys.argv[1]).read_text(), Path.home() / ".m2/repository"), sort_keys=True))
