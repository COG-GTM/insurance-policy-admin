/**
 * PAS domain: policy, coverage, premium, endorsement, underwriting decision and policyholder value
 * types, enums for the COBOL 88-levels and {@code BigDecimal} money with COBOL scale.
 *
 * <p>Depends on nothing: no Spring, no persistence annotations, no other PAS module (ADR 0001 D1,
 * enforced by the Maven enforcer and pas-architecture-tests). Value types and copybook codecs
 * arrive with AB-521 (see docs/pas/data-model.md); rating types with AB-522.
 */
package com.acme.insurance.pas.domain;
