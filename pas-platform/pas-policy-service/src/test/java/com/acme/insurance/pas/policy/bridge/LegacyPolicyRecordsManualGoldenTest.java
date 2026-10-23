package com.acme.insurance.pas.policy.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.copybook.CopybookCharset;
import com.acme.insurance.pas.copybook.LegacyPolicyRecords;
import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.parity.ParityHarness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class LegacyPolicyRecordsManualGoldenTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void newBusinessRecordMatchesHandBuiltPolicyRecordBytes() throws Exception {
    Path root = ParityHarness.locateRoot();
    JsonNode golden =
        mapper.readTree(
            Files.readString(root.resolve("parity/golden-manual/POLNEW/new-business.json")));
    Policy policy =
        new Policy(
            golden.path("policyNumber").asText(),
            Coded.of(PolicyType.class, golden.path("policyType").asText()),
            null,
            null,
            null,
            null,
            null,
            golden.path("branchCode").asText(),
            golden.path("totalPremium").decimalValue(),
            golden.path("deductible").decimalValue(),
            golden.path("coverageLimit").decimalValue(),
            null,
            0,
            Coded.of(UnderwritingStatus.class, golden.path("uwStatus").asText()),
            golden.path("riskScore").asInt(),
            null,
            null,
            null,
            null);

    byte[] record = LegacyPolicyRecords.polnewReferralRecord(policy, CopybookCharset.ASCII);

    assertThat(record).hasSize(125);
    assertThat(HexFormat.of().withUpperCase().formatHex(record))
        .isEqualTo(golden.path("recordHex").asText());
  }
}
