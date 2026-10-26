import json

import common


def test_head_and_base_file_track_merge_base(repo):
    repo.write("a.txt", "v1\n")
    repo.commit("base")
    repo.branch()
    repo.write("a.txt", "v2\n")
    repo.write("new.txt", "fresh\n")
    repo.commit()

    assert common.base_file("a.txt") == "v1\n"
    assert common.head_file("a.txt") == "v2\n"
    assert common.base_file("new.txt") is None
    assert common.head_file("missing.txt") is None


def test_files_at_base_and_head_filter_by_prefix(repo):
    repo.write("sql/a.sql", "x")
    repo.write("docs/readme.md", "x")
    repo.commit("base")
    repo.branch()
    repo.write("sql/b.sql", "x")
    repo.commit()

    assert common.files_at_base("sql/") == ["sql/a.sql"]
    assert sorted(common.files_at_head("sql/")) == ["sql/a.sql", "sql/b.sql"]
    assert sorted(common.files_at_base()) == ["docs/readme.md", "sql/a.sql"]


def test_merge_base_is_fork_point_not_main_tip(repo):
    repo.write("a.txt", "v1")
    repo.commit("base")
    fork = common.git("rev-parse", "HEAD").strip()
    repo.branch()
    repo.write("a.txt", "feature")
    repo.commit()
    common.git("checkout", "-q", "main")
    repo.write("b.txt", "main moved on")
    repo.commit("main-only")
    common.git("checkout", "-q", "feature")

    assert common.merge_base() == fork
    assert common.base_file("b.txt") is None


def test_allowed_ids_missing_file_is_empty(repo):
    assert common.allowed_ids() == {}


def test_allowed_ids_indexes_entries_by_id(repo):
    repo.write("compat/allowed-changes.json", json.dumps({"allowed": [
        {"id": "x:1", "ticket": "AB-1", "reason": "r1"},
        {"id": "x:2", "ticket": "AB-2", "reason": "r2"},
    ]}))
    ids = common.allowed_ids()
    assert set(ids) == {"x:1", "x:2"}
    assert ids["x:2"]["ticket"] == "AB-2"


def test_report_pass_with_no_findings(capsys, monkeypatch):
    monkeypatch.setattr(common, "allowed_ids", dict)
    assert common.report("Check", []) == 0
    out = capsys.readouterr().out
    assert "== Check: PASS" in out
    assert "no compatibility-relevant changes found" in out


def test_report_notes_do_not_fail(capsys, monkeypatch):
    monkeypatch.setattr(common, "allowed_ids", dict)
    assert common.report("Check", [("NOTE", "n:1", "additive")]) == 0
    assert "NOTE      n:1: additive" in capsys.readouterr().out


def test_report_unapproved_fail_exits_non_zero(capsys, monkeypatch):
    monkeypatch.setattr(common, "allowed_ids", dict)
    assert common.report("Check", [("FAIL", "f:1", "broke"), ("NOTE", "n:1", "ok")]) == 1
    out = capsys.readouterr().out
    assert "== Check: FAIL" in out
    assert "FAIL      f:1: broke" in out


def test_report_allowed_fail_is_downgraded_to_approved(capsys, monkeypatch):
    monkeypatch.setattr(common, "allowed_ids", lambda: {"f:1": {"id": "f:1", "ticket": "AB-524", "reason": "auth"}})
    assert common.report("Check", [("FAIL", "f:1", "status changed")]) == 0
    out = capsys.readouterr().out
    assert "== Check: PASS" in out
    assert "APPROVED  f:1: status changed (ticket AB-524: auth)" in out


def test_report_allowlist_does_not_cover_other_ids(monkeypatch):
    monkeypatch.setattr(common, "allowed_ids", lambda: {"f:1": {"id": "f:1"}})
    assert common.report("Check", [("FAIL", "f:1", "a"), ("FAIL", "f:2", "b")]) == 1


def test_report_approved_entry_without_ticket_shows_placeholder(capsys, monkeypatch):
    monkeypatch.setattr(common, "allowed_ids", lambda: {"f:1": {"id": "f:1"}})
    common.report("Check", [("FAIL", "f:1", "m")])
    assert "(ticket ?: )" in capsys.readouterr().out


def test_report_appends_markdown_to_github_step_summary(tmp_path, monkeypatch):
    summary = tmp_path / "summary.md"
    summary.write_text("prior\n")
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(summary))
    monkeypatch.setattr(common, "allowed_ids", dict)
    common.report("Schema", [("FAIL", "f:1", "dropped")])
    common.report("Layouts", [])
    text = summary.read_text()
    assert text.startswith("prior\n")
    assert "### Schema: FAIL\n- `FAIL      f:1: dropped`\n" in text
    assert "### Layouts: PASS\n- `no compatibility-relevant changes found`\n" in text


def test_exit_with_raises_system_exit_with_code():
    import pytest
    with pytest.raises(SystemExit) as exc:
        common.exit_with(3)
    assert exc.value.code == 3
