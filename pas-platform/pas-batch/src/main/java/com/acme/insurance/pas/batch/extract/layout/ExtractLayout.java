package com.acme.insurance.pas.batch.extract.layout;

import com.acme.insurance.pas.copybook.CopybookCharset;
import com.acme.insurance.pas.copybook.CopybookCodec;
import com.acme.insurance.pas.copybook.RecordLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public record ExtractLayout(
    String id,
    int version,
    Kind kind,
    Charset charset,
    String recordSeparator,
    int maximumRecordLength,
    RecordLayout recordLayout) {
  public enum Kind {
    FIXED,
    CSV
  }

  public ExtractLayout {
    if (id == null || version < 1 || kind == null || charset == null || recordSeparator == null) {
      throw new IllegalArgumentException("Invalid extract layout");
    }
    if (kind == Kind.FIXED && recordLayout == null) {
      throw new IllegalArgumentException("Fixed layout requires a record layout");
    }
  }

  public String versionString() {
    return id + "/v" + version;
  }

  public CopybookCodec codec() {
    if (recordLayout == null) {
      throw new IllegalStateException("CSV layout does not use a copybook codec");
    }
    return new CopybookCodec(recordLayout, CopybookCharset.ASCII);
  }

  public static ExtractLayout fixed(String id, int version, int length, RecordLayout layout) {
    return new ExtractLayout(
        id, version, Kind.FIXED, StandardCharsets.ISO_8859_1, "\r\n", length, layout);
  }

  public static ExtractLayout csv(String id, int version, int maximumLength) {
    return new ExtractLayout(
        id, version, Kind.CSV, StandardCharsets.ISO_8859_1, "\r\n", maximumLength, null);
  }
}
