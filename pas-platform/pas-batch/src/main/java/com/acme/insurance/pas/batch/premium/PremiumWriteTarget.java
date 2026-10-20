package com.acme.insurance.pas.batch.premium;

/**
 * Where {@code premiumRecalculationJob} writes its PREMIUMS rows ({@code
 * pas.batch.premium.write-target}).
 */
public enum PremiumWriteTarget {
  /** {@code pas.premiums}: the system of record. Use only once PREMIUMS has been cut over. */
  PAS,
  /**
   * {@code pas_shadow.premiums}, one {@code shadow_run_id} per job instance, for the AB-529
   * parallel run. While DB2 is authoritative, only the migration sync writes {@code pas.premiums}.
   */
  SHADOW
}
