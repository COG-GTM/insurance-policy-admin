package com.acme.insurance.pas.copybook;

import java.nio.charset.Charset;

public enum CopybookCharset {
  EBCDIC(Charset.forName("IBM037")),
  ASCII(Charset.forName("ISO-8859-1"));

  private final Charset charset;

  public static CopybookCharset fromName(String value) {
    return switch (value.toUpperCase(java.util.Locale.ROOT)) {
      case "IBM037", "EBCDIC" -> EBCDIC;
      case "ASCII", "ISO-8859-1" -> ASCII;
      default -> throw new IllegalArgumentException("Unsupported copybook charset: " + value);
    };
  }

  CopybookCharset(Charset charset) {
    this.charset = charset;
  }

  public Charset charset() {
    return charset;
  }
}
