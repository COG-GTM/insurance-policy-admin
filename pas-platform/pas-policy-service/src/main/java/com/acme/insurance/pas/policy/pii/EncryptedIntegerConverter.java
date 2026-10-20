package com.acme.insurance.pas.policy.pii;

import com.acme.insurance.pas.common.pii.PiiCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** AES-256-GCM encryption of an Integer attribute (used for credit score). */
@Converter
public class EncryptedIntegerConverter implements AttributeConverter<Integer, String> {

  private final PiiCipher cipher;

  public EncryptedIntegerConverter(PiiCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public String convertToDatabaseColumn(Integer attribute) {
    return attribute == null ? null : cipher.encrypt(attribute.toString());
  }

  @Override
  public Integer convertToEntityAttribute(String dbData) {
    return dbData == null ? null : Integer.valueOf(cipher.decrypt(dbData));
  }
}
