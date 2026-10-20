package com.acme.insurance.pas.batch.alert;

import java.util.List;
import java.util.Map;

/** A batch job ended unsuccessfully; what an on-call notifier needs to act. */
public record BatchAlert(
    String jobName,
    long jobExecutionId,
    String status,
    String exitCode,
    Map<String, String> parameters,
    List<String> failures) {

  public BatchAlert {
    parameters = Map.copyOf(parameters);
    failures = List.copyOf(failures);
  }
}
