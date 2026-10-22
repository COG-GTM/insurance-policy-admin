package com.acme.insurance.pas.batch;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class PasBatchApplication {
  public static void main(String[] args) {
    boolean cli =
        Arrays.stream(args)
            .anyMatch(
                argument ->
                    argument.equals("--pas.batch.cli.job")
                        || argument.startsWith("--pas.batch.cli.job="));
    if (cli) {
      ConfigurableApplicationContext context =
          SpringApplication.run(PasBatchApplication.class, args);
      System.exit(SpringApplication.exit(context));
    } else {
      SpringApplication.run(PasBatchApplication.class, args);
    }
  }
}
