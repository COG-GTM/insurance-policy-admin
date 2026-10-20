package com.acme.insurance.pas.batch.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransferPropertiesTest {

  @Test
  void backoffIsExponentialAndCapped() {
    TransferProperties.Retry retry =
        new TransferProperties.Retry(5, Duration.ofSeconds(30), 2, Duration.ofMinutes(1));
    assertThat(retry.backoffBefore(2)).isEqualTo(Duration.ofSeconds(30));
    assertThat(retry.backoffBefore(3)).isEqualTo(Duration.ofSeconds(60));
    assertThat(retry.backoffBefore(4)).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void enabledTransferFailsFastWhenDestinationIncomplete() {
    TransferProperties props =
        new TransferProperties(
            true,
            null,
            null,
            null,
            Map.of(
                "claims",
                new TransferProperties.Destination(
                    "sftp.example", 22, "pas", "/nonexistent/key", null, "", "/claims/import")));
    assertThatThrownBy(() -> props.validate(List.of("claims", "actuarial")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("claims.private-key-file")
        .hasMessageContaining("claims.known-hosts-file")
        .hasMessageContaining("actuarial.* missing");
  }
}
