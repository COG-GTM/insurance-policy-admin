package com.acme.insurance.pas.policy.pii;

import com.acme.insurance.pas.common.pii.PiiCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDate;

/** AES-256-GCM encryption of a LocalDate attribute (plaintext is ISO {@code yyyy-MM-dd}). */
@Converter
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {

  private final PiiCipher cipher;

  public EncryptedLocalDateConverter(PiiCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public String convertToDatabaseColumn(LocalDate attribute) {
    return attribute == null ? null : cipher.encrypt(attribute.toString());
  }

  @Override
  public LocalDate convertToEntityAttribute(String dbData) {
    return dbData == null ? null : LocalDate.parse(cipher.decrypt(dbData));
  }
}
