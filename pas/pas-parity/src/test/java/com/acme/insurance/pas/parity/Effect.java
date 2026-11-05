package com.acme.insurance.pas.parity;

import java.util.Map;
import java.util.TreeMap;

/**
 * One observed effect of a program run: a SQL write, a CICS send or a queue write, with its host
 * variables. {@code text} holds alphanumeric values (right-trimmed), {@code numbers} decimal values
 * compared numerically, {@code probes} internal working-storage values (compared only when the Java
 * side reports them).
 */
public record Effect(
    String op,
    Integer sqlcode,
    Map<String, String> text,
    Map<String, String> numbers,
    Map<String, String> probes) {

  public Effect {
    text = Map.copyOf(text == null ? Map.of() : text);
    numbers = Map.copyOf(numbers == null ? Map.of() : numbers);
    probes = Map.copyOf(probes == null ? Map.of() : probes);
  }

  public static Builder builder(String op) {
    return new Builder(op);
  }

  /** Builds an effect field by field, in the order the COBOL stub records them. */
  public static final class Builder {
    private final String op;
    private Integer sqlcode;
    private final Map<String, String> text = new TreeMap<>();
    private final Map<String, String> numbers = new TreeMap<>();
    private final Map<String, String> probes = new TreeMap<>();

    private Builder(String op) {
      this.op = op;
    }

    public Builder sqlcode(int value) {
      this.sqlcode = value;
      return this;
    }

    public Builder text(String name, String value) {
      text.put(name, value);
      return this;
    }

    public Builder number(String name, Object value) {
      numbers.put(name, value.toString());
      return this;
    }

    public Builder probe(String name, Object value) {
      probes.put(name, value.toString());
      return this;
    }

    public Effect build() {
      return new Effect(op, sqlcode, text, numbers, probes);
    }
  }
}
