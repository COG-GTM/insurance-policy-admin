package com.acme.insurance.pas.batch.extract;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Claims and Actuarial feeds (AB-527). Endpoints, user names, key files and host
 * fingerprints are injected from the secrets manager through environment variables or mounted
 * files; nothing here has a credential default.
 */
@ConfigurationProperties("pas.extract")
public record ExtractProperties(
    Path stagingDir,
    @DefaultValue("America/New_York") ZoneId businessZone,
    @DefaultValue("ISO-8859-1") Charset charset,
    @DefaultValue("CRLF") RecordSeparator recordSeparator,
    @DefaultValue("true") boolean purgeAfterDelivery,
    @DefaultValue Retry retry,
    @DefaultValue SftpDestination claims,
    @DefaultValue SftpDestination actuarial) {

  public ExtractProperties {
    charset = charset == null ? StandardCharsets.ISO_8859_1 : charset;
  }

  public SftpDestination destination(ExtractFeed.Consumer consumer) {
    return switch (consumer) {
      case CLAIMS -> claims;
      case ACTUARIAL -> actuarial;
    };
  }

  /** Line ending appended after each record. FTP text mode delivered CRLF to Windows hosts. */
  public enum RecordSeparator {
    CRLF("\r\n"),
    LF("\n"),
    NONE("");

    private final String value;

    RecordSeparator(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public record Retry(@DefaultValue("3") int maxAttempts, @DefaultValue("PT1M") Duration backoff) {}
}
