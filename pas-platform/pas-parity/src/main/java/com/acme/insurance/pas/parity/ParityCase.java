package com.acme.insurance.pas.parity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** One row of a program's {@code fixtures.csv}. */
public record ParityCase(String caseId, String note, Map<String, String> inputs) {

  private static final DateTimeFormatter COBOL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  public ParityCase {
    inputs = Map.copyOf(inputs);
  }

  private String required(String name) {
    String v = inputs.get(name);
    if (v == null) {
      throw new IllegalArgumentException("unknown fixture input " + name + " for case " + caseId);
    }
    return v;
  }

  public String text(String name) {
    return required(name);
  }

  public BigDecimal decimal(String name) {
    return new BigDecimal(required(name).trim());
  }

  public long integer(String name) {
    return Long.parseLong(required(name).trim());
  }

  public LocalDate date(String name) {
    return LocalDate.parse(required(name).trim(), COBOL_DATE);
  }
}
