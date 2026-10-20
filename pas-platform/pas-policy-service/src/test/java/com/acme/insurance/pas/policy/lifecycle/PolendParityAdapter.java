package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** POLEND fixture case → the validation and arithmetic {@link EndorsementService} uses. */
class PolendParityAdapter implements ParityAdapter {

  /** The service returns 404; POLEND's text is reproduced for the golden. */
  static final String MSG_NOT_FOUND = "POLICY NOT FOUND";

  /** A failed insert rolls back and returns 500 without a confirmation (Q-14). */
  static final String MSG_INSERT_FAILED = "DB2 ERROR INSERTING ENDORSEMENT";

  @Override
  public String program() {
    return "polend";
  }

  @Override
  public Map<String, String> run(ParityCase c) {
    Map<String, String> out = new LinkedHashMap<>();
    if (c.integer("select_sqlcode") != 0) {
      return refused(out, MSG_NOT_FOUND, BigDecimal.ZERO);
    }
    BigDecimal premium = c.decimal("total_premium");
    Optional<String> invalid =
        EndorsementService.validationMessage(
            c.text("policy_status"), c.text("ws_endorsement_type"));
    if (invalid.isPresent()) {
      return refused(out, invalid.get(), premium);
    }
    EndorsementCalculator.ProRata p =
        EndorsementCalculator.proRata(
            (int) c.integer("effective_date"),
            (int) c.integer("expiry_date"),
            (int) c.integer("as_of_date"));
    BigDecimal adjust = EndorsementCalculator.premiumAdjustment(p);
    int seq = EndorsementCalculator.nextSequence((int) c.integer("max_endorsement_seq"));
    boolean insertFailed = c.integer("insert_sqlcode") != 0;
    out.put("error_msg", insertFailed ? MSG_INSERT_FAILED : "");
    out.put("screens_sent", insertFailed ? "TEXT" : "POLECONF");
    out.put("days_in_term", ParityFormat.integer(p.daysInTerm()));
    out.put("days_remaining", ParityFormat.integer(p.daysRemaining()));
    out.put(
        "prorata_factor", ParityFormat.decimal(p.factor(), EndorsementCalculator.FACTOR_DECIMALS));
    out.put("premium_adjust", ParityFormat.money(adjust));
    out.put("endorsement_seq", ParityFormat.integer(seq));
    out.put(
        "total_premium",
        ParityFormat.money(
            insertFailed ? premium : EndorsementCalculator.adjustedPremium(premium, adjust)));
    return out;
  }

  private static Map<String, String> refused(
      Map<String, String> out, String message, BigDecimal premium) {
    out.put("error_msg", message);
    out.put("screens_sent", "TEXT");
    out.put("days_in_term", "0");
    out.put("days_remaining", "0");
    out.put("prorata_factor", "0.000000");
    out.put("premium_adjust", "0.00");
    out.put("endorsement_seq", "0");
    out.put("total_premium", ParityFormat.money(premium));
    return out;
  }
}
