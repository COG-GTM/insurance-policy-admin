package com.acme.insurance.pas.policy.integration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The replayed record could not be published to Kafka; the dead letter stays parked. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DeadLetterReplayException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DeadLetterReplayException(long id, Throwable cause) {
    super("replay of dead letter " + id + " failed: " + cause.getMessage(), cause);
  }
}
