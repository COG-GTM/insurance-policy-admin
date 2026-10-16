package com.acme.insurance.pas.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParityHarnessTest {

  @TempDir Path parityDir;

  private Path progDir;

  @BeforeEach
  void setUp() throws IOException {
    progDir = parityDir.resolve("programs").resolve("demo");
    Files.createDirectories(progDir);
    Files.writeString(
        progDir.resolve("fixtures.csv"),
        "case_id,note,policy_type,effective_date,expiry_date\n"
            + "CASE-1,first,AUT,20240101,20250101\n"
            + "CASE-2,second,HOM,20240301,20250301\n");
    Files.writeString(
        progDir.resolve("golden.csv"), "case_id,total_premium\nCASE-1,904.75\nCASE-2,1267.00\n");
    Files.writeString(parityDir.resolve("known-differences.json"), "[]");
  }

  private ParityAdapter adapter(Map<String, String> case1, Map<String, String> case2) {
    return new ParityAdapter() {
      public String program() {
        return "demo";
      }

      public Map<String, String> run(ParityCase c) {
        return c.caseId().equals("CASE-1") ? case1 : case2;
      }
    };
  }

  @Test
  void allMatchPasses() {
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.75"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.ok());
    assertEquals(2, report.fieldsCompared());
    assertEquals(2, report.matched());
    assertTrue(Files.exists(report.reportPath()));
  }

  @Test
  void oneCentMismatchReported() {
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.76"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertEquals(1, report.mismatches().size());
    assertTrue(
        report.mismatches().get(0).contains("CASE-1/total_premium: cobol=<904.75> java=<904.76>"));
  }

  @Test
  void knownDifferenceApplied() throws IOException {
    Files.writeString(
        parityDir.resolve("known-differences.json"),
        "[{\"program\":\"demo\",\"case_id\":\"CASE-1\",\"field\":\"total_premium\","
            + "\"cobol\":\"904.75\",\"java\":\"904.76\",\"quirk\":\"cent rounding\","
            + "\"decision\":\"https://cog-gtm.atlassian.net/browse/AB-517\","
            + "\"rationale\":\"accepted\"}]");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.76"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.ok());
    assertEquals(1, report.knownDifferencesApplied());
  }

  @Test
  void knownDifferenceWithoutValidDecisionRejected() throws IOException {
    Files.writeString(
        parityDir.resolve("known-differences.json"),
        "[{\"program\":\"demo\",\"case_id\":\"CASE-1\",\"field\":\"total_premium\","
            + "\"cobol\":\"904.75\",\"java\":\"904.76\",\"quirk\":\"cent rounding\","
            + "\"decision\":\"lgtm\",\"rationale\":\"accepted\"}]");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.76"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.failures().stream().anyMatch(f -> f.contains("invalid decision")));
  }

  @Test
  void ab5170DecisionRejected() throws IOException {
    Files.writeString(
        parityDir.resolve("known-differences.json"),
        "[{\"program\":\"demo\",\"case_id\":\"CASE-1\",\"field\":\"total_premium\","
            + "\"cobol\":\"904.75\",\"java\":\"904.76\",\"quirk\":\"cent rounding\","
            + "\"decision\":\"https://cog-gtm.atlassian.net/browse/AB-5170\","
            + "\"rationale\":\"accepted\"}]");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.76"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.failures().stream().anyMatch(f -> f.contains("invalid decision")));
  }

  @Test
  void staleKnownDifferenceFails() throws IOException {
    Files.writeString(
        parityDir.resolve("known-differences.json"),
        "[{\"program\":\"demo\",\"case_id\":\"CASE-1\",\"field\":\"total_premium\","
            + "\"cobol\":\"904.75\",\"java\":\"999.00\",\"quirk\":\"old\","
            + "\"decision\":\"https://cog-gtm.atlassian.net/browse/AB-517\","
            + "\"rationale\":\"accepted\"}]");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.75"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.failures().stream().anyMatch(f -> f.contains("stale known difference")));
  }

  @Test
  void missingColumnFails() {
    ParityHarness.ParityReport report =
        ParityHarness.run(adapter(Map.of(), Map.of("total_premium", "1267.00")), parityDir);
    assertTrue(report.mismatches().get(0).contains("java=<missing>"));
  }

  @Test
  void caseIdMismatchFails() throws IOException {
    Files.writeString(
        progDir.resolve("golden.csv"), "case_id,total_premium\nCASE-1,904.75\nOTHER,1267.00\n");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.75"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.failures().stream().anyMatch(f -> f.contains("case_ids differ")));
  }

  @Test
  void multilineQuotedFieldInFixtures() throws IOException {
    Files.writeString(
        progDir.resolve("fixtures.csv"),
        "case_id,note,policy_type,effective_date,expiry_date\n"
            + "CASE-1,\"first line\nsecond line\",AUT,20240101,20250101\n"
            + "CASE-2,second,HOM,20240301,20250301\n");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.75"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.ok());
    assertEquals(2, report.cases());
  }

  @Test
  void equalValuesKnownDifferenceRejected() throws IOException {
    Files.writeString(
        parityDir.resolve("known-differences.json"),
        "[{\"program\":\"demo\",\"case_id\":\"CASE-1\",\"field\":\"total_premium\","
            + "\"cobol\":\"904.75\",\"java\":\"904.75\",\"quirk\":\"same\","
            + "\"decision\":\"https://cog-gtm.atlassian.net/browse/AB-517\","
            + "\"rationale\":\"accepted\"}]");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.75"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(report.failures().stream().anyMatch(f -> f.contains("identical cobol and java")));
  }

  @Test
  void knownDifferenceForFieldThatNowMatchesIsStale() throws IOException {
    Files.writeString(
        parityDir.resolve("known-differences.json"),
        "[{\"program\":\"demo\",\"case_id\":\"CASE-1\",\"field\":\"total_premium\","
            + "\"cobol\":\"904.76\",\"java\":\"904.77\",\"quirk\":\"cent rounding\","
            + "\"decision\":\"https://cog-gtm.atlassian.net/browse/AB-517\","
            + "\"rationale\":\"accepted\"}]");
    ParityHarness.ParityReport report =
        ParityHarness.run(
            adapter(Map.of("total_premium", "904.75"), Map.of("total_premium", "1267.00")),
            parityDir);
    assertTrue(
        report.failures().stream()
            .anyMatch(
                f -> f.contains("stale known difference") && f.contains("values no longer match")));
  }

  @Test
  void assertParityThrowsListingMismatch() {
    System.setProperty("pas.parity.dir", parityDir.toString());
    try {
      AssertionError err =
          assertThrows(
              AssertionError.class,
              () ->
                  ParityHarness.assertParity(
                      adapter(
                          Map.of("total_premium", "904.76"), Map.of("total_premium", "1267.00"))));
      assertTrue(err.getMessage().contains("CASE-1/total_premium"));
    } finally {
      System.clearProperty("pas.parity.dir");
    }
  }
}
