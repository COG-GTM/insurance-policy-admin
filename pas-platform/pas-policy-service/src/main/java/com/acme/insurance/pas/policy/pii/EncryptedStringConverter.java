package com.acme.insurance.pas.policy.pii;

import com.acme.insurance.pas.common.pii.PiiCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * AES-256-GCM encryption of a String attribute. Instantiated by Spring's Hibernate bean container.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

  private final PiiCipher cipher;

  public EncryptedStringConverter(PiiCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public String convertToDatabaseColumn(String attribute) {
    return cipher.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    return cipher.decrypt(dbData);
  }
}
