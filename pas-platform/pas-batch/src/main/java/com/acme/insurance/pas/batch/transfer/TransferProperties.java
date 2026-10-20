package com.acme.insurance.pas.batch.transfer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pas.transfer.*}: SFTP destinations. Hosts, users, key files and known_hosts come from the
 * environment / mounted secrets only (see {@code .env.example} and the extracts runbook).
 */
@ConfigurationProperties("pas.transfer")
public record TransferProperties(
    boolean enabled,
    Duration connectTimeout,
    Duration authTimeout,
    Retry retry,
    Map<String, Destination> destinations) {

  public TransferProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(30) : connectTimeout;
    authTimeout = authTimeout == null ? Duration.ofSeconds(30) : authTimeout;
    retry = retry == null ? new Retry(0, null, 0, null) : retry;
    destinations = destinations == null ? Map.of() : Map.copyOf(destinations);
  }

  /** Exponential backoff between whole-delivery attempts. */
  public record Retry(
      int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
    static final int DEFAULT_BACKOFF_MULTIPLIER = 2;

    public Retry {
      maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
      initialBackoff = initialBackoff == null ? Duration.ofSeconds(30) : initialBackoff;
      multiplier = multiplier < 1 ? DEFAULT_BACKOFF_MULTIPLIER : multiplier;
      maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
    }

    public Duration backoffBefore(int nextAttempt) {
      double factor = Math.pow(multiplier, Math.max(0, nextAttempt - 2));
      long millis = (long) Math.min(initialBackoff.toMillis() * factor, maxBackoff.toMillis());
      return Duration.ofMillis(millis);
    }
  }

  /**
   * One SFTP endpoint. Authentication is public-key only; the server host key must be present in
   * {@code knownHostsFile} (unknown or changed keys are rejected).
   */
  public record Destination(
      String host,
      int port,
      String username,
      String privateKeyFile,
      String privateKeyPassphrase,
      String knownHostsFile,
      String remoteDirectory) {

    public Destination {
      port = port <= 0 ? 22 : port;
    }

    public Path privateKey() {
      return Path.of(privateKeyFile);
    }

    public Path knownHosts() {
      return Path.of(knownHostsFile);
    }
  }

  public Destination destination(String name) {
    Destination destination = destinations.get(name);
    if (destination == null) {
      throw new IllegalArgumentException("no pas.transfer.destinations." + name + " configured");
    }
    return destination;
  }

  /** Fail fast at startup when delivery is enabled but a destination is incomplete. */
  public void validate(Collection<String> required) {
    List<String> problems = new ArrayList<>();
    for (String name : required) {
      Destination d = destinations.get(name);
      String prefix = "pas.transfer.destinations." + name + ".";
      if (d == null) {
        problems.add(prefix + "* missing");
        continue;
      }
      if (blank(d.host())) {
        problems.add(prefix + "host");
      }
      if (blank(d.username())) {
        problems.add(prefix + "username");
      }
      if (blank(d.remoteDirectory())) {
        problems.add(prefix + "remote-directory");
      }
      if (blank(d.privateKeyFile()) || !Files.isReadable(d.privateKey())) {
        problems.add(prefix + "private-key-file (readable key file)");
      }
      if (blank(d.knownHostsFile()) || !Files.isReadable(d.knownHosts())) {
        problems.add(prefix + "known-hosts-file (readable known_hosts)");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "pas.transfer.enabled=true but SFTP configuration is incomplete: " + problems);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
