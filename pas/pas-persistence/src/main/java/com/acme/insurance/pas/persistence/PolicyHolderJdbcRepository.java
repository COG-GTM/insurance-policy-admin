package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.integer;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.PolicyHolderContact;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ACMEINS.POLICY_HOLDERS. Reads and writes clear values, so only services that need them (new
 * business, underwriting) use it; reporting and support read acmeins.policy_holders_masked. This
 * class logs nothing and SQL errors carry bind markers, not values.
 */
public class PolicyHolderJdbcRepository {

  private final JdbcClient jdbc;

  public PolicyHolderJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PolicyHolder h) {
    new Insert("acmeins.policy_holders")
        .set("cust_id", h.custId())
        .setOrDefault("cust_type", h.custTypeCode())
        .set("last_name", h.lastName())
        .set("first_name", h.firstName())
        .set("middle_init", h.middleInit())
        .set("company_name", h.companyName())
        .set("addr_line1", h.addrLine1())
        .set("addr_line2", h.addrLine2())
        .set("city", h.city())
        .set("state_code", h.stateCode())
        .set("zip_code", h.zipCode())
        .set("country_code", h.countryCode())
        .set("phone", h.phone())
        .set("email", h.email())
        .set("date_of_birth", h.dateOfBirth())
        .set("ssn_last4", h.ssnLast4())
        .set("tax_id", h.taxId())
        .set("credit_score", h.creditScore())
        .set("risk_tier", h.riskTierCode())
        .set("gdpr_consent", h.gdprConsent())
        .setOrDefault("created_date", h.createdDate())
        .setOrDefault("last_updated", h.lastUpdated())
        .execute(jdbc);
  }

  public Optional<PolicyHolder> find(String custId) {
    return jdbc.sql("SELECT * FROM acmeins.policy_holders WHERE cust_id = ?")
        .param(custId)
        .query(
            (rs, row) ->
                new PolicyHolder(
                    text(rs, "cust_id"),
                    text(rs, "cust_type"),
                    text(rs, "last_name"),
                    text(rs, "first_name"),
                    text(rs, "middle_init"),
                    text(rs, "company_name"),
                    text(rs, "addr_line1"),
                    text(rs, "addr_line2"),
                    text(rs, "city"),
                    text(rs, "state_code"),
                    text(rs, "zip_code"),
                    text(rs, "country_code"),
                    text(rs, "phone"),
                    text(rs, "email"),
                    date(rs, "date_of_birth"),
                    text(rs, "ssn_last4"),
                    text(rs, "tax_id"),
                    integer(rs, "credit_score"),
                    text(rs, "risk_tier"),
                    text(rs, "gdpr_consent"),
                    date(rs, "created_date"),
                    timestamp(rs, "last_updated")))
        .optional();
  }

  /**
   * The inquiry fields of a holder in clear (POLQRY 3000-READ-CUSTOMER). Callers must hold the
   * entitlement to see personal data; everyone else uses {@link #findMaskedContact(String)}.
   */
  public Optional<PolicyHolderContact> findContact(String custId) {
    return jdbc.sql(
            "SELECT cust_id, cust_type, last_name, first_name, company_name, phone, email"
                + " FROM acmeins.policy_holders WHERE cust_id = ?")
        .param(custId)
        .query(
            (rs, row) ->
                new PolicyHolderContact(
                    text(rs, "cust_id"),
                    text(rs, "cust_type"),
                    text(rs, "last_name"),
                    text(rs, "first_name"),
                    text(rs, "company_name"),
                    text(rs, "phone"),
                    text(rs, "email"),
                    false))
        .optional();
  }

  /**
   * The inquiry fields of a holder from acmeins.policy_holders_masked, so the masking rules live in
   * one place (V12). The view carries no phone number, so it is always null here.
   */
  public Optional<PolicyHolderContact> findMaskedContact(String custId) {
    return jdbc.sql(
            "SELECT cust_id, cust_type, last_name, first_name, company_name, email"
                + " FROM acmeins.policy_holders_masked WHERE cust_id = ?")
        .param(custId)
        .query(
            (rs, row) ->
                new PolicyHolderContact(
                    text(rs, "cust_id"),
                    text(rs, "cust_type"),
                    text(rs, "last_name"),
                    text(rs, "first_name"),
                    text(rs, "company_name"),
                    null,
                    text(rs, "email"),
                    true))
        .optional();
  }
}
