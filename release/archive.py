#!/usr/bin/env python3
"""Deterministic archive primitives for release artifacts."""

from __future__ import annotations

import gzip
import io
import os
import tarfile
from pathlib import Path


def create_normalized_tar_gz(source: Path, destination: Path) -> None:
    root = source.resolve()
    if not root.is_dir():
        raise ValueError("ARCHIVE_SOURCE_DIRECTORY_REQUIRED")
    entries = sorted(root.rglob("*"), key=lambda path: path.relative_to(root).as_posix())
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for path in entries:
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                raise ValueError(f"ARCHIVE_SYMLINK_FORBIDDEN: {relative}")
            info = tarfile.TarInfo(relative)
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            info.mtime = 0
            info.pax_headers = {}
            if path.is_dir():
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                archive.addfile(info)
            elif path.is_file():
                payload = path.read_bytes()
                info.type = tarfile.REGTYPE
                info.mode = 0o644
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
            else:
                raise ValueError(f"ARCHIVE_ENTRY_TYPE_FORBIDDEN: {relative}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.tmp")
    try:
        with temporary.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as compressed:
                compressed.write(buffer.getvalue())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
