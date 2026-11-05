package com.acme.insurance.pas.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.parity.ParityComparator.Difference;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParityComparatorTest {

  private static Effect premium(String total) {
    return Effect.builder("SQL INSERT PREMIUMS")
        .sqlcode(0)
        .text("POLICY_NUMBER", "POL-00000001")
        .number("TOTAL_PREMIUM", total)
        .probe("WS-MOD-PREMIUM", "850.00")
        .build();
  }

  @Test
  void equalRunsHaveNoDifferences() {
    RunResult golden = RunResult.completed(0, List.of(premium("904.75")));
    RunResult actual =
        RunResult.completed(
            0,
            List.of(
                Effect.builder("SQL INSERT PREMIUMS")
                    .sqlcode(0)
                    .text("POLICY_NUMBER", "POL-00000001   ")
                    .number("TOTAL_PREMIUM", "904.750")
                    .probe("WS-MOD-PREMIUM", "850.0000")
                    .build()));

    assertThat(ParityComparator.compare(golden, actual)).isEmpty();
  }

  @Test
  void aOneCentDifferenceFails() {
    List<Difference> differences =
        ParityComparator.compare(
            RunResult.completed(0, List.of(premium("904.75"))),
            RunResult.completed(0, List.of(premium("904.76"))));

    assertThat(differences)
        .containsExactly(new Difference("effects[0].numbers.TOTAL_PREMIUM", "904.75", "904.76"));
  }

  @Test
  void missingFieldsEffectsAndOutcomeAreDifferences() {
    RunResult golden = RunResult.completed(4, List.of(premium("1.00"), premium("2.00")));
    RunResult actual =
        RunResult.completed(0, List.of(Effect.builder("SQL INSERT PREMIUMS").sqlcode(0).build()));

    assertThat(ParityComparator.compare(golden, actual))
        .extracting(Difference::path)
        .containsExactly(
            "outcome.return_code",
            "effects.count",
            "effects[0].text.POLICY_NUMBER",
            "effects[0].numbers.TOTAL_PREMIUM",
            "effects[0].probes.WS-MOD-PREMIUM");
  }

  @Test
  void aProbeValueDifferenceFails() {
    RunResult golden = RunResult.completed(0, List.of(premium("1.00")));
    RunResult actual =
        RunResult.completed(
            0,
            List.of(
                Effect.builder("SQL INSERT PREMIUMS")
                    .sqlcode(0)
                    .text("POLICY_NUMBER", "POL-00000001")
                    .number("TOTAL_PREMIUM", "1.00")
                    .probe("WS-MOD-PREMIUM", "850.01")
                    .build()));

    assertThat(ParityComparator.compare(golden, actual))
        .extracting(Difference::path)
        .containsExactly("effects[0].probes.WS-MOD-PREMIUM");
  }

  @Test
  void aProbeCobolRecordedButJavaLeavesOutIsADifferenceUnlessIgnored() {
    RunResult golden = RunResult.completed(0, List.of(premium("1.00")));
    RunResult actual =
        RunResult.completed(
            0,
            List.of(
                Effect.builder("SQL INSERT PREMIUMS")
                    .sqlcode(0)
                    .text("POLICY_NUMBER", "POL-00000001")
                    .number("TOTAL_PREMIUM", "1.00")
                    .build()));

    assertThat(ParityComparator.compare(golden, actual))
        .containsExactly(new Difference("effects[0].probes.WS-MOD-PREMIUM", "850.00", null));
    assertThat(ParityComparator.compare(golden, actual, Set.of("WS-MOD-PREMIUM"))).isEmpty();
  }

  @Test
  void knownDifferenceGlobsCoverPathsAndCases() {
    KnownDifference kd =
        new KnownDifference(
            "KD-05",
            "undwrt-decision",
            List.of("branch-accumulation-exceeded"),
            List.of("effects[*].text.DECISION_REASON"),
            "Q-16",
            "docs/modernization/quirk-register.md#q-16",
            "AB-525",
            "pending",
            "reason");

    assertThat(
            kd.covers(
                "undwrt-decision",
                "branch-accumulation-exceeded",
                "effects[0].text.DECISION_REASON"))
        .isTrue();
    assertThat(
            kd.covers(
                "undwrt-decision", "branch-accumulation-exceeded", "effects[0].numbers.RISK_SCORE"))
        .isFalse();
    assertThat(kd.covers("undwrt-decision", "claims-four", "effects[0].text.DECISION_REASON"))
        .isFalse();
    assertThat(kd.approved()).isFalse();
  }

  @Test
  void allCasesKnownDifferenceAndApproval() {
    KnownDifference kd =
        new KnownDifference(
            "KD-02",
            "polrnw-renewal",
            List.of("*"),
            List.of("outcome.status", "effects[1].*"),
            "Q-41",
            "docs/modernization/quirk-register.md#q-41",
            "AB-526",
            "approved: Product Owner, 2026-11-01, PAS-SO-12",
            "reason");

    assertThat(kd.covers("polrnw-renewal", "any-case", "outcome.status")).isTrue();
    assertThat(kd.covers("polrnw-renewal", "any-case", "effects[1].numbers.TOTAL_PREMIUM"))
        .isTrue();
    assertThat(kd.covers("polrnw-renewal", "any-case", "effects[0].numbers.TOTAL_PREMIUM"))
        .isFalse();
    assertThat(kd.covers("polend-endorsement", "any-case", "outcome.status")).isFalse();
    assertThat(kd.approved()).isTrue();
  }
}
