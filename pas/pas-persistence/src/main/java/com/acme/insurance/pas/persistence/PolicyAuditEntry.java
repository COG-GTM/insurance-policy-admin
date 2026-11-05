package com.acme.insurance.pas.persistence;

import java.time.LocalDateTime;

/**
 * PASAPP.POLICY_AUDIT row (V51, component DS-05): who changed which policy, how, and the
 * before/after values ({@code details}, JSON).
 *
 * @param principal the authenticated subject of the access token
 * @param clientId the OAuth client the token was issued to
 * @param legacyUser the 8-character id written to the ACMEINS audit columns (Q-518-1)
 */
public record PolicyAuditEntry(
    Long auditId,
    LocalDateTime occurredAt,
    String action,
    String policyNumber,
    String principal,
    String clientId,
    String legacyUser,
    String correlationId,
    String details) {}
