package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.support.CorrelationIds;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class LifecycleResponses {

  private LifecycleResponses() {}

  /** 201 with the correlation id, or 404 when the policy is absent or not entitled. */
  static <T> ResponseEntity<T> created(Optional<T> body, String correlationId) {
    return body.map(
            b ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .header(CorrelationIds.HEADER, correlationId)
                    .body(b))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
