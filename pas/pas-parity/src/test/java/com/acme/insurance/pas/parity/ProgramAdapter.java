package com.acme.insurance.pas.parity;

/**
 * Runs the Java implementation of a legacy program for one fixture case and reports the same
 * effects the COBOL stubs record. One adapter per parity suite, registered as {@code java_adapter}
 * in {@code parity/suites.json}; needs a public no-argument constructor.
 */
public interface ProgramAdapter {

  RunResult run(FixtureCase fixture);
}
