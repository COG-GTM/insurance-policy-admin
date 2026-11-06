package com.acme.insurance.pas.policyservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * PAS policy service (ADR 0001 D1, SVC-01): REST API, security and policy lifecycle use cases.
 *
 * <p>No in-memory users: authentication is an OAuth 2.0 resource server added in AB-524 (D5).
 */
@SpringBootApplication(
    scanBasePackages = "com.acme.insurance.pas",
    exclude = UserDetailsServiceAutoConfiguration.class)
public class PasPolicyServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PasPolicyServiceApplication.class, args);
  }
}
