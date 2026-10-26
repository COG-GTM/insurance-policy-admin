import os
import subprocess
import sys

import pytest

COMPAT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in (COMPAT, os.path.join(COMPAT, "api")):
    if p not in sys.path:
        sys.path.insert(0, p)


def _git(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True)


class Repo:
    """Throwaway git repo: `base` commit on main, working changes on a feature branch."""

    def __init__(self, root):
        self.root = str(root)

    def write(self, path, text):
        full = os.path.join(self.root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as fh:
            fh.write(text)

    def remove(self, path):
        _git(self.root, "rm", "-q", path)

    def commit(self, msg="change"):
        _git(self.root, "add", "-A")
        _git(self.root, "commit", "-q", "--allow-empty", "-m", msg)

    def branch(self, name="feature"):
        _git(self.root, "checkout", "-q", "-b", name)


@pytest.fixture
def repo(tmp_path, monkeypatch):
    import common

    _git(tmp_path, "init", "-q", "-b", "main")
    _git(tmp_path, "config", "user.email", "test@example.com")
    _git(tmp_path, "config", "user.name", "test")
    _git(tmp_path, "config", "commit.gpgsign", "false")
    monkeypatch.setattr(common, "ROOT", str(tmp_path))
    monkeypatch.setattr(common, "BASE_REF", "main")
    monkeypatch.setattr(common, "ALLOWED_FILE", str(tmp_path / "compat" / "allowed-changes.json"))
    monkeypatch.delenv("GITHUB_STEP_SUMMARY", raising=False)
    return Repo(tmp_path)


@pytest.fixture(autouse=True)
def _no_step_summary(monkeypatch):
    monkeypatch.delenv("GITHUB_STEP_SUMMARY", raising=False)
