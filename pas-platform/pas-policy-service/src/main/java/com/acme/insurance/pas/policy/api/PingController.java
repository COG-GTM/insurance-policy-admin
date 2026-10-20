package com.acme.insurance.pas.policy.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class PingController {

  public record PingResponse(String service, String status) {}

  @GetMapping("/ping")
  public PingResponse ping() {
    return new PingResponse("pas-policy-service", "UP");
  }
}
