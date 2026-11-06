package com.acme.insurance.pas.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PAS batch application (ADR 0001 D1, SVC-02): Spring Batch 5 jobs for the premium run (AB-523) and
 * the Claims and Actuarial extracts (AB-527). Launched by the platform scheduler; runs the
 * requested job and exits with its status.
 */
@SpringBootApplication(scanBasePackages = "com.acme.insurance.pas")
public class PasBatchApplication {

  public static void main(String[] args) {
    System.exit(SpringApplication.exit(SpringApplication.run(PasBatchApplication.class, args)));
  }
}
