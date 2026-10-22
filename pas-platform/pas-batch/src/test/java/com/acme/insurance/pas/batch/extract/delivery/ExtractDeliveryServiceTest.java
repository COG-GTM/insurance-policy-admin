package com.acme.insurance.pas.batch.extract.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ExtractDeliveryServiceTest {
  @TempDir Path directory;

  @Test
  void retriesTheWholeFileTransferAfterTransientIoFailure() throws Exception {
    Path output = Files.writeString(directory.resolve("extract.dat"), "sample data");
    AtomicInteger connections = new AtomicInteger();
    FakeStore store = new FakeStore(connections, false);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SftpProperties properties = properties();
    java.util.List<Duration> pauses = new java.util.ArrayList<>();
    ExtractDeliveryService service =
        new ExtractDeliveryService(
            store,
            properties,
            new ExtractRetryProperties(3, Duration.ofSeconds(5), 2, Duration.ofSeconds(60)),
            jdbc,
            java.time.Clock.systemUTC(),
            pauses::add);
    var file = new ExtractDeliveryService.DeliveryFile(output, "extract.dat", "daily-policy/v1", 1);

    int attempts =
        service.deliver(
            "claims", "dailyExtractJob", 42, LocalDate.of(2025, 2, 10), java.util.List.of(file));

    assertThat(attempts).isEqualTo(2);
    assertThat(connections).hasValue(2);
    assertThat(pauses).containsExactly(Duration.ofSeconds(5));
    assertThat(store.contents.get("/claims/import/extract.dat"))
        .isEqualTo("sample data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    verify(jdbc).update(any(String.class), any(Object[].class));
  }

  @Test
  void doesNotRetryHostKeyOrAuthenticationFailures() throws Exception {
    Path output = Files.writeString(directory.resolve("extract.dat"), "sample data");
    AtomicInteger connections = new AtomicInteger();
    FakeStore store = new FakeStore(connections, true);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ExtractDeliveryService service =
        new ExtractDeliveryService(
            store,
            properties(),
            new ExtractRetryProperties(3, Duration.ofMillis(1), 2, Duration.ofSeconds(1)),
            jdbc,
            java.time.Clock.systemUTC(),
            ignored -> {
              throw new AssertionError("Should not back off after a security failure");
            });
    var file = new ExtractDeliveryService.DeliveryFile(output, "extract.dat", "daily-policy/v1", 1);

    assertThatThrownBy(
            () ->
                service.deliver(
                    "claims",
                    "dailyExtractJob",
                    7,
                    LocalDate.of(2025, 2, 10),
                    java.util.List.of(file)))
        .isInstanceOf(TransferSecurityException.class);
    assertThat(connections).hasValue(1);
  }

  private static SftpProperties properties() {
    SftpProperties properties = new SftpProperties();
    Map<String, SftpProperties.Target> targets = new HashMap<>();
    targets.put(
        "claims",
        new SftpProperties.Target(
            "localhost",
            22,
            "claims",
            "/key",
            null,
            "/known-hosts",
            "/claims/import",
            Duration.ofSeconds(1)));
    properties.setTargets(targets);
    return properties;
  }

  private static final class FakeStore implements RemoteFileStore {
    private final AtomicInteger connections;
    private final boolean securityFailure;
    private final Map<String, byte[]> contents = new HashMap<>();
    private boolean transientFailure = true;

    private FakeStore(AtomicInteger connections, boolean securityFailure) {
      this.connections = connections;
      this.securityFailure = securityFailure;
    }

    @Override
    public Session connect(SftpProperties.Target target) throws IOException {
      connections.incrementAndGet();
      if (securityFailure) {
        throw new TransferSecurityException("test authentication rejection", null);
      }
      return new Session() {
        @Override
        public void delete(String remotePath) {
          contents.remove(remotePath);
        }

        @Override
        public void upload(Path localFile, String remotePath) throws IOException {
          if (transientFailure) {
            transientFailure = false;
            throw new IOException("transient");
          }
          contents.put(remotePath, Files.readAllBytes(localFile));
        }

        @Override
        public long size(String remotePath) {
          return contents.get(remotePath).length;
        }

        @Override
        public void rename(String source, String destination) {
          contents.put(destination, contents.remove(source));
        }

        @Override
        public void write(byte[] content, String remotePath) {
          contents.put(remotePath, content.clone());
        }

        @Override
        public void close() {}
      };
    }
  }
}
