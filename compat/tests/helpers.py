"""Shared fixtures for the compat-gate unit tests (stdlib unittest only)."""

import contextlib
import io
import json
import os
import sys
import tempfile
from unittest import mock

COMPAT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in (COMPAT_DIR, os.path.join(COMPAT_DIR, "api")):
    if p not in sys.path:
        sys.path.insert(0, p)

import common  # noqa: E402


@contextlib.contextmanager
def allowed_changes(entries=()):
    """Point common.ALLOWED_FILE at a temp allow-list and clear GITHUB_STEP_SUMMARY."""
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "allowed-changes.json")
        with open(path, "w") as fh:
            json.dump({"allowed": list(entries)}, fh)
        env = {k: v for k, v in os.environ.items() if k != "GITHUB_STEP_SUMMARY"}
        with mock.patch.object(common, "ALLOWED_FILE", path), mock.patch.dict(os.environ, env, clear=True):
            yield path


def run_quiet(fn, *args, **kwargs):
    """Call fn, returning (result, captured stdout)."""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        result = fn(*args, **kwargs)
    return result, buf.getvalue()


def fake_repo(module, base, head):
    """Patch a check module's from-imported git accessors with in-memory trees.

    base/head: dict path -> file contents (missing key == file absent)."""
    return mock.patch.multiple(
        module,
        files_at_base=lambda prefix="": sorted(p for p in base if p.startswith(prefix)),
        base_file=lambda path: base.get(path),
        head_file=lambda path: head.get(path),
    )
