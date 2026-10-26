import check_security as sec

# Trigger strings are assembled at runtime so this file's own added lines
# don't trip the security gate when it scans this PR.
PW = "pass" + "word"
SECRET_RULE = "security:hardcoded-" + "secret"
FTP_URL = "ftp" + "://"
PRIVATE_KEY = "-----BEGIN RSA PRIVATE" + " KEY-----"
ACTUATOR_OFF = "management.security.enabled" + "=false"
ACTUATOR_ALL = "management.endpoints.web.exposure.include" + "=*"
H2_ON = "spring.h2.console.enabled" + "=true"
FTP_STEP = "//STEP1 EXEC PGM" + "=FTP,PARM='host'"
DB_SECRET = f"spring.datasource.{PW}" + "=Sup3rS3cret\n"


def run(repo, capsys, files, base=None):
    repo.write("README.md", "base\n")
    for p, t in (base or {}).items():
        repo.write(p, t)
    repo.commit("base")
    repo.branch()
    for p, t in files.items():
        repo.write(p, t)
    repo.commit()
    rc = sec.main()
    return rc, capsys.readouterr().out


def test_added_lines_yields_only_added_lines_per_file(repo):
    repo.write("a.txt", "keep\nold\n")
    repo.commit("base")
    repo.branch()
    repo.write("a.txt", "keep\nnew\n")
    repo.write("b.txt", "fresh\n")
    repo.commit()
    assert sorted(sec.added_lines()) == [("a.txt", "new"), ("b.txt", "fresh")]


def test_added_lines_skips_deleted_files_and_the_checker_itself(repo):
    repo.write("gone.txt", "x\n")
    repo.commit("base")
    repo.branch()
    repo.remove("gone.txt")
    repo.write("compat/check_security.py", PW + "=hunter2secret\n")
    repo.commit()
    assert list(sec.added_lines()) == []


def test_clean_change_passes(repo, capsys):
    rc, out = run(repo, capsys, {"src/main/java/A.java": "class A {}\n"})
    assert rc == 0
    assert "== Security non-regression: PASS" in out


def test_private_key_fails_everywhere_including_tests(repo, capsys):
    rc, out = run(repo, capsys, {"svc/src/test/resources/key.pem": PRIVATE_KEY + "\n"})
    assert rc == 1
    assert "FAIL      security:private-key:svc/src/test/resources/key.pem" in out


def test_hardcoded_secret_fails_in_main_config(repo, capsys):
    rc, out = run(repo, capsys, {"svc/src/main/resources/application.properties": DB_SECRET})
    assert rc == 1
    assert f"{SECRET_RULE}:svc/src/main/resources/application.properties" in out


def test_hardcoded_secret_variants_are_detected(repo, capsys):
    rc, out = run(repo, capsys, {"app.yml": "api_key" + ': "abcdef123"\n' + "TOKEN" + ' = "zzzzzz9"\n'})
    assert rc == 1
    assert out.count(f"{SECRET_RULE}:app.yml") == 2


def test_placeholders_and_short_values_are_not_secrets(repo, capsys):
    props = f"spring.datasource.{PW}=${{DB2_PASSWORD}}\ndb.{PW}=changeme\ntoken=abc\n"
    rc, _out = run(repo, capsys, {"svc/src/main/resources/application.properties": props})
    assert rc == 0


def test_secret_in_local_profile_or_tests_is_ignored(repo, capsys):
    rc, _out = run(repo, capsys, {
        "svc/src/main/resources/application-local.properties": PW + "=localonly1\n",
        "svc/src/test/java/T.java": f'String {PW} = "testpass1";\n',
    })
    assert rc == 0


def test_actuator_exposure_fails_outside_local(repo, capsys):
    rc, out = run(repo, capsys, {"svc/src/main/resources/application.properties":
                                 ACTUATOR_OFF + "\n" + ACTUATOR_ALL + "\n"})
    assert rc == 1
    assert out.count("security:actuator-exposed:svc/src/main/resources/application.properties") == 2


def test_actuator_exposure_allowed_in_local_profile(repo, capsys):
    rc, _ = run(repo, capsys, {"svc/src/main/resources/application-local.properties": ACTUATOR_OFF + "\n"})
    assert rc == 0


def test_h2_console_fails_outside_local(repo, capsys):
    rc, out = run(repo, capsys, {"svc/src/main/resources/application.properties": H2_ON + "\n"})
    assert rc == 1
    assert "security:h2-console-enabled" in out


def test_plaintext_ftp_fails(repo, capsys):
    rc, out = run(repo, capsys, {"jcl/X.jcl": FTP_STEP + "\n", "doc.md": "see " + FTP_URL + "files.acme\n"})
    assert rc == 1
    assert "security:plaintext-ftp:jcl/X.jcl" in out
    assert "security:plaintext-ftp:doc.md" in out


def test_sftp_program_is_not_plaintext_ftp(repo, capsys):
    rc, _ = run(repo, capsys, {"jcl/X.jcl": "//STEP1 EXEC PGM=FTPS\n"})
    assert rc == 0


def test_pii_field_is_note_only_and_skipped_in_schema(repo, capsys):
    rc, out = run(repo, capsys, {
        "svc/src/main/java/Dto.java": "private String taxId;\nString date_of_birth;\n",
        "sql/ddl/t.sql": "TAX_ID CHAR(9),\n",
    })
    assert rc == 0
    assert "NOTE      security:pii-field:svc/src/main/java/Dto.java: String date_of_birth;" in out
    assert "sql/ddl/t.sql" not in out


def test_only_added_lines_are_scanned(repo, capsys):
    base_line = DB_SECRET
    rc, _out = run(repo, capsys, {"app.properties": base_line + "server.port=8080\n"}, base={"app.properties": base_line})
    assert rc == 0


def test_long_lines_are_truncated_in_report(repo, capsys):
    rc, out = run(repo, capsys, {"a.txt": FTP_URL + "x" * 300 + "\n"})
    assert rc == 1
    line = next(ln for ln in out.splitlines() if "plaintext-ftp" in ln)
    assert line.endswith("x" * (120 - len(FTP_URL)))
