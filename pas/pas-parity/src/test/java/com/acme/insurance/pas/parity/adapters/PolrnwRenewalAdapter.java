package com.acme.insurance.pas.parity.adapters;

import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.parity.Effect;
import com.acme.insurance.pas.parity.FixtureCase;
import com.acme.insurance.pas.parity.ProgramAdapter;
import com.acme.insurance.pas.parity.RunResult;
import com.acme.insurance.pas.rating.PolicyChangeError;
import com.acme.insurance.pas.rating.RenewalCalculator;
import com.acme.insurance.pas.rating.RenewalQuote;
import com.acme.insurance.pas.rating.RenewalRules;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Suite {@code polrnw-renewal}: runs the AB-526 renewal rules ({@link PolicyStatus#renewable()} and
 * {@link RenewalCalculator} with the production default the production defaults of {@code
 * pas.policy-change.*}) on the fixture policy and reports the writes and sends RenewalService makes
 * in POLRNW's terms: the POLICIES and COVERAGES updates, the POLRCONF confirmation and the return.
 * A failed request (RESP not NORMAL) is refused before any read, the Java equivalent of an HTTP 400
 * (Q-520-1).
 */
public final class PolrnwRenewalAdapter implements ProgramAdapter {

  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
  private static final String UPDATED_BY = "POLRNW";

  private final RenewalCalculator calculator =
      new RenewalCalculator(
          new RenewalRules(
              new BigDecimal("5.00"), new BigDecimal("15.00"), RenewalRules.LeapDayExpiry.FEB_28));

  @Override
  public RunResult run(FixtureCase fixture) {
    JsonNode in = fixture.input();
    if (!"0".equals(fixture.text(in, "RESP", "0"))) {
      return refused(PolicyChangeError.RENEWAL_REQUEST_INVALID);
    }
    if (!"0".equals(fixture.text(in, "SQLCODE_SELECT_POLICY", "0"))) {
      return refused(PolicyChangeError.RENEWAL_POLICY_NOT_FOUND);
    }
    boolean renewable =
        PolicyStatus.fromCode(fixture.text(in, "POLICY_STATUS", ""))
            .map(PolicyStatus::renewable)
            .orElse(false);
    if (!renewable) {
      return refused(PolicyChangeError.NOT_ELIGIBLE_FOR_RENEWAL);
    }
    String policyNumber = Cobol.alphanumeric(fixture.text(in, "MAP_POLICY_NUMBER", ""), 12);
    RenewalQuote quote =
        calculator.quote(
            new BigDecimal(fixture.text(in, "TOTAL_PREMIUM", "0")),
            LocalDate.parse(fixture.text(in, "EXPIRY_DATE", ""), YYYYMMDD),
            Integer.valueOf(fixture.text(in, "RENEWAL_COUNT", "0")));
    String status = PolicyStatus.ACTIVE.code();
    String uwStatus = UnderwritingStatus.PENDING.code();
    String effective = quote.effectiveDate().format(YYYYMMDD);
    String expiry = quote.expiryDate().format(YYYYMMDD);
    return RunResult.completed(
        0,
        List.of(
            Effect.builder("SQL UPDATE POLICIES")
                .sqlcode(0)
                .text("POLICY_NUMBER", policyNumber)
                .text("POLICY_STATUS", status)
                .text("EFFECTIVE_DATE", effective)
                .text("EXPIRY_DATE", expiry)
                .text("UW_STATUS", uwStatus)
                .text("UPDATED_BY", UPDATED_BY)
                .number("TOTAL_PREMIUM", quote.newPremium())
                .number("RENEWAL_COUNT", quote.renewalCount())
                .probe("WS-OLD-PREMIUM", quote.previousPremium())
                .probe("WS-NEW-PREMIUM", quote.newPremium())
                .probe("WS-RATE-CHANGE-PCT", quote.rateChangePct())
                // Counted by POLRNW but never enforced (Q-02); echoed from the Claims read.
                .probe("OPEN-CLAIM-COUNT", fixture.text(in, "OPEN_CLAIM_COUNT", "0"))
                .build(),
            Effect.builder("SQL UPDATE COVERAGES")
                .sqlcode(0)
                .text("POLICY_NUMBER", policyNumber)
                .text("EFFECTIVE_DATE", effective)
                .text("EXPIRY_DATE", expiry)
                .build(),
            Effect.builder("CICS SEND MAP POLRCONF")
                .text("POLICY_NUMBER", policyNumber)
                .text("POLICY_STATUS", status)
                .text("EFFECTIVE_DATE", effective)
                .text("EXPIRY_DATE", expiry)
                .text("UW_STATUS", uwStatus)
                .number("TOTAL_PREMIUM", quote.newPremium())
                .number("RENEWAL_COUNT", quote.renewalCount())
                .build(),
            transactionReturn()));
  }

  private static RunResult refused(PolicyChangeError error) {
    return RunResult.completed(
        0,
        List.of(
            Effect.builder("CICS SEND TEXT").text("TEXT", error.legacyText()).build(),
            transactionReturn()));
  }

  private static Effect transactionReturn() {
    return Effect.builder("CICS RETURN").text("TRANSID", "PRWL").build();
  }
}
