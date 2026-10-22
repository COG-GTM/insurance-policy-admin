package com.acme.insurance.pas.policy.newbusiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.repository.CoverageRepository;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewBusinessServiceTest {
  private final PolicyHolderRepository holders = mock(PolicyHolderRepository.class);
  private final NewBusinessService service =
      new NewBusinessService(
          mock(PolicyRepository.class),
          mock(CoverageRepository.class),
          holders,
          mock(PolicyNumberGenerator.class),
          mock(IdempotencyRepository.class),
          mock(ShadowNewBusinessRepository.class),
          new DefaultCoverageProperties(Map.of()),
          mock(OutboxWriter.class),
          new ObjectMapper().findAndRegisterModules(),
          Clock.fixed(Instant.parse("2025-02-01T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void collectsAllNewBusinessAndColumnErrors() {
    var errors =
        service.validate(
            new NewBusinessRequest(
                "",
                "MISSING",
                java.time.LocalDate.of(2025, 1, 31),
                "TOO-LONG",
                "B0001",
                null,
                new java.math.BigDecimal("1.001"),
                new java.math.BigDecimal("1234567890.00")));

    assertThat(errors)
        .extracting(NewBusinessError::field)
        .contains(
            "policyType",
            "effectiveDate",
            "coverageLimit",
            "deductible",
            "totalPremium",
            "agentCode",
            "branchCode");
    assertThat(errors)
        .extracting(NewBusinessError::message)
        .contains(
            "POLICY TYPE IS REQUIRED",
            "EFFECTIVE DATE CANNOT BE IN PAST",
            "POLICY LIMIT MUST BE GREATER THAN ZERO");
    verify(holders, never()).existsByCustId("MISSING");
  }

  @Test
  void hashesTheCanonicalRequestRecordDeterministically() {
    NewBusinessRequest request =
        new NewBusinessRequest(
            "AUT",
            "CUST000001",
            java.time.LocalDate.of(2025, 2, 1),
            "AGT001",
            "B001",
            new java.math.BigDecimal("1000.00"),
            null,
            null);

    assertThat(service.requestHash(request)).isEqualTo(service.requestHash(request));
    assertThat(service.requestHash(request))
        .isNotEqualTo(
            service.requestHash(
                new NewBusinessRequest(
                    "HOM",
                    request.policyholderId(),
                    request.effectiveDate(),
                    request.agentCode(),
                    request.branchCode(),
                    request.coverageLimit(),
                    null,
                    null)));
  }
}
