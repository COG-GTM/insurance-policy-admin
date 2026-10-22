package com.acme.insurance.pas.pii;

import java.time.LocalDate;

public final class PolicyHolderPii {
  private PolicyHolderPii() {}

  public static LocalDate decryptDate(byte[] value, PiiCipher cipher, PiiContext context) {
    return value == null ? null : LocalDate.parse(cipher.decrypt(value, context));
  }

  public static byte[] encryptDate(LocalDate value, PiiCipher cipher, PiiContext context) {
    return value == null ? null : cipher.encrypt(value.toString(), context);
  }

  public static byte[] encryptInteger(Integer value, PiiCipher cipher, PiiContext context) {
    return value == null ? null : cipher.encrypt(value.toString(), context);
  }

  public static Integer decryptInteger(byte[] value, PiiCipher cipher, PiiContext context) {
    return value == null ? null : Integer.valueOf(cipher.decrypt(value, context));
  }

  public static byte[] encryptString(String value, PiiCipher cipher, PiiContext context) {
    return value == null ? null : cipher.encrypt(value, context);
  }

  public static String decryptString(byte[] value, PiiCipher cipher, PiiContext context) {
    return value == null ? null : cipher.decrypt(value, context);
  }
}
