"""Load deployment-mounted key material without following links or exposing weak files."""

from __future__ import annotations

import os
import stat
from pathlib import Path


MINIMUM_KEY_BYTES = 32
MAXIMUM_KEY_BYTES = 4 * 1024


def _protected_directory(metadata: os.stat_result) -> bool:
    permissions = stat.S_IMODE(metadata.st_mode)
    if not permissions & (stat.S_IWGRP | stat.S_IWOTH):
        return True
    return bool(permissions & stat.S_ISVTX) and metadata.st_uid in {
        0,
        os.geteuid(),
    }


def _open_without_link_traversal(path: Path, error_code: str) -> int:
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW"):
        raise ValueError(error_code)
    components = path.parts
    if not components or components[0] != path.anchor or len(components) < 2:
        raise ValueError(error_code)
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | os.O_NOFOLLOW
    )
    directory = -1
    try:
        directory = os.open(path.anchor, directory_flags)
        for component in components[1:-1]:
            next_directory = os.open(
                component,
                directory_flags,
                dir_fd=directory,
            )
            os.close(directory)
            directory = next_directory
            metadata = os.fstat(directory)
            if (
                not stat.S_ISDIR(metadata.st_mode)
                or not _protected_directory(metadata)
            ):
                raise ValueError(error_code)
        return os.open(
            components[-1],
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | os.O_NOFOLLOW,
            dir_fd=directory,
        )
    except ValueError:
        raise
    except OSError as failure:
        raise ValueError(error_code) from failure
    finally:
        if directory >= 0:
            os.close(directory)


def load_protected_key(
    candidate: Path,
    *,
    error_code: str,
    forbidden_root: Path | None = None,
) -> bytes:
    """Apply the same custody boundary used by the Java runtime target-key loader."""
    path = Path(candidate)
    if not path.is_absolute():
        raise ValueError(error_code)
    normalized = Path(os.path.normpath(path))
    if normalized != path:
        raise ValueError(error_code)
    if forbidden_root is not None:
        root = forbidden_root.resolve()
        if normalized == root or root in normalized.parents:
            raise ValueError(error_code)

    descriptor = -1
    try:
        descriptor = _open_without_link_traversal(normalized, error_code)
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(error_code)
        if os.name == "posix" and stat.S_IMODE(metadata.st_mode) & (
            stat.S_IRWXG | stat.S_IRWXO
        ):
            raise ValueError(error_code)
        with os.fdopen(descriptor, "rb", closefd=True) as stream:
            descriptor = -1
            key = stream.read(MAXIMUM_KEY_BYTES + 1)
    except ValueError:
        raise
    except OSError as failure:
        raise ValueError(error_code) from failure
    finally:
        if descriptor >= 0:
            os.close(descriptor)

    if not MINIMUM_KEY_BYTES <= len(key) <= MAXIMUM_KEY_BYTES:
        raise ValueError(error_code)
    return key
