package com.acme.insurance.pas.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class PasBatchApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(PasBatchApplication.class, args);
    if (context.getEnvironment().getProperty("pas.batch.premium.cli.run", Boolean.class, false)) {
      // one-shot CLI run: exit with the job's return code (PremiumJobCli)
      System.exit(SpringApplication.exit(context));
    }
  }
}
