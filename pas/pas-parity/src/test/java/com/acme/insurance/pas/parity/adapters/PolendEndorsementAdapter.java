package com.acme.insurance.pas.parity.adapters;

import com.acme.insurance.pas.domain.EndorsementType;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.parity.Effect;
import com.acme.insurance.pas.parity.FixtureCase;
import com.acme.insurance.pas.parity.ProgramAdapter;
import com.acme.insurance.pas.parity.RunResult;
import com.acme.insurance.pas.rating.PolicyChangeError;
import com.acme.insurance.pas.rating.ProRata;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Suite {@code polend-endorsement}: runs the AB-526 endorsement rules (type check, {@link
 * PolicyStatus#endorsable()}, {@link ProRata} without a premium change) on the fixture policy and
 * reports EndorsementService's writes in POLEND's terms: the ENDORSEMENTS insert, the POLICIES
 * premium update, the audit write (a durable PASAPP row instead of TS queue ENDORSEMENT-AUDIT,
 * Q-23), the POLECONF confirmation and the return.
 *
 * <p>POLEND never fills its type field from the screen, so most fixtures carry LOW-VALUES (Q-19).
 * The API requires one of the five known types; the adapter submits {@link #SUBSTITUTE_TYPE} for
 * LOW-VALUES, and only the ENDORSEMENT_TYPE field may differ for those cases (known difference).
 */
public final class PolendEndorsementAdapter implements ProgramAdapter {

  static final String SUBSTITUTE_TYPE = EndorsementType.LIMIT_CHANGE.code();
  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
  private static final String PROCESSED_BY = "POLEND";

  @Override
  public RunResult run(FixtureCase fixture) {
    JsonNode in = fixture.input();
    String type =
        switch (fixture.text(in, "STORAGE_WS_ENDORSEMENT_TYPE", "SPACES")) {
          case "SPACES" -> "   ";
          case "LOW-VALUES" -> SUBSTITUTE_TYPE;
          default -> fixture.text(in, "STORAGE_WS_ENDORSEMENT_TYPE", "");
        };
    Optional<PolicyChangeError> invalidType = PolicyChangeError.checkEndorsementType(type);
    if (invalidType.isPresent()) {
      return refused(invalidType.get());
    }
    if (!"0".equals(fixture.text(in, "SQLCODE_SELECT_POLICY", "0"))) {
      return refused(PolicyChangeError.ENDORSEMENT_POLICY_NOT_FOUND);
    }
    String status = fixture.text(in, "POLICY_STATUS", "");
    boolean endorsable = PolicyStatus.fromCode(status).map(PolicyStatus::endorsable).orElse(false);
    if (!endorsable) {
      return refused(PolicyChangeError.NOT_ACTIVE_FOR_ENDORSEMENT);
    }
    String policyNumber = Cobol.alphanumeric(fixture.text(in, "MAP_POLICY_NUMBER", ""), 12);
    ProRata proRata =
        ProRata.of(
            LocalDate.parse(fixture.text(in, "EFFECTIVE_DATE", ""), YYYYMMDD),
            LocalDate.parse(fixture.text(in, "EXPIRY_DATE", ""), YYYYMMDD),
            fixture.runDate(),
            null);
    BigDecimal premium = proRata.applyTo(new BigDecimal(fixture.text(in, "TOTAL_PREMIUM", "0")));
    int seq = Integer.parseInt(fixture.text(in, "LAST_ENDORSEMENT_SEQ", "0")) + 1;
    return RunResult.completed(
        0,
        List.of(
            Effect.builder("SQL INSERT ENDORSEMENTS")
                .sqlcode(0)
                .text("POLICY_NUMBER", policyNumber)
                .text("ENDORSEMENT_TYPE", type)
                .text("EFFECTIVE_DATE", fixture.runDate().format(YYYYMMDD))
                .text("PROCESSED_BY", PROCESSED_BY)
                .number("ENDORSEMENT_SEQ", seq)
                .number("PREMIUM_ADJUSTMENT", proRata.adjustment())
                .probe("WS-DAYS-IN-TERM", proRata.daysInTerm())
                .probe("WS-DAYS-REMAINING", proRata.daysRemaining())
                .probe("WS-PRORATA-FACTOR", proRata.factor())
                .build(),
            Effect.builder("SQL UPDATE POLICIES")
                .sqlcode(0)
                .text("POLICY_NUMBER", policyNumber)
                .text("UPDATED_BY", PROCESSED_BY)
                .number("TOTAL_PREMIUM", premium)
                .build(),
            Effect.builder("CICS WRITEQ TS")
                .text("QUEUE", "ENDORSEMENT-AUDIT")
                .text("POLICY_NUMBER", policyNumber)
                .text("POLICY_STATUS", status)
                .number("TOTAL_PREMIUM", premium)
                .build(),
            Effect.builder("CICS SEND MAP POLECONF")
                .text("POLICY_NUMBER", policyNumber)
                .text("POLICY_STATUS", status)
                .number("TOTAL_PREMIUM", premium)
                .build(),
            Effect.builder("CICS RETURN").text("TRANSID", "PEND").build()));
  }

  private static RunResult refused(PolicyChangeError error) {
    return RunResult.completed(
        0,
        List.of(
            Effect.builder("CICS SEND TEXT").text("TEXT", error.legacyText()).build(),
            Effect.builder("CICS RETURN").text("TRANSID", "PEND").build()));
  }
}
