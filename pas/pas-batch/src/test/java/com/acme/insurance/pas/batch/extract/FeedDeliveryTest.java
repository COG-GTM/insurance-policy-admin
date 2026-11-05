package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.acme.insurance.pas.persistence.DeliveryReceipt;
import com.acme.insurance.pas.persistence.ExtractJdbcRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class FeedDeliveryTest {

  @TempDir Path dir;
  ExtractJdbcRepository receipts = mock(ExtractJdbcRepository.class);
  SimpleMeterRegistry meters = new SimpleMeterRegistry();
  List<Duration> waits = new ArrayList<>();
  StagedFile file;
  ExtractProperties properties;

  @BeforeEach
  void stage() throws IOException {
    Path data = Files.writeString(dir.resolve("PAS.CLAIMS.POLICY.D20261105"), "x\r\n");
    file = new StagedFile(ExtractFeed.CLAIMS_POLICY, LocalDate.of(2026, 11, 5), data, 1, 3, "ab");
    SftpDestination claims =
        new SftpDestination(
            URI.create("sftp://mft.example.internal/claims/import"),
            "pas",
            null,
            null,
            null,
            Duration.ofSeconds(1));
    properties =
        new ExtractProperties(
            dir,
            ZoneOffset.UTC,
            StandardCharsets.ISO_8859_1,
            ExtractProperties.RecordSeparator.CRLF,
            true,
            new ExtractProperties.Retry(3, Duration.ofSeconds(10)),
            claims,
            claims);
  }

  FeedDelivery delivery(FeedTransfer transfer) {
    return new FeedDelivery(
        transfer,
        receipts,
        properties,
        meters,
        Clock.fixed(Instant.parse("2026-11-06T04:10:00Z"), ZoneOffset.UTC),
        waits::add);
  }

  @Test
  void retriesWithBackoffThenConfirmsAndPurges() {
    int[] calls = {0};
    List<String> manifests = new ArrayList<>();
    FeedTransfer flaky =
        (d, f, manifest) -> {
          if (++calls[0] < 3) {
            throw new FeedTransfer.DeliveryException("connection reset");
          }
          manifests.add(read(manifest));
        };
    delivery(flaky).deliver(file, 42L);

    assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20));
    assertThat(manifests).containsExactly("ab  policy_extract.dat\n");
    ArgumentCaptor<DeliveryReceipt> receipt = ArgumentCaptor.forClass(DeliveryReceipt.class);
    verify(receipts).recordDelivery(receipt.capture());
    assertThat(receipt.getValue().status()).isEqualTo(DeliveryReceipt.Status.CONFIRMED);
    assertThat(receipt.getValue().attempts()).isEqualTo(3);
    assertThat(receipt.getValue().jobExecutionId()).isEqualTo(42L);
    assertThat(receipt.getValue().destination())
        .isEqualTo("sftp://mft.example.internal:22/claims/import/policy_extract.dat");
    assertThat(file.path()).doesNotExist();
    assertThat(
            meters
                .counter("pas.extract.deliveries", "feed", "claims-policy", "outcome", "confirmed")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void exhaustedRetriesRecordAFailureAndKeepTheStagedFile() {
    FeedTransfer down =
        (d, f, m) -> {
          throw new FeedTransfer.DeliveryException("host key mismatch");
        };
    assertThatThrownBy(() -> delivery(down).deliver(file, 7L))
        .isInstanceOf(FeedTransfer.DeliveryException.class)
        .hasMessageContaining("not delivered after 3 attempts");

    ArgumentCaptor<DeliveryReceipt> receipt = ArgumentCaptor.forClass(DeliveryReceipt.class);
    verify(receipts).recordDelivery(receipt.capture());
    assertThat(receipt.getValue().status()).isEqualTo(DeliveryReceipt.Status.FAILED);
    assertThat(receipt.getValue().detail()).isEqualTo("host key mismatch");
    assertThat(receipt.getValue().confirmedAt()).isNull();
    assertThat(file.path()).exists();
    verify(receipts, org.mockito.Mockito.times(1)).recordDelivery(any());
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
