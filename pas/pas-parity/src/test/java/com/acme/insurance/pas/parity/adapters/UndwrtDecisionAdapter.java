package com.acme.insurance.pas.parity.adapters;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.events.PasEventType;
import com.acme.insurance.pas.parity.Effect;
import com.acme.insurance.pas.parity.FixtureCase;
import com.acme.insurance.pas.parity.ProgramAdapter;
import com.acme.insurance.pas.parity.RunResult;
import com.acme.insurance.pas.underwriting.ClaimsSummary;
import com.acme.insurance.pas.underwriting.RiskDecision;
import com.acme.insurance.pas.underwriting.Underwriter;
import com.acme.insurance.pas.underwriting.UnderwritingOutcome;
import com.acme.insurance.pas.underwriting.UnderwritingRules;
import com.acme.insurance.pas.underwriting.UnderwritingSubject;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Suite {@code undwrt-decision}: runs the {@link Underwriter} with the legacy rules over the
 * fixture's policy/holder row, claims summary and branch exposure, and reports the effects PUWR
 * produces: the UNDERWRITING_DECISIONS insert, the POLICIES update, the decision message (the
 * {@code pas.underwriting.decided} event, reported under its legacy queue name), the result map and
 * the RETURN. A failed map receive ({@code RESP} not 0) is rejected before any write (Q-520-1).
 */
public final class UndwrtDecisionAdapter implements ProgramAdapter {

  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
  private static final String TRANSID = "PUWR";

  @Override
  public RunResult run(FixtureCase fixture) {
    JsonNode in = fixture.input();
    boolean received = "0".equals(fixture.text(in, "RESP", "0"));
    String policyNumber =
        received ? Cobol.alphanumeric(fixture.text(in, "MAP_POLICY_NUMBER", ""), 12) : "";
    Optional<UnderwritingSubject> subject =
        "0".equals(fixture.text(in, "SQLCODE_SELECT_POLICY", "0"))
            ? Optional.of(
                new UnderwritingSubject(
                    policyNumber,
                    fixture.text(in, "POLICY_TYPE", ""),
                    decimal(fixture, in, "TOTAL_PREMIUM"),
                    decimal(fixture, in, "COVERAGE_LIMIT"),
                    fixture.text(in, "BRANCH_CODE", ""),
                    "AG0001",
                    "C000000001",
                    fixture.text(in, "CUST_TYPE", ""),
                    Integer.valueOf(fixture.text(in, "CREDIT_SCORE", "0")),
                    fixture.text(in, "RISK_TIER", "")))
            : Optional.empty();
    ClaimsSummary claims =
        new ClaimsSummary(
            Integer.parseInt(fixture.text(in, "CLAIM_COUNT", "0")),
            decimal(fixture, in, "TOTAL_INCURRED"));
    BigDecimal branchLimit = decimal(fixture, in, "BRANCH_ACCUMULATED_LIMIT");
    Underwriter underwriter =
        new Underwriter(
            UnderwritingRules.legacy(), pn -> subject, (pn, from, to) -> claims, b -> branchLimit);

    UnderwritingOutcome outcome = underwriter.underwrite(policyNumber, fixture.runDate());
    if (outcome instanceof UnderwritingOutcome.Rejected rejected) {
      return RunResult.completed(
          0,
          List.of(
              Effect.builder("CICS SEND TEXT")
                  .text("TEXT", rejected.reason().legacyMessage())
                  .build(),
              ret()));
    }
    RiskDecision decision = ((UnderwritingOutcome.Decided) outcome).decision();
    UnderwritingDecision row = decision.toDecisionRow();
    String queue = PasEventType.UNDERWRITING_DECIDED.legacyQueue().orElseThrow();
    return RunResult.completed(
        0,
        List.of(
            Effect.builder("SQL INSERT UNDERWRITING_DECISIONS")
                .sqlcode(0)
                .text("POLICY_NUMBER", row.policyNumber())
                .text("DECISION_DATE", row.decisionDate().format(YYYYMMDD))
                .text("DECISION_CODE", row.decisionCode())
                .text("DECISION_REASON", row.decisionReason())
                .text("UNDERWRITER_ID", row.underwriterId())
                .number("RISK_SCORE", row.riskScore())
                .probe("WS-CLAIM-COUNT", decision.assessment().claimCount())
                .probe("WS-LOSS-RATIO", decision.lossRatio())
                .build(),
            Effect.builder("SQL UPDATE POLICIES")
                .sqlcode(0)
                .text("POLICY_NUMBER", row.policyNumber())
                .text("UW_STATUS", row.decisionCode())
                .text("UPDATED_BY", RiskDecision.UPDATED_BY)
                .number("RISK_SCORE", row.riskScore())
                .build(),
            Effect.builder("CICS WRITEQ TD")
                .text("QUEUE", queue)
                .text("POLICY_NUMBER", row.policyNumber())
                .text("POLICY_UW_STATUS", row.decisionCode())
                .number("POLICY_RISK_SCORE", row.riskScore())
                .build(),
            Effect.builder("CICS SEND MAP UWRTRES")
                .text("POLICY_NUMBER", row.policyNumber())
                .text("POLICY_UW_STATUS", row.decisionCode())
                .number("POLICY_RISK_SCORE", row.riskScore())
                .build(),
            ret()));
  }

  private static Effect ret() {
    return Effect.builder("CICS RETURN").text("TRANSID", TRANSID).build();
  }

  private static BigDecimal decimal(FixtureCase fixture, JsonNode in, String name) {
    return new BigDecimal(fixture.text(in, name, "0"));
  }
}
