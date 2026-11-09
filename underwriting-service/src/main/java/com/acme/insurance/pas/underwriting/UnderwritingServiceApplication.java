package com.acme.insurance.pas.underwriting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java 17 / Spring Boot 3 replacement for the UNDWRT program behind CICS transaction PUWR.
 */
@SpringBootApplication
public class UnderwritingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnderwritingServiceApplication.class, args);
    }
}
