package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.pii.BlindIndex;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiContext;
import com.acme.insurance.pas.pii.PolicyHolderPii;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyHolderRepository {
  private static final String SELECT =
      """
      SELECT cust_id, cust_type, last_name, first_name, middle_init, company_name, addr_line1,
             addr_line2, city, state_code, zip_code, country_code, phone, email, date_of_birth_enc,
             ssn_last4_enc, tax_id_enc, credit_score_enc, risk_tier, gdpr_consent, created_date,
             last_updated
      FROM pas.policy_holders
      """;

  private final JdbcClient jdbc;
  private final PiiCipher cipher;
  private final BlindIndex blindIndex;

  public PolicyHolderRepository(JdbcClient jdbc, PiiCipher cipher, BlindIndex blindIndex) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.blindIndex = blindIndex;
  }

  public void insert(PolicyHolder holder) {
    String custId = JdbcValues.trimChar(holder.custId());
    jdbc.sql(
            """
            INSERT INTO pas.policy_holders (
                cust_id, cust_type, last_name, first_name, middle_init, company_name, addr_line1,
                addr_line2, city, state_code, zip_code, country_code, phone, email,
                date_of_birth_enc, ssn_last4_enc, tax_id_enc, credit_score_enc, tax_id_bidx,
                risk_tier, gdpr_consent, created_date, last_updated
            ) VALUES (
                :custId, :custType, :lastName, :firstName, :middleInit, :companyName, :addrLine1,
                :addrLine2, :city, :stateCode, :zipCode, :countryCode, :phone, :email,
                :dateOfBirth, :ssnLast4, :taxId, :creditScore, :taxIdIndex,
                :riskTier, :gdprConsent, :createdDate, :lastUpdated
            )
            """)
        .param("custId", custId)
        .param("custType", holder.custType().raw())
        .param("lastName", holder.lastName())
        .param("firstName", holder.firstName())
        .param("middleInit", holder.middleInit())
        .param("companyName", holder.companyName())
        .param("addrLine1", holder.addrLine1())
        .param("addrLine2", holder.addrLine2())
        .param("city", holder.city())
        .param("stateCode", holder.stateCode())
        .param("zipCode", holder.zipCode())
        .param("countryCode", holder.countryCode())
        .param("phone", holder.phone())
        .param("email", holder.email())
        .param(
            "dateOfBirth",
            PolicyHolderPii.encryptDate(
                holder.dateOfBirth(), cipher, context(custId, "date_of_birth_enc")))
        .param(
            "ssnLast4",
            PolicyHolderPii.encryptString(
                holder.ssnLast4(), cipher, context(custId, "ssn_last4_enc")))
        .param(
            "taxId",
            PolicyHolderPii.encryptString(holder.taxId(), cipher, context(custId, "tax_id_enc")))
        .param(
            "creditScore",
            PolicyHolderPii.encryptInteger(
                holder.creditScore(), cipher, context(custId, "credit_score_enc")))
        .param("taxIdIndex", blindIndex.of(holder.taxId()))
        .param("riskTier", holder.riskTier() == null ? null : holder.riskTier().raw())
        .param("gdprConsent", holder.gdprConsent() == null ? null : holder.gdprConsent().raw())
        .param("createdDate", holder.createdDate())
        .param("lastUpdated", holder.lastUpdated())
        .update();
  }

  public Optional<PolicyHolder> findByCustId(String custId) {
    return jdbc.sql(SELECT + " WHERE cust_id = :custId")
        .param("custId", custId)
        .query((row, rowNumber) -> map(row))
        .optional();
  }

  public boolean existsByCustId(String custId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pas.policy_holders WHERE cust_id = :custId)")
        .param("custId", custId)
        .query(Boolean.class)
        .single();
  }

  public List<PolicyHolder> findByTaxId(String taxId) {
    return jdbc.sql(SELECT + " WHERE tax_id_bidx = :taxIdIndex ORDER BY cust_id")
        .param("taxIdIndex", blindIndex.of(taxId))
        .query((row, rowNumber) -> map(row))
        .list();
  }

  private PolicyHolder map(java.sql.ResultSet row) throws java.sql.SQLException {
    String custId = JdbcValues.charValue(row, "cust_id");
    return new PolicyHolder(
        custId,
        JdbcValues.coded(row, "cust_type", CustomerType.class),
        row.getString("last_name"),
        row.getString("first_name"),
        JdbcValues.charValue(row, "middle_init"),
        row.getString("company_name"),
        row.getString("addr_line1"),
        row.getString("addr_line2"),
        row.getString("city"),
        JdbcValues.charValue(row, "state_code"),
        JdbcValues.charValue(row, "zip_code"),
        JdbcValues.charValue(row, "country_code"),
        row.getString("phone"),
        row.getString("email"),
        PolicyHolderPii.decryptDate(
            row.getBytes("date_of_birth_enc"), cipher, context(custId, "date_of_birth_enc")),
        PolicyHolderPii.decryptString(
            row.getBytes("ssn_last4_enc"), cipher, context(custId, "ssn_last4_enc")),
        PolicyHolderPii.decryptString(
            row.getBytes("tax_id_enc"), cipher, context(custId, "tax_id_enc")),
        PolicyHolderPii.decryptInteger(
            row.getBytes("credit_score_enc"), cipher, context(custId, "credit_score_enc")),
        JdbcValues.coded(row, "risk_tier", RiskTier.class),
        JdbcValues.coded(row, "gdpr_consent", YesNo.class),
        JdbcValues.date(row, "created_date"),
        JdbcValues.timestamp(row, "last_updated"));
  }

  private static PiiContext context(String custId, String column) {
    return new PiiContext("policy_holders", column, custId);
  }

  /** ACH endorsement (Q-13). Address columns are not among the V5 encrypted PII columns. */
  public int changeAddress(
      String custId,
      String addrLine1,
      String addrLine2,
      String city,
      String stateCode,
      String zipCode) {
    return jdbc.sql(
            "UPDATE pas.policy_holders SET addr_line1 = :addrLine1, addr_line2 = :addrLine2, city = :city, state_code = :stateCode, zip_code = :zipCode, last_updated = now() WHERE cust_id = :custId")
        .param("addrLine1", addrLine1)
        .param("addrLine2", addrLine2)
        .param("city", city)
        .param("stateCode", stateCode)
        .param("zipCode", zipCode)
        .param("custId", custId)
        .update();
  }
}
