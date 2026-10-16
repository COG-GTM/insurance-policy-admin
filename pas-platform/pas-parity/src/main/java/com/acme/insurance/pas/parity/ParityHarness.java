package com.acme.insurance.pas.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs a {@link ParityAdapter} against the fixtures and COBOL golden CSVs in {@code
 * parity/programs/<program>/} and reports every difference.
 *
 * <p>The parity directory is located via the {@code pas.parity.dir} system property, falling back
 * to walking up from {@code user.dir} until a {@code parity/programs} directory is found.
 */
public final class ParityHarness {

  /** Outcome of a single program run. */
  public record ParityReport(
      String program,
      int cases,
      int fieldsCompared,
      int matched,
      int knownDifferencesApplied,
      List<String> mismatches,
      List<String> failures,
      Path reportPath) {

    public ParityReport {
      mismatches = List.copyOf(mismatches);
      failures = List.copyOf(failures);
    }

    public boolean ok() {
      return mismatches.isEmpty() && failures.isEmpty();
    }
  }

  private record KnownDifference(
      String program,
      String caseId,
      String field,
      String cobol,
      String java,
      String quirk,
      String decision) {}

  private static final Pattern JIRA_DECISION =
      Pattern.compile("^https://cog-gtm\\.atlassian\\.net/browse/AB-517([/?#].*)?$");
  private static final Pattern DOC_DECISION = Pattern.compile("^docs/modernization/\\S+$");

  private ParityHarness() {}

  /** Locates the repository {@code parity/} directory. */
  static Path locateParityDir() {
    String prop = System.getProperty("pas.parity.dir");
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      if (Files.isDirectory(dir.resolve("parity/programs"))) {
        return dir.resolve("parity");
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("could not locate parity/ directory; set -Dpas.parity.dir");
  }

  public static ParityReport run(ParityAdapter adapter) {
    return run(adapter, locateParityDir());
  }

  public static void assertParity(ParityAdapter adapter) {
    ParityReport report = run(adapter);
    if (!report.ok()) {
      StringBuilder sb =
          new StringBuilder("parity check failed for ").append(adapter.program()).append(":\n");
      report.failures().forEach(f -> sb.append("  ").append(f).append('\n'));
      report.mismatches().forEach(m -> sb.append("  ").append(m).append('\n'));
      sb.append("report: ").append(report.reportPath());
      throw new AssertionError(sb.toString());
    }
  }

  static ParityReport run(ParityAdapter adapter, Path parityDir) {
    String program = adapter.program();
    Path progDir = parityDir.resolve("programs").resolve(program);
    List<String> failures = new ArrayList<>();
    List<String> mismatches = new ArrayList<>();
    int fieldsCompared = 0;
    int matched = 0;
    int knownApplied = 0;

    List<List<String>> fixtureRows = readCsv(progDir.resolve("fixtures.csv"));
    List<String> fixtureHeader = fixtureRows.get(0);
    int noteIdx = fixtureHeader.indexOf("note");

    List<List<String>> goldenRows = readCsv(progDir.resolve("golden.csv"));
    List<String> goldenHeader = goldenRows.get(0);
    List<String> outputCols = goldenHeader.subList(1, goldenHeader.size());

    List<ParityCase> cases = new ArrayList<>();
    List<List<String>> goldenData = goldenRows.subList(1, goldenRows.size());
    for (List<String> row : fixtureRows.subList(1, fixtureRows.size())) {
      Map<String, String> inputs = new LinkedHashMap<>();
      for (int i = 0; i < fixtureHeader.size(); i++) {
        if (i == 0 || i == noteIdx) {
          continue;
        }
        inputs.put(fixtureHeader.get(i), i < row.size() ? row.get(i) : "");
      }
      cases.add(new ParityCase(row.get(0), noteIdx >= 0 ? row.get(noteIdx) : "", inputs));
    }

    List<String> goldenIds = new ArrayList<>();
    for (List<String> row : goldenData) {
      goldenIds.add(row.get(0));
    }
    List<String> fixtureIds = cases.stream().map(ParityCase::caseId).toList();
    if (!fixtureIds.equals(goldenIds)) {
      failures.add(
          "fixture case_ids differ from golden case_ids: fixtures="
              + fixtureIds
              + " golden="
              + goldenIds);
    }

    List<KnownDifference> known = readKnownDifferences(parityDir, program, failures);
    boolean[] knownUsed = new boolean[known.size()];

    int n = Math.min(cases.size(), goldenData.size());
    for (int i = 0; i < n; i++) {
      ParityCase pc = cases.get(i);
      List<String> golden = goldenData.get(i);
      Map<String, String> goldenMap = new LinkedHashMap<>();
      for (int c = 0; c < outputCols.size(); c++) {
        goldenMap.put(outputCols.get(c), c + 1 < golden.size() ? golden.get(c + 1) : "");
      }
      Map<String, String> actual;
      try {
        actual = adapter.run(pc);
      } catch (RuntimeException e) {
        failures.add(pc.caseId() + ": adapter threw " + e);
        continue;
      }
      if (actual == null) {
        actual = Map.of();
      }
      for (String extra : actual.keySet()) {
        if (!goldenMap.containsKey(extra)) {
          failures.add(pc.caseId() + "/" + extra + ": unexpected output column");
        }
      }
      for (Map.Entry<String, String> e : goldenMap.entrySet()) {
        fieldsCompared++;
        String cobol = e.getValue();
        String java = actual.get(e.getKey());
        if (java == null) {
          mismatches.add(pc.caseId() + "/" + e.getKey() + ": cobol=<" + cobol + "> java=<missing>");
          continue;
        }
        if (java.equals(cobol)) {
          matched++;
          continue;
        }
        boolean applied = false;
        for (int k = 0; k < known.size(); k++) {
          KnownDifference kd = known.get(k);
          if (kd.caseId().equals(pc.caseId())
              && kd.field().equals(e.getKey())
              && kd.cobol().equals(cobol)
              && kd.java().equals(java)) {
            knownUsed[k] = true;
            knownApplied++;
            applied = true;
            break;
          }
        }
        if (!applied) {
          mismatches.add(
              pc.caseId() + "/" + e.getKey() + ": cobol=<" + cobol + "> java=<" + java + ">");
        }
      }
      // stale known differences for this case
      for (int k = 0; k < known.size(); k++) {
        KnownDifference kd = known.get(k);
        if (kd.caseId().equals(pc.caseId()) && !knownUsed[k]) {
          if (!goldenMap.containsKey(kd.field())) {
            failures.add("stale known difference: unknown field " + kd.caseId() + "/" + kd.field());
          } else {
            String cobol = goldenMap.get(kd.field());
            String java = actual.get(kd.field());
            if (!kd.cobol().equals(cobol) || !kd.java().equals(java)) {
              failures.add(
                  "stale known difference "
                      + kd.caseId()
                      + "/"
                      + kd.field()
                      + ": golden=<"
                      + cobol
                      + "> java=<"
                      + java
                      + ">");
            }
          }
        }
      }
    }
    for (int k = 0; k < known.size(); k++) {
      if (!knownUsed[k] && fixtureIds.contains(known.get(k).caseId()) == false) {
        failures.add("stale known difference: unknown case " + known.get(k).caseId());
      }
    }

    Path reportPath =
        Path.of(System.getProperty("user.dir"), "target", "parity", program + "-report.txt");
    writeReport(
        reportPath,
        program,
        cases.size(),
        fieldsCompared,
        matched,
        knownApplied,
        mismatches,
        failures);
    return new ParityReport(
        program,
        cases.size(),
        fieldsCompared,
        matched,
        knownApplied,
        mismatches,
        failures,
        reportPath);
  }

  private static List<KnownDifference> readKnownDifferences(
      Path parityDir, String program, List<String> failures) {
    Path file = parityDir.resolve("known-differences.json");
    List<KnownDifference> out = new ArrayList<>();
    if (!Files.exists(file)) {
      return out;
    }
    try {
      JsonNode root = new ObjectMapper().readTree(file.toFile());
      for (JsonNode n : root) {
        if (!program.equals(n.path("program").asText())) {
          continue;
        }
        String quirk = n.path("quirk").asText("");
        String decision = n.path("decision").asText("");
        if (quirk.isBlank()) {
          failures.add(
              "known difference "
                  + n.path("case_id").asText()
                  + "/"
                  + n.path("field").asText()
                  + " has a blank quirk");
        }
        if (!JIRA_DECISION.matcher(decision).matches()
            && !DOC_DECISION.matcher(decision).matches()) {
          failures.add(
              "known difference "
                  + n.path("case_id").asText()
                  + "/"
                  + n.path("field").asText()
                  + " has an invalid decision: "
                  + decision);
        }
        out.add(
            new KnownDifference(
                program,
                n.path("case_id").asText(""),
                n.path("field").asText(""),
                n.path("cobol").asText(""),
                n.path("java").asText(""),
                quirk,
                decision));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("reading " + file, e);
    }
    return out;
  }

  private static void writeReport(
      Path path,
      String program,
      int cases,
      int fieldsCompared,
      int matched,
      int knownApplied,
      List<String> mismatches,
      List<String> failures) {
    StringBuilder sb = new StringBuilder();
    sb.append("program: ").append(program).append('\n');
    sb.append("cases: ").append(cases).append('\n');
    sb.append("fields compared: ").append(fieldsCompared).append('\n');
    sb.append("matched: ").append(matched).append('\n');
    sb.append("known differences applied: ").append(knownApplied).append('\n');
    sb.append("mismatches: ").append(mismatches.size()).append('\n');
    mismatches.forEach(m -> sb.append("  ").append(m).append('\n'));
    failures.forEach(f -> sb.append("  FAILURE: ").append(f).append('\n'));
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, sb.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("writing " + path, e);
    }
  }

  /** Minimal CSV reader handling quoted fields (as written by python csv). */
  static List<List<String>> readCsv(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException("reading " + file, e);
    }
    List<List<String>> rows = new ArrayList<>();
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      List<String> row = new ArrayList<>();
      StringBuilder cur = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (quoted) {
          if (ch == '"') {
            if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
              cur.append('"');
              i++;
            } else {
              quoted = false;
            }
          } else {
            cur.append(ch);
          }
        } else if (ch == '"') {
          quoted = true;
        } else if (ch == ',') {
          row.add(cur.toString());
          cur.setLength(0);
        } else {
          cur.append(ch);
        }
      }
      row.add(cur.toString());
      rows.add(row);
    }
    return rows;
  }
}
