#!/usr/bin/env python3
"""Generate the additive backend dependency lock and schema from resolved bytes."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "release"))

from backend_lock import generate_backend_lock  # noqa: E402


def _coordinates(node: Any) -> set[str]:
    if not isinstance(node, dict):
        return set()
    result = set()
    identity = (node.get("groupId"), node.get("artifactId"), node.get("version"))
    if all(isinstance(value, str) and value for value in identity):
        result.add(":".join(identity))
    for child in node.get("children", []):
        result.update(_coordinates(child))
    return result


def generate(root: Path, runtime_tree: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    predecessor = json.loads((
        root / "contracts/release/backend-lock-1.0.0.json"
    ).read_text(encoding="utf-8"))
    dependency_tree = json.loads(runtime_tree.read_text(encoding="utf-8"))
    generated = generate_backend_lock(root)
    locked = {item["coordinate"] for item in generated["dependencies"]}
    lock = {
        "bootstrapPlugin": predecessor["bootstrapPlugin"],
        "dependencies": generated["dependencies"],
        "extensions": [],
        "pluginResolution": predecessor["pluginResolution"],
        "plugins": generated["plugins"],
        "runtimeGraph": dependency_tree,
        "runtimeGraphSupplement": sorted(locked - _coordinates(dependency_tree)),
        "version": "BACKEND-LOCK-2.0.0",
        "wrapper": generated["wrapper"],
    }
    schema = copy.deepcopy(json.loads((
        root / "contracts/release/backend-lock.schema.json"
    ).read_text(encoding="utf-8")))
    schema["$id"] = (
        "https://scholarsense.suda.edu.cn/contracts/release/"
        "backend-lock-2.schema.json"
    )
    schema["properties"]["version"] = {"const": "BACKEND-LOCK-2.0.0"}
    return lock, schema


def _write(path: Path, value: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-tree", type=Path, required=True)
    parser.add_argument("--lock-output", type=Path, required=True)
    parser.add_argument("--schema-output", type=Path, required=True)
    args = parser.parse_args()
    lock, schema = generate(ROOT, args.runtime_tree.resolve())
    _write(args.lock_output.resolve(), lock)
    _write(args.schema_output.resolve(), schema)
    print(
        f"backend-lock-v2: generated {len(lock['dependencies'])} runtime dependencies"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
