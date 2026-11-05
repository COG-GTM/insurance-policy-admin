package com.acme.insurance.pas.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One row of PASAPP.POLICY_CHANGE_AUDIT: who renewed or endorsed which policy, and the policy
 * before and after. Replaces POLEND's volatile CICS TS queue ENDORSEMENT-AUDIT (Q-23) and carries
 * the full authenticated principal instead of the 8-character UPDATED_BY (Q-518-1).
 */
public record PolicyChangeAudit(
    String policyNumber,
    Operation operation,
    String legacyProgram,
    Integer endorsementSeq,
    String endorsementType,
    String principal,
    String clientId,
    String correlationId,
    String policyStatusBefore,
    String policyStatusAfter,
    LocalDate effectiveDateBefore,
    LocalDate expiryDateBefore,
    LocalDate effectiveDateAfter,
    LocalDate expiryDateAfter,
    BigDecimal premiumBefore,
    BigDecimal premiumAdjustment,
    BigDecimal premiumAfter,
    Integer renewalCountAfter,
    LocalDateTime occurredAt) {

  public PolicyChangeAudit {
    Objects.requireNonNull(policyNumber, "policyNumber");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(legacyProgram, "legacyProgram");
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public enum Operation {
    RENEWAL,
    ENDORSEMENT
  }
}
