package com.acme.insurance.pas.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks personal data (ADR 0001 D9, docs/pas/data-model.md section 5). Annotated values never
 * appear in {@code toString()}, logs, metrics, events or error messages.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Pii {

  /** Classification of the value. */
  Level value();

  /** PII classes used by the PAS data model. */
  enum Level {
    /** Names, address, phone and email: personal data, masked outside production. */
    PERSONAL,
    /**
     * Regulated identifiers and attributes (date of birth, SSN last four, tax id, credit score):
     * column-level encryption or tokenisation, never in non-production data.
     */
    RESTRICTED
  }
}
