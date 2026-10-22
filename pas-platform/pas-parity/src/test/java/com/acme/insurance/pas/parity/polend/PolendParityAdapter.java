package com.acme.insurance.pas.parity.polend;

import com.acme.insurance.pas.lifecycle.EndorsementRules;
import com.acme.insurance.pas.lifecycle.EndorsementType;
import com.acme.insurance.pas.lifecycle.LifecycleRejectedException;
import com.acme.insurance.pas.parity.ParityAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * POLEND through the AB-526 rules. The legacy program never reads the endorsement type or the
 * adjustment (they are whatever working storage holds, seeded by the harness); the service takes
 * the type as input (Q-12) and always posts a 0.00 adjustment (Q-11, Q-49). Any failed write leaves
 * nothing behind (Q-14).
 */
final class PolendParityAdapter
    implements ParityAdapter<PolendParityAdapter.Input, PolendParityAdapter.Output> {
  static final String RECEIVE_ERROR = "ERROR RECEIVING ENDORSEMENT DATA";
  static final String POLICY_NOT_FOUND = "POLICY NOT FOUND";
  static final String INSERT_ERROR = "DB2 ERROR INSERTING ENDORSEMENT";
  static final String POLICY_UPDATE_ERROR = "DB2 ERROR UPDATING POLICY";
  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

  @Override
  public String program() {
    return "POLEND";
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
    try {
      EndorsementRules.checkActive(policy.status());
      EndorsementType type = EndorsementType.fromCode(input.seed().endorsementType());
      LocalDate date = LocalDate.parse(input.currentDate(), YYYYMMDD);
      EndorsementRules.proRataFactor(
          LocalDate.parse(policy.effectiveDate(), YYYYMMDD),
          LocalDate.parse(policy.expiryDate(), YYYYMMDD),
          date);
      int sequence = EndorsementRules.nextSequence(input.maxEndorsementSeq());
      BigDecimal adjustment = EndorsementRules.PREMIUM_ADJUSTMENT;
      BigDecimal total = EndorsementRules.adjustedPremium(policy.totalPremium(), adjustment);
      if (input.insertSqlcode() != 0) {
        return Output.error(INSERT_ERROR);
      }
      if (input.policyUpdateSqlcode() != 0) {
        return Output.error(POLICY_UPDATE_ERROR);
      }
      return new Output(
          List.of(
              new EndorsementRow(
                  policy.policyNumber(), sequence, type.code(), input.currentDate(), adjustment)),
          List.of(new PolicyUpdateRow(total)),
          List.of(new AuditRow(policy.policyNumber(), total)),
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
      Integer maxEndorsementSeq,
      int insertSqlcode,
      int policyUpdateSqlcode,
      Seed seed) {}

  record Policy(
      String policyNumber,
      String status,
      String effectiveDate,
      String expiryDate,
      BigDecimal totalPremium) {}

  record Seed(String endorsementType, BigDecimal premiumAdjust) {}

  record Output(
      List<EndorsementRow> endorsement,
      List<PolicyUpdateRow> policyUpdate,
      List<AuditRow> audit,
      List<ErrorRow> error) {
    static Output error(String message) {
      return new Output(List.of(), List.of(), List.of(), List.of(new ErrorRow(message)));
    }
  }

  record EndorsementRow(
      String policyNumber,
      int endorsementSeq,
      String endorsementType,
      String effectiveDate,
      BigDecimal premiumAdjustment) {}

  record PolicyUpdateRow(BigDecimal totalPremium) {}

  record AuditRow(String policyNumber, BigDecimal totalPremium) {}

  record ErrorRow(String message) {}
}
