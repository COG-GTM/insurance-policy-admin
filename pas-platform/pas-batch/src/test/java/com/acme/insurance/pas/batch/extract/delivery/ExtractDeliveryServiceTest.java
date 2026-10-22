package com.acme.insurance.pas.batch.extract.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
  void removesSetMarkersAndTemporaryFilesAfterTerminalFileFailure() throws Exception {
    Path policy = Files.writeString(directory.resolve("policy.dat"), "policy");
    Path coverage = Files.writeString(directory.resolve("coverage.dat"), "coverage");
    AtomicInteger connections = new AtomicInteger();
    FakeStore store = new FakeStore(connections, false);
    store.transientFailure = false;
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ExtractDeliveryService service =
        new ExtractDeliveryService(
            store,
            properties(),
            new ExtractRetryProperties(3, Duration.ZERO, 2, Duration.ZERO),
            jdbc,
            java.time.Clock.systemUTC(),
            ignored -> {});
    List<ExtractDeliveryService.DeliveryFile> files =
        List.of(
            new ExtractDeliveryService.DeliveryFile(
                policy, "policy_extract.dat", "daily-policy/v1", 1),
            new ExtractDeliveryService.DeliveryFile(
                coverage, "coverage_extract.dat", "daily-coverage/v1", 1));

    service.deliver("claims", "dailyExtractJob", 41, LocalDate.of(2025, 2, 10), files);

    assertThat(store.contents)
        .containsKeys(
            "/claims/import/policy_extract.dat.done", "/claims/import/coverage_extract.dat.done");
    store.failUploadPath = "/claims/import/coverage_extract.dat.42.tmp";

    assertThatThrownBy(
            () ->
                service.deliver("claims", "dailyExtractJob", 42, LocalDate.of(2025, 2, 10), files))
        .isInstanceOf(IOException.class);

    assertThat(store.contents)
        .doesNotContainKeys(
            "/claims/import/policy_extract.dat.done",
            "/claims/import/coverage_extract.dat.done",
            "/claims/import/coverage_extract.dat.42.tmp")
        .doesNotContainKey("/claims/import/policy_extract.dat.42.tmp");
    assertThat(store.contents.keySet()).noneMatch(path -> path.endsWith(".tmp"));

    ArgumentCaptor<Object[]> updates = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(4)).update(any(String.class), updates.capture());
    assertThat(updates.getAllValues().subList(2, 4))
        .allSatisfy(
            values -> {
              assertThat(values[9]).isEqualTo("FAILED");
              assertThat(values[10]).isEqualTo(3);
              assertThat(values[11]).isEqualTo("IOException");
            });
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
    private String failUploadPath;

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
          byte[] content = Files.readAllBytes(localFile);
          contents.put(remotePath, content);
          if (remotePath.equals(failUploadPath)) {
            throw new IOException("simulated partial upload");
          }
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
