package com.acme.insurance.pas.common.copybook;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field values keyed by COBOL data name. {@link #toString()} lists field names only, because
 * records such as CUSTOMER-RECORD carry regulated data.
 */
public final class RecordValues {

  private final Map<String, Object> values = new LinkedHashMap<>();

  public RecordValues put(String name, Object value) {
    values.put(name, value);
    return this;
  }

  public Object get(String name) {
    if (!values.containsKey(name)) {
      throw new CopybookDataException("no value for field " + name);
    }
    return values.get(name);
  }

  public boolean contains(String name) {
    return values.containsKey(name);
  }

  public String string(String name) {
    return (String) get(name);
  }

  public BigDecimal decimal(String name) {
    return (BigDecimal) get(name);
  }

  /** Integer value of a scale-0 numeric field. */
  public int integer(String name) {
    return decimal(name).intValueExact();
  }

  public LocalDate date(String name) {
    return (LocalDate) get(name);
  }

  public Map<String, Object> asMap() {
    return Collections.unmodifiableMap(values);
  }

  @Override
  public String toString() {
    return "RecordValues" + values.keySet();
  }
}
