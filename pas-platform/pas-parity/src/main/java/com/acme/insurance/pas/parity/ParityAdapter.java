package com.acme.insurance.pas.parity;

import java.util.Map;

/**
 * Adapter between a parity fixture case and a Java implementation of a COBOL program.
 * Implementations live next to the code under test (usually in test sources) and translate the
 * fixture inputs into calls on the real classes.
 */
public interface ParityAdapter {

  /** Directory name under {@code parity/programs/}, e.g. {@code "prembat"}. */
  String program();

  /**
   * Runs one fixture case and returns the program outputs keyed by the golden CSV column names
   * (excluding {@code case_id}).
   */
  Map<String, String> run(ParityCase c);
}
