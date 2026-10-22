package com.acme.insurance.pas.policy.events;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/events")
public class DltReplayController {
  private final DltReplayService service;

  public DltReplayController(DltReplayService service) {
    this.service = service;
  }

  @PostMapping("/dlt-replay")
  public ResponseEntity<?> replay(
      @Valid @RequestBody DltReplayRequest request, @AuthenticationPrincipal Jwt jwt) {
    try {
      return ResponseEntity.ok(service.replay(request, jwt.getSubject()));
    } catch (IllegalArgumentException exception) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }
  }
}
