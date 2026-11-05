package com.acme.insurance.pas.underwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UnderwriterTest {

  static final LocalDate TODAY = LocalDate.of(2025, 3, 1);
  static final BigDecimal LOW_ACCUMULATION = new BigDecimal("10000000.00");

  static UnderwritingSubject subject(int credit, String tier, String premium, String limit) {
    return new UnderwritingSubject(
        "POL-00000101",
        "HOM",
        new BigDecimal(premium),
        new BigDecimal(limit),
        "B001",
        "AG0001",
        "C000000001",
        "I",
        credit,
        tier);
  }

  static RiskAssessment assess(UnderwritingSubject s, ClaimsSummary claims, BigDecimal accum) {
    return new RiskScorer(UnderwritingRules.legacy()).assess(s, claims, accum);
  }

  @ParameterizedTest(name = "credit {0} tier {1} claims {2} incurred {3} -> {4} {5}")
  @CsvSource({
    // credit, tier, claims, incurred, score, code  (threshold boundaries from the AB-520 fixtures)
    "301, S, 0, 0.00, 299, AP",
    "300, S, 0, 0.00, 300, RS",
    "101, U, 0, 0.00, 599, RS",
    "100, U, 0, 0.00, 600, RM",
    "101, U, 1, 900.00, 799, RM",
    "100, U, 1, 900.00, 800, DC",
    "0,   S, 0, 0.00, 400, RS",
    "500, S, 4, 100.00, 350, RS",
    "0,   U, 6, 2000.00, 1150, DC",
  })
  void scoreAndBandMatchUndwrt(
      int credit, String tier, int claims, String incurred, int score, String code) {
    UnderwritingSubject s = subject(credit, tier, "1000.00", "250000.00");
    RiskAssessment a =
        assess(s, new ClaimsSummary(claims, new BigDecimal(incurred)), LOW_ACCUMULATION);
    assertThat(a.riskScore()).isEqualTo(score);
    assertThat(UnderwritingRules.legacy().band(a.riskScore()).status().code()).isEqualTo(code);
  }

  @Test
  void creditDivisionTruncatesLikeCobol() {
    // (900 - 301) / 2 = 299.5 -> 299 (Q-06)
    assertThat(assess(subject(301, "S", "1000.00", "1.00"), ClaimsSummary.NONE, null).riskScore())
        .isEqualTo(299);
  }

  @Test
  void negativeScoresClampAtZeroInsteadOfLosingTheSign() {
    // COBOL stores |25 - 100| = 75 (Q-14); the fix stores 0.
    assertThat(assess(subject(850, "P", "1000.00", "1.00"), ClaimsSummary.NONE, null).riskScore())
        .isZero();
    // Credit above 900 gives a negative base: 0, not |900 - 950| / 2.
    assertThat(assess(subject(950, "S", "1000.00", "1.00"), ClaimsSummary.NONE, null).riskScore())
        .isZero();
  }

  @Test
  void scoresAbove999AreKeptWhole() {
    RiskAssessment a =
        assess(
            subject(0, "U", "1000.00", "10000001.00"),
            new ClaimsSummary(6, new BigDecimal("2000.00")),
            LOW_ACCUMULATION);
    assertThat(a.riskScore()).isEqualTo(1450);
  }

  @Test
  void lossRatioIsNotTruncatedAt1000Percent() {
    RiskAssessment a =
        assess(
            subject(500, "S", "1000.00", "250000.00"),
            new ClaimsSummary(1, new BigDecimal("10500.00")),
            LOW_ACCUMULATION);
    assertThat(a.lossRatio()).isEqualByComparingTo("1050.00");
    assertThat(a.riskScore()).isEqualTo(400); // 200 + 200 surcharge (COBOL: 200, Q-15)
  }

  @Test
  void lossRatioTruncatesToTheCentAndExactly80IsNotSurcharged() {
    assertThat(
            assess(
                    subject(500, "S", "1000.00", "1.00"),
                    new ClaimsSummary(1, new BigDecimal("800.00")),
                    null)
                .riskScore())
        .isEqualTo(200);
    RiskAssessment justAbove =
        assess(
            subject(500, "S", "3.00", "1.00"), new ClaimsSummary(1, new BigDecimal("2.41")), null);
    assertThat(justAbove.lossRatio()).isEqualByComparingTo("80.33");
    assertThat(justAbove.riskScore()).isEqualTo(400);
  }

  @Test
  void zeroPremiumSkipsTheLossRatio() {
    RiskAssessment a =
        assess(
            subject(500, "S", "0.00", "250000.00"),
            new ClaimsSummary(2, new BigDecimal("5000.00")),
            LOW_ACCUMULATION);
    assertThat(a.lossRatio()).isEqualByComparingTo("0.00");
    assertThat(a.riskScore()).isEqualTo(200);
  }

  @Test
  void limitBandsAreStrictlyAbove() {
    assertThat(
            assess(subject(500, "S", "1.00", "5000000.00"), ClaimsSummary.NONE, null).riskScore())
        .isEqualTo(200);
    assertThat(
            assess(subject(500, "S", "1.00", "5000000.01"), ClaimsSummary.NONE, null).riskScore())
        .isEqualTo(300);
    assertThat(
            assess(subject(500, "S", "1.00", "10000000.01"), ClaimsSummary.NONE, null).riskScore())
        .isEqualTo(500);
  }

  @Test
  void claimCountBands() {
    UnderwritingSubject s = subject(500, "S", "0.00", "1.00");
    assertThat(assess(s, new ClaimsSummary(3, BigDecimal.ZERO), null).riskScore()).isEqualTo(200);
    assertThat(assess(s, new ClaimsSummary(4, BigDecimal.ZERO), null).riskScore()).isEqualTo(350);
    assertThat(assess(s, new ClaimsSummary(5, BigDecimal.ZERO), null).riskScore()).isEqualTo(350);
    assertThat(assess(s, new ClaimsSummary(6, BigDecimal.ZERO), null).riskScore()).isEqualTo(550);
  }

  @Test
  void accumulationAddsThreeHundredAndKeepsItsReason() {
    Underwriter underwriter =
        new Underwriter(
            UnderwritingRules.legacy(),
            pn -> Optional.of(subject(500, "S", "1000.00", "250000.00")),
            (pn, from, to) -> ClaimsSummary.NONE,
            branch -> new BigDecimal("500000000.01"));
    RiskDecision d = decided(underwriter.underwrite("POL-00000101", TODAY));
    assertThat(d.riskScore()).isEqualTo(500);
    assertThat(d.decisionCode()).isEqualTo("RS");
    assertThat(d.reason())
        .isEqualTo("REFERRED TO SENIOR UNDERWRITER; ACCUMULATION LIMIT EXCEEDED FOR BRANCH B001");
    assertThat(
            assess(
                    subject(500, "S", "1000.00", "1.00"),
                    ClaimsSummary.NONE,
                    new BigDecimal("500000000.00"))
                .accumulationExceeded())
        .isFalse();
  }

  @Test
  void thresholdsAndAccumulationLimitAreConfigurable() {
    UnderwritingRules rules = new UnderwritingRules(100, 200, 300, new BigDecimal("1000.00"), 3);
    List<LocalDate> window = new ArrayList<>();
    Underwriter underwriter =
        new Underwriter(
            rules,
            pn -> Optional.of(subject(500, "S", "1000.00", "250000.00")),
            (pn, from, to) -> {
              window.add(from);
              window.add(to);
              return ClaimsSummary.NONE;
            },
            branch -> new BigDecimal("1000.01"));
    RiskDecision d = decided(underwriter.underwrite("POL-00000101", TODAY));
    assertThat(d.riskScore()).isEqualTo(500);
    assertThat(d.decisionCode()).isEqualTo("DC");
    assertThat(window).containsExactly(LocalDate.of(2022, 3, 1), TODAY);
    assertThatThrownBy(() -> new UnderwritingRules(300, 300, 800, BigDecimal.ONE, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decisionRowCarriesTheLegacyAuditValues() {
    Underwriter underwriter =
        new Underwriter(
            UnderwritingRules.legacy(),
            pn -> Optional.of(subject(301, "S", "1000.00", "250000.00")),
            (pn, from, to) -> ClaimsSummary.NONE,
            branch -> LOW_ACCUMULATION);
    UnderwritingDecision row =
        decided(underwriter.underwrite("POL-00000101", TODAY)).toDecisionRow();
    assertThat(row.decisionCode()).isEqualTo("AP");
    assertThat(row.riskScore()).isEqualTo(299);
    assertThat(row.decisionReason()).isEqualTo("AUTO-ACCEPTED: LOW RISK");
    assertThat(row.underwriterId()).isEqualTo("SYSTEM");
    assertThat(row.decisionDate()).isEqualTo(TODAY);
    assertThat(row.overrideBy()).isNull();
  }

  @Test
  void rejectsBlankOrOverlongPolicyNumbersBeforeAnyRead() {
    Underwriter underwriter =
        new Underwriter(
            UnderwritingRules.legacy(),
            pn -> {
              throw new AssertionError("must not read");
            },
            (pn, from, to) -> {
              throw new AssertionError("must not read");
            },
            branch -> {
              throw new AssertionError("must not read");
            });
    for (String bad : new String[] {null, "", "   ", "POL-0000000001"}) {
      assertThat(underwriter.underwrite(bad, TODAY))
          .isEqualTo(
              new UnderwritingOutcome.Rejected(UnderwritingOutcome.Rejection.INVALID_REQUEST));
    }
  }

  @Test
  void missingSubjectIsNotFoundAndClaimsOutageStopsTheDecision() {
    Underwriter missing =
        new Underwriter(
            UnderwritingRules.legacy(),
            pn -> Optional.empty(),
            (pn, from, to) -> ClaimsSummary.NONE,
            branch -> BigDecimal.ZERO);
    assertThat(missing.underwrite("POL-00000117", TODAY))
        .isEqualTo(new UnderwritingOutcome.Rejected(UnderwritingOutcome.Rejection.NOT_FOUND));

    Underwriter outage =
        new Underwriter(
            UnderwritingRules.legacy(),
            pn -> Optional.of(subject(500, "S", "1.00", "1.00")),
            (pn, from, to) -> {
              throw new ClaimsHistoryUnavailableException("down");
            },
            branch -> BigDecimal.ZERO);
    assertThatThrownBy(() -> outage.underwrite("POL-00000101", TODAY))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
  }

  @Test
  void overrideAcceptsOrDeclinesTheLatestDecision() {
    UnderwritingDecision latest =
        new UnderwritingDecision(
            7L,
            "POL-00000101",
            TODAY,
            "RS",
            450,
            "REFERRED TO SENIOR UNDERWRITER",
            "SYSTEM",
            null,
            null,
            null);
    UnderwritingDecision after =
        new UnderwritingOverride("AP", "Inspected, risk acceptable", "UW00042").applyTo(latest);
    assertThat(after.decisionCode()).isEqualTo("AP");
    assertThat(after.overrideReason()).isEqualTo("Inspected, risk acceptable");
    assertThat(after.overrideBy()).isEqualTo("UW00042");
    assertThat(after.riskScore()).isEqualTo(450);
    assertThat(after.decisionId()).isEqualTo(7L);

    assertThat(new UnderwritingOverride("RS", "x", "U1").violations()).hasSize(1);
    assertThat(new UnderwritingOverride("AP", " ", "U1").violations()).hasSize(1);
    assertThat(new UnderwritingOverride("DC", "x".repeat(201), "TOOLONGID").violations())
        .hasSize(2);
  }

  private static RiskDecision decided(UnderwritingOutcome outcome) {
    assertThat(outcome).isInstanceOf(UnderwritingOutcome.Decided.class);
    return ((UnderwritingOutcome.Decided) outcome).decision();
  }
}
