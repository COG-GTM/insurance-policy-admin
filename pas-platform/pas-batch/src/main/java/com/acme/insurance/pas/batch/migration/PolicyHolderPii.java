package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.common.pii.PiiCipher;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encrypts POLICY_HOLDERS PII on the way into {@code pas.policy_holders} exactly as AB-521's JPA
 * converters do (V4): AES-256-GCM ciphertext for DATE_OF_BIRTH (ISO date), SSN_LAST4, TAX_ID and
 * CREDIT_SCORE, and HMAC blind indexes for TAX_ID and SSN_LAST4. Plaintext never reaches the target
 * or the logs.
 */
public final class PolicyHolderPii {

  /** Same purposes as {@code policy.pii.BlindIndexPurpose} so lookups match the policy service. */
  public static final String TAX_ID_PURPOSE = "pas.policy_holders.tax_id";

  public static final String SSN_LAST4_PURPOSE = "pas.policy_holders.ssn_last4";

  static final List<String> SOURCE_PII_COLUMNS =
      List.of("date_of_birth", "ssn_last4", "tax_id", "credit_score");

  static final List<String> TARGET_PII_COLUMNS =
      List.of(
          "date_of_birth_enc",
          "ssn_last4_enc",
          "ssn_last4_bidx",
          "tax_id_enc",
          "tax_id_bidx",
          "credit_score_enc");

  private static final String RECONCILE_PURPOSE = "pas.migration.reconcile.policy_holders";

  private final PiiCipher cipher;
  private final BlindIndexer indexer;

  public PolicyHolderPii(PiiCipher cipher, BlindIndexer indexer) {
    this.cipher = cipher;
    this.indexer = indexer;
  }

  /**
   * Keyed (HMAC, blind-index key) fingerprint of a holder record digest. Reconciliation publishes
   * this instead of an unsalted hash, so report values cannot be used to guess holder PII offline.
   */
  public String fingerprint(String recordDigest) {
    return indexer.index(RECONCILE_PURPOSE, recordDigest);
  }

  static List<String> withSourcePiiColumns(List<String> columns) {
    List<String> all = new ArrayList<>(columns);
    all.addAll(SOURCE_PII_COLUMNS);
    return List.copyOf(all);
  }

  /** Replaces the plaintext PII entries of a source row with their encrypted target columns. */
  public void encryptInPlace(Map<String, Object> row) {
    Object dob = row.remove("date_of_birth");
    String ssn = trimmed(row.remove("ssn_last4"));
    String taxId = trimmed(row.remove("tax_id"));
    Object credit = row.remove("credit_score");
    row.put("date_of_birth_enc", dob == null ? null : cipher.encrypt(isoDate(dob)));
    row.put("ssn_last4_enc", ssn == null ? null : cipher.encrypt(ssn));
    row.put("ssn_last4_bidx", ssn == null ? null : indexer.index(SSN_LAST4_PURPOSE, ssn));
    row.put("tax_id_enc", taxId == null ? null : cipher.encrypt(taxId));
    row.put("tax_id_bidx", taxId == null ? null : indexer.index(TAX_ID_PURPOSE, taxId));
    row.put(
        "credit_score_enc",
        credit == null ? null : cipher.encrypt(Integer.toString(((Number) credit).intValue())));
  }

  /**
   * Replaces the encrypted target columns of a {@code pas.policy_holders} row with their plaintext
   * source equivalents, in memory only, so reconciliation can hash both sides the same way.
   */
  public void decryptInPlace(Map<String, Object> row) {
    String dob = decrypt(row.remove("date_of_birth_enc"));
    String credit = decrypt(row.remove("credit_score_enc"));
    row.put("date_of_birth", dob == null ? null : LocalDate.parse(dob));
    row.put("ssn_last4", decrypt(row.remove("ssn_last4_enc")));
    row.put("tax_id", decrypt(row.remove("tax_id_enc")));
    row.put("credit_score", credit == null ? null : Integer.valueOf(credit));
    row.remove("ssn_last4_bidx");
    row.remove("tax_id_bidx");
  }

  private String decrypt(Object value) {
    return value == null ? null : cipher.decrypt(value.toString());
  }

  private static String isoDate(Object value) {
    if (value instanceof LocalDate date) {
      return date.toString();
    }
    return ((Date) value).toLocalDate().toString();
  }

  private static String trimmed(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().strip();
    return text.isEmpty() ? null : text;
  }
}
