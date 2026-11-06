package com.acme.insurance.pas.parity;

import java.util.List;

/**
 * Outcome of one program run for one fixture case: completed or abended, the return code and the
 * effects in order. Golden files (COBOL) and adapters (Java) both produce this shape.
 */
public record RunResult(String status, int returnCode, String abendCode, List<Effect> effects) {

  public static final String COMPLETED = "completed";
  public static final String ABEND = "abend";

  public RunResult {
    effects = List.copyOf(effects);
  }

  public static RunResult completed(int returnCode, List<Effect> effects) {
    return new RunResult(COMPLETED, returnCode, null, effects);
  }
}
