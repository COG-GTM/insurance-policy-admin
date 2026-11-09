"""Test helpers for the compat gate: import paths and throwaway git repos."""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
COMPAT = os.path.dirname(HERE)
for p in (COMPAT, os.path.join(COMPAT, "api")):
    if p not in sys.path:
        sys.path.insert(0, p)


def run_git(cwd, *args):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.com",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.com")
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True, env=env).stdout


def write(root, path, text):
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as fh:
        fh.write(text)


def make_repo(base_files, head_files=None, removed=()):
    """Create a repo whose `base` branch holds base_files and whose HEAD adds
    head_files and deletes `removed` on top. Returns a TemporaryDirectory."""
    tmp = tempfile.TemporaryDirectory()
    root = tmp.name
    run_git(root, "init", "-q", "-b", "base")
    for path, text in base_files.items():
        write(root, path, text)
    run_git(root, "add", "-A")
    run_git(root, "commit", "-q", "--allow-empty", "-m", "base")
    run_git(root, "checkout", "-q", "-b", "feature")
    for path, text in (head_files or {}).items():
        write(root, path, text)
    for path in removed:
        run_git(root, "rm", "-q", path)
    run_git(root, "add", "-A")
    run_git(root, "commit", "-q", "--allow-empty", "-m", "head")
    return tmp


def cpy(*lines):
    """Format copybook statements into fixed-format COBOL (area A at column 8)."""
    return "\n".join("       " + l for l in lines) + "\n"
