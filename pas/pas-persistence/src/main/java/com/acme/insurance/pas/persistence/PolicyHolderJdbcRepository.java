package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.integer;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import com.acme.insurance.pas.domain.PolicyHolder;
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
    jdbc.sql(
            """
            INSERT INTO acmeins.policy_holders (cust_id, cust_type, last_name, first_name,
              middle_init, company_name, addr_line1, addr_line2, city, state_code, zip_code,
              country_code, phone, email, date_of_birth, ssn_last4, tax_id, credit_score,
              risk_tier, gdpr_consent, created_date, last_updated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            h.custId(),
            h.custTypeCode(),
            h.lastName(),
            h.firstName(),
            h.middleInit(),
            h.companyName(),
            h.addrLine1(),
            h.addrLine2(),
            h.city(),
            h.stateCode(),
            h.zipCode(),
            h.countryCode(),
            h.phone(),
            h.email(),
            h.dateOfBirth(),
            h.ssnLast4(),
            h.taxId(),
            h.creditScore(),
            h.riskTierCode(),
            h.gdprConsent(),
            h.createdDate(),
            h.lastUpdated())
        .update();
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
}
