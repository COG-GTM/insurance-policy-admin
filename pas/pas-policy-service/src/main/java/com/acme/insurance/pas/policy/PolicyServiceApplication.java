package com.acme.insurance.pas.policy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PolicyServiceApplication {
    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "--healthcheck".equals(args[0])) {
            int status;
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) URI.create("http://localhost:8081/actuator/health/readiness")
                                .toURL()
                                .openConnection();
                connection.setConnectTimeout(2_000);
                connection.setReadTimeout(2_000);
                status = connection.getResponseCode();
                connection.disconnect();
            } catch (IOException exception) {
                status = -1;
            }
            System.exit(status == 200 ? 0 : 1);
        }
        SpringApplication.run(PolicyServiceApplication.class, args);
    }
}
