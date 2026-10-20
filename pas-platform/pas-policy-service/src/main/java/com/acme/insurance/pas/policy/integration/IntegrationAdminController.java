package com.acme.insurance.pas.policy.integration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator API for dead letters and captured legacy messages; scope {@code batch:operate}. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Integration operations", description = "Dead-letter replay (AB-528)")
public class IntegrationAdminController {

  private final DeadLetterService deadLetters;
  private final LegacyQueueTransport transport;

  public IntegrationAdminController(DeadLetterService deadLetters, LegacyQueueTransport transport) {
    this.deadLetters = deadLetters;
    this.transport = transport;
  }

  @GetMapping("/dead-letters")
  @Operation(summary = "List dead letters (optionally by status PARKED or REPLAYED)")
  public List<DeadLetterView> deadLetters(@RequestParam(required = false) String status) {
    return deadLetters.list(status);
  }

  @GetMapping("/dead-letters/{id}")
  @Operation(summary = "One dead letter, including the original record value")
  public ResponseEntity<DeadLetterView> deadLetter(@PathVariable long id) {
    return ResponseEntity.of(deadLetters.find(id));
  }

  @PostMapping("/dead-letters/{id}/replay")
  @Operation(summary = "Republish a dead letter to its original topic (consumers are idempotent)")
  public ResponseEntity<DeadLetterView> replay(
      @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.of(deadLetters.replay(id, jwt == null ? null : jwt.getSubject()));
  }

  @GetMapping("/legacy-messages")
  @Operation(
      summary = "Messages captured by the in-memory legacy queue transport (test environments)")
  public ResponseEntity<List<LegacyQueueMessage>> legacyMessages(
      @RequestParam(required = false) String policyNumber) {
    if (!(transport instanceof InMemoryLegacyQueueTransport memory)) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(
        memory.messages().stream()
            .filter(m -> policyNumber == null || policyNumber.strip().equals(m.policyNumber()))
            .toList());
  }
}
