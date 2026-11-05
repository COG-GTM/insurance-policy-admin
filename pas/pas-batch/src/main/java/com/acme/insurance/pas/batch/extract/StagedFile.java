package com.acme.insurance.pas.batch.extract;

import java.nio.file.Path;
import java.time.LocalDate;

/** A feed file written to the staging directory, with the checksum computed while writing. */
public record StagedFile(
    ExtractFeed feed,
    LocalDate businessDate,
    Path path,
    long recordCount,
    long byteCount,
    String sha256) {

  /** {@code sha256sum} format: consumers verify with {@code sha256sum -c}. */
  public String manifestLine() {
    return sha256 + "  " + feed.remoteName() + "\n";
  }

  public String manifestName() {
    return feed.remoteName() + ".sha256";
  }
}
