package com.acme.insurance.pas.parity.polrnw;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException;
import com.acme.insurance.pas.lifecycle.RenewalRules;
import com.acme.insurance.pas.lifecycle.RenewalSettings;
import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RenewalPremium;
import com.acme.insurance.pas.rating.RenewalPremiumException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * POLRNW through the AB-526 rules. The SQLCODE inputs model a failed write: the renewal service
 * runs in one transaction, so any failure leaves no policy or coverage update (Q-16).
 */
final class PolrnwParityAdapter
    implements ParityAdapter<PolrnwParityAdapter.Input, PolrnwParityAdapter.Output> {
  static final String RECEIVE_ERROR = "ERROR RECEIVING RENEWAL DATA";
  static final String POLICY_NOT_FOUND = "POLICY NOT FOUND FOR RENEWAL";
  static final String POLICY_UPDATE_ERROR = "DB2 ERROR UPDATING RENEWAL";
  static final String COVERAGE_UPDATE_ERROR = "DB2 ERROR UPDATING COVERAGES";
  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

  @Override
  public String program() {
    return "POLRNW";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Output run(Input input) {
    if (input.receiveResp() != 0) {
      return Output.error(RECEIVE_ERROR);
    }
    if (input.policySqlcode() != 0) {
      return Output.error(POLICY_NOT_FOUND);
    }
    Policy policy = input.policy();
    LocalDate expiry = LocalDate.parse(policy.expiryDate(), YYYYMMDD);
    RenewalSettings settings = RenewalSettings.legacyDefaults();
    try {
      RenewalRules.checkEligibility(
          policy.status(),
          policy.uwStatus(),
          expiry,
          policy.renewalCount(),
          LocalDate.parse(input.currentDate(), YYYYMMDD),
          settings,
          input::openClaims);
      RenewalPremium premium;
      try {
        premium =
            PremiumCalculator.renewalPremium(
                policy.totalPremium(), settings.upliftPct(), settings.defaultCapPct());
      } catch (RenewalPremiumException exception) {
        throw new LifecycleRejectedException(
            LifecycleRejectedException.Rejection.RENEWAL_PREMIUM_INVALID);
      }
      if (input.policyUpdateSqlcode() != 0) {
        return Output.error(POLICY_UPDATE_ERROR);
      }
      if (input.coverageUpdateSqlcode() != 0) {
        return Output.error(COVERAGE_UPDATE_ERROR);
      }
      String effective = RenewalRules.renewalEffectiveDate(expiry).format(YYYYMMDD);
      String renewedExpiry = RenewalRules.renewalExpiryDate(expiry).format(YYYYMMDD);
      return new Output(
          List.of(
              new PolicyUpdateRow(
                  "AC",
                  effective,
                  renewedExpiry,
                  premium.newPremium(),
                  RenewalRules.nextRenewalCount(policy.renewalCount()),
                  "PN")),
          List.of(new CoverageUpdateRow(effective, renewedExpiry)),
          List.of());
    } catch (LifecycleRejectedException exception) {
      return Output.error(exception.rejection().message());
    }
  }

  record Input(
      String currentDate,
      int receiveResp,
      int policySqlcode,
      Policy policy,
      int openClaims,
      int policyUpdateSqlcode,
      int coverageUpdateSqlcode) {}

  record Policy(
      String policyNumber,
      String policyType,
      String status,
      BigDecimal totalPremium,
      String expiryDate,
      int renewalCount,
      String uwStatus) {}

  record Output(
      List<PolicyUpdateRow> policyUpdate,
      List<CoverageUpdateRow> coverageUpdate,
      List<ErrorRow> error) {
    static Output error(String message) {
      return new Output(List.of(), List.of(), List.of(new ErrorRow(message)));
    }
  }

  record PolicyUpdateRow(
      String status,
      String effectiveDate,
      String expiryDate,
      BigDecimal totalPremium,
      int renewalCount,
      String uwStatus) {}

  record CoverageUpdateRow(String effectiveDate, String expiryDate) {}

  record ErrorRow(String message) {}
}
