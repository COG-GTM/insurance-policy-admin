package com.acme.insurance.pas.copybook;

import java.nio.charset.Charset;

public enum CopybookCharset {
  EBCDIC(Charset.forName("IBM037")),
  ASCII(Charset.forName("ISO-8859-1"));

  private final Charset charset;

  CopybookCharset(Charset charset) {
    this.charset = charset;
  }

  public Charset charset() {
    return charset;
  }
}
