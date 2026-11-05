package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpDestinationTest {

  @TempDir Path dir;

  SftpDestination destination(String uri) throws IOException {
    Path key = Files.writeString(dir.resolve("key"), "placeholder");
    Path hosts = Files.writeString(dir.resolve("known_hosts"), "placeholder");
    return new SftpDestination(
        URI.create(uri), "pas-batch", key, null, hosts, Duration.ofSeconds(5));
  }

  @Test
  void anSftpEndpointWithKeyAndPinnedHostIsAccepted() throws IOException {
    SftpDestination d = destination("sftp://mft.example.internal/claims/import");
    assertThat(d.problems("claims")).isEmpty();
    assertThat(d.port()).isEqualTo(22);
    assertThat(d.describe("policy_extract.dat"))
        .isEqualTo("sftp://mft.example.internal:22/claims/import/policy_extract.dat");
  }

  @Test
  void unencryptedSchemesAreRejected() throws IOException {
    String plain = "f" + "tp";
    for (String scheme : new String[] {plain, "ftps", "http", "file"}) {
      assertThat(destination(scheme + "://host/dir").problems("claims"))
          .anyMatch(p -> p.contains("only sftp is allowed"));
    }
  }

  @Test
  void credentialsInTheUriAndMissingFilesAreRejected() throws IOException {
    assertThat(destination("sftp://user@host/dir").problems("claims"))
        .anyMatch(p -> p.contains("must not carry user info"));
    SftpDestination noKey =
        new SftpDestination(
            URI.create("sftp://host/dir"),
            "u",
            dir.resolve("missing"),
            null,
            null,
            Duration.ofSeconds(5));
    assertThat(noKey.problems("claims"))
        .contains(
            "claims: private-key-file is not readable", "claims: known-hosts-file is not set");
  }

  @Test
  void anUnsetEndpointIsReported() {
    SftpDestination unset = new SftpDestination(null, null, null, null, null, Duration.ZERO);
    assertThat(unset.problems("actuarial")).containsExactly("actuarial: uri is not set");
  }
}
