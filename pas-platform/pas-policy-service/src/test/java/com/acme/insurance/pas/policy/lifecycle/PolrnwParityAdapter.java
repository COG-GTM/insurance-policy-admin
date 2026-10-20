package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * POLRNW fixture case → the renewal rules and arithmetic {@link RenewalService} uses, with the
 * shipped defaults (5% uplift, 15% cap, more than 3 open claims ineligible).
 */
class PolrnwParityAdapter implements ParityAdapter {

  static final BigDecimal UPLIFT_PCT = BigDecimal.valueOf(5);
  static final BigDecimal RATE_CAP_PCT = BigDecimal.valueOf(15);
  static final int MAX_OPEN_CLAIMS = 3;

  /** The service returns 404; POLRNW's text is reproduced for the golden. */
  static final String MSG_NOT_FOUND = "POLICY NOT FOUND FOR RENEWAL";

  /** A failed update rolls back and returns 500 without a confirmation (Q-09). */
  static final String MSG_UPDATE_FAILED = "DB2 ERROR UPDATING RENEWAL";

  @Override
  public String program() {
    return "polrnw";
  }

  @Override
  public Map<String, String> run(ParityCase c) {
    Map<String, String> out = new LinkedHashMap<>();
    if (c.integer("select_sqlcode") != 0) {
      return refused(out, MSG_NOT_FOUND, BigDecimal.ZERO, 0, 0, "", "");
    }
    BigDecimal premium = c.decimal("total_premium");
    int expiry = (int) c.integer("expiry_date");
    int count = (int) c.integer("renewal_count");
    Optional<String> ineligible =
        RenewalService.eligibilityMessage(
            c.text("policy_status"), c.integer("open_claims"), MAX_OPEN_CLAIMS);
    if (ineligible.isPresent()) {
      return refused(
          out,
          ineligible.get(),
          premium,
          expiry,
          count,
          c.text("policy_status"),
          c.text("uw_status"));
    }
    RenewalCalculator.RenewalTerms t =
        RenewalCalculator.calculate(premium, UPLIFT_PCT, RATE_CAP_PCT, expiry, count);
    boolean updateFailed = c.integer("update_sqlcode") != 0;
    out.put("error_msg", updateFailed ? MSG_UPDATE_FAILED : "");
    out.put("screens_sent", updateFailed ? "TEXT" : "POLRCONF");
    out.put("new_premium", ParityFormat.money(t.newPremium()));
    out.put("rate_change_pct", ParityFormat.money(t.rateChangePct()));
    out.put("policy_total_premium", ParityFormat.money(t.newPremium()));
    out.put("policy_effective_date", ParityFormat.integer(t.newEffectiveDate()));
    out.put("policy_expiry_date", ParityFormat.integer(t.newExpiryDate()));
    out.put("renewal_count", ParityFormat.integer(t.newRenewalCount()));
    out.put("policy_status", "AC");
    out.put("uw_status", "PN");
    return out;
  }

  private static Map<String, String> refused(
      Map<String, String> out,
      String message,
      BigDecimal premium,
      int expiry,
      int count,
      String status,
      String uwStatus) {
    out.put("error_msg", message);
    out.put("screens_sent", "TEXT");
    out.put("new_premium", "0.00");
    out.put("rate_change_pct", "0.00");
    out.put("policy_total_premium", ParityFormat.money(premium));
    out.put("policy_effective_date", "0");
    out.put("policy_expiry_date", ParityFormat.integer(expiry));
    out.put("renewal_count", ParityFormat.integer(count));
    out.put("policy_status", status);
    out.put("uw_status", uwStatus);
    return out;
  }
}
