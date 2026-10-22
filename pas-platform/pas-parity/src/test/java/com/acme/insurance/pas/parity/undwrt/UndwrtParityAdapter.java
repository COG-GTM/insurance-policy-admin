package com.acme.insurance.pas.parity.undwrt;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.underwriting.UnderwritingDecision;
import com.acme.insurance.pas.underwriting.UnderwritingInput;
import com.acme.insurance.pas.underwriting.UnderwritingRules;
import com.acme.insurance.pas.underwriting.UnderwritingThresholds;
import java.math.BigDecimal;
import java.util.List;

final class UndwrtParityAdapter
    implements ParityAdapter<UndwrtParityAdapter.Input, UndwrtParityAdapter.Output> {
  private static final String POLICY_NOT_FOUND = "POLICY/CUSTOMER DATA NOT FOUND";
  private static final String RECEIVE_ERROR = "ERROR RECEIVING UNDERWRITING DATA";

  @Override
  public String program() {
    return "UNDWRT";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Output run(Input input) {
    if (input.policySqlcode() != 0) {
      return Output.error(POLICY_NOT_FOUND);
    }
    if (input.receiveResp() != 0) {
      return Output.error(RECEIVE_ERROR);
    }
    UnderwritingDecision decision =
        UnderwritingRules.decide(
            new UnderwritingInput(
                input.customer().creditScore(),
                input.customer().riskTier(),
                input.policy().totalPremium(),
                input.policy().coverageLimit(),
                input.claims().totalIncurred(),
                input.branchAccumulatedLimit(),
                input.claims().count(),
                input.policy().branchCode()),
            UnderwritingThresholds.legacyDefaults());
    String status = decision.code().name();
    return new Output(
        List.of(
            new DecisionRow(
                input.policy().policyNumber(),
                input.currentDate(),
                status,
                decision.riskScore(),
                decision.reasons().get(0))),
        List.of(new PolicyUpdateRow(status, decision.riskScore())),
        List.of(new NotificationRow(input.policy().policyNumber(), status, decision.riskScore())),
        List.of());
  }

  record Input(
      String currentDate,
      int receiveResp,
      int policySqlcode,
      Policy policy,
      Customer customer,
      Claims claims,
      BigDecimal branchAccumulatedLimit) {}

  record Policy(
      String policyNumber,
      String policyType,
      BigDecimal totalPremium,
      BigDecimal coverageLimit,
      String branchCode) {}

  record Customer(String custType, Integer creditScore, String riskTier) {}

  record Claims(int count, BigDecimal totalIncurred) {}

  record Output(
      List<DecisionRow> decision,
      List<PolicyUpdateRow> policyUpdate,
      List<NotificationRow> notification,
      List<ErrorRow> error) {
    static Output error(String message) {
      return new Output(List.of(), List.of(), List.of(), List.of(new ErrorRow(message)));
    }
  }

  record DecisionRow(
      String policyNumber,
      String decisionDate,
      String decisionCode,
      int riskScore,
      String decisionReason) {}

  record PolicyUpdateRow(String uwStatus, int riskScore) {}

  record NotificationRow(String policyNumber, String uwStatus, int riskScore) {}

  record ErrorRow(String message) {}
}
