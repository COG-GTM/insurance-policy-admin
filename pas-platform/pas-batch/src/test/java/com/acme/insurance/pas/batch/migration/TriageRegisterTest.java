package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.report.CheckType;
import com.acme.insurance.pas.batch.migration.triage.Classification;
import com.acme.insurance.pas.batch.migration.triage.Difference;
import com.acme.insurance.pas.batch.migration.triage.TriageRegister;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TriageRegisterTest {

  private static final Path QUIRKS =
      ParityFixtures.repoRoot().resolve("docs/modernization/quirk-register.md");

  private static TriageRegister.Entry fix(String id, String quirk, String decision) {
    return new TriageRegister.Entry(
        id,
        "PARALLEL_RUN",
        "PREMIUMS",
        "*",
        "EXTRA_IN_NEW",
        null,
        null,
        null,
        Classification.APPROVED_FIX,
        quirk,
        decision,
        null,
        null,
        "why",
        "Proposed");
  }

  @Test
  void committedRegisterIsValidAndLinksToRealQuirks() throws Exception {
    TriageRegister register =
        TriageRegister.load(
            ParityFixtures.repoRoot().resolve("parity/parallel-run/triage-register.json"), QUIRKS);
    assertThat(register.entries())
        .filteredOn(e -> e.classification() == Classification.APPROVED_FIX)
        .allSatisfy(
            e -> assertThat(e.decision()).startsWith("docs/modernization/quirk-register.md#q-"));
  }

  @Test
  void quirkIdsComeFromRegisterHeadings() throws Exception {
    assertThat(TriageRegister.quirkIds(QUIRKS)).contains("Q-01", "Q-22", "Q-34").hasSize(34);
  }

  @Test
  void approvedFixMustLinkAnExistingMatchingQuirk() throws Exception {
    Set<String> quirks = TriageRegister.quirkIds(QUIRKS);
    String ok = "docs/modernization/quirk-register.md#q-22";
    assertThat(new TriageRegister(List.of(fix("A", "Q-22", ok)), quirks).entries()).hasSize(1);
    assertThatThrownBy(() -> new TriageRegister(List.of(fix("A", null, ok)), quirks))
        .hasMessageContaining("needs quirk Q-nn");
    assertThatThrownBy(
            () ->
                new TriageRegister(
                    List.of(fix("A", "Q-99", "docs/modernization/quirk-register.md#q-99")), quirks))
        .hasMessageContaining("not in the quirk register");
    assertThatThrownBy(() -> new TriageRegister(List.of(fix("A", "Q-21", ok)), quirks))
        .hasMessageContaining("does not match");
    assertThatThrownBy(
            () -> new TriageRegister(List.of(fix("A", "Q-22", "https://example.test/q22")), quirks))
        .hasMessageContaining("decision must be");
  }

  @Test
  void unclassifiedBugWithoutTicketAndDataIssueWithoutOwnerAreRejected() {
    TriageRegister.Entry unclassified =
        new TriageRegister.Entry(
            "U",
            "PARALLEL_RUN",
            "PREMIUMS",
            "*",
            null,
            null,
            null,
            null,
            Classification.UNCLASSIFIED,
            null,
            null,
            null,
            null,
            "why",
            null);
    TriageRegister.Entry bug =
        new TriageRegister.Entry(
            "B",
            "PARALLEL_RUN",
            "PREMIUMS",
            "*",
            null,
            null,
            null,
            null,
            Classification.BUG,
            null,
            null,
            null,
            null,
            "why",
            null);
    TriageRegister.Entry data =
        new TriageRegister.Entry(
            "D",
            "PARALLEL_RUN",
            "PREMIUMS",
            "*",
            null,
            null,
            null,
            null,
            Classification.DATA_ISSUE,
            null,
            null,
            null,
            null,
            "why",
            null);
    assertThatThrownBy(() -> new TriageRegister(List.of(unclassified), Set.of()))
        .hasMessageContaining("must be BUG, APPROVED_FIX or DATA_ISSUE");
    assertThatThrownBy(() -> new TriageRegister(List.of(bug), Set.of()))
        .hasMessageContaining("BUG needs a ticket");
    assertThatThrownBy(() -> new TriageRegister(List.of(data), Set.of()))
        .hasMessageContaining("DATA_ISSUE needs an owner");
  }

  @Test
  void classifiesByScopeKindGlobAndFieldAndCountsUnexplained() {
    TriageRegister register =
        new TriageRegister(
            List.of(
                new TriageRegister.Entry(
                    "B1",
                    "PARALLEL_RUN",
                    "dailyExtractJob/policy_extract.dat",
                    "POL-0001*",
                    "CHANGED",
                    "TOTAL_PREMIUM",
                    null,
                    null,
                    Classification.BUG,
                    null,
                    null,
                    "AB-999",
                    null,
                    "rounding",
                    null),
                new TriageRegister.Entry(
                    "D1",
                    "PARALLEL_RUN",
                    "dailyExtractJob/policy_extract.dat",
                    "*",
                    "MISSING_IN_NEW",
                    null,
                    null,
                    null,
                    Classification.DATA_ISSUE,
                    null,
                    null,
                    null,
                    "Data Migration",
                    "late DB2 change",
                    null)),
            Set.of());
    String scope = "dailyExtractJob/policy_extract.dat";
    List<Difference> diffs =
        register.classifyAll(
            List.of(
                Difference.unclassified(
                    "PARALLEL_RUN",
                    scope,
                    "CHANGED",
                    "POL-00012",
                    List.of("TOTAL_PREMIUM"),
                    "a",
                    "b"),
                Difference.unclassified(
                    "PARALLEL_RUN", scope, "CHANGED", "POL-00012", List.of("AGENT_CODE"), "a", "b"),
                Difference.unclassified(
                    "PARALLEL_RUN", scope, "MISSING_IN_NEW", "X", List.of(), "a", null)));
    assertThat(diffs)
        .extracting(Difference::classification)
        .containsExactly(
            Classification.BUG, Classification.UNCLASSIFIED, Classification.DATA_ISSUE);
    assertThat(diffs.get(0).reference()).isEqualTo("AB-999");

    CheckReport report =
        CheckReport.of(
            "r",
            CheckType.PARALLEL_RUN,
            LocalDate.of(2026, 10, 12),
            Instant.EPOCH,
            Instant.EPOCH,
            true,
            List.of(),
            Map.of(),
            diffs);
    assertThat(report.unexplained()).isEqualTo(2);
    assertThat(report.status()).isEqualTo("FAIL");
    assertThat(
            CheckReport.of(
                    "r",
                    CheckType.PARALLEL_RUN,
                    LocalDate.of(2026, 10, 12),
                    Instant.EPOCH,
                    Instant.EPOCH,
                    false,
                    List.of(),
                    Map.of(),
                    List.of())
                .status())
        .isEqualTo("INCOMPLETE");
  }
}
