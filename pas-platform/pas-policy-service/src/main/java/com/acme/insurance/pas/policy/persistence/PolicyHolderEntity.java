package com.acme.insurance.pas.policy.persistence;

import com.acme.insurance.pas.policy.pii.EncryptedIntegerConverter;
import com.acme.insurance.pas.policy.pii.EncryptedLocalDateConverter;
import com.acme.insurance.pas.policy.pii.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * pas.policy_holders (ACMEINS.POLICY_HOLDERS, CUSTOMER-RECORD.cpy). Regulated fields are encrypted
 * by converters and indexed by {@link PolicyHolderBlindIndexListener}; {@link #toString()} never
 * includes personal data.
 */
@Entity
@Table(name = "policy_holders")
@EntityListeners(PolicyHolderBlindIndexListener.class)
public class PolicyHolderEntity {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_id", length = 10, nullable = false)
  private String custId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_type", length = 1, nullable = false)
  private String custType;

  @Column(name = "last_name", length = 30)
  private String lastName;

  @Column(name = "first_name", length = 20)
  private String firstName;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "middle_init", length = 1)
  private String middleInit;

  @Column(name = "company_name", length = 50)
  private String companyName;

  @Column(name = "addr_line1", length = 40)
  private String addrLine1;

  @Column(name = "addr_line2", length = 40)
  private String addrLine2;

  @Column(name = "city", length = 25)
  private String city;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "state_code", length = 2)
  private String stateCode;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "zip_code", length = 10)
  private String zipCode;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "country_code", length = 3)
  private String countryCode;

  @Column(name = "phone", length = 15)
  private String phone;

  @Column(name = "email", length = 60)
  private String email;

  @Convert(converter = EncryptedLocalDateConverter.class)
  @Column(name = "date_of_birth_enc", length = 255)
  private LocalDate dateOfBirth;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "ssn_last4_enc", length = 255)
  private String ssnLast4;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "ssn_last4_bidx", length = 64)
  private String ssnLast4BlindIndex;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "tax_id_enc", length = 255)
  private String taxId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tax_id_bidx", length = 64)
  private String taxIdBlindIndex;

  @Convert(converter = EncryptedIntegerConverter.class)
  @Column(name = "credit_score_enc", length = 255)
  private Integer creditScore;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "risk_tier", length = 1)
  private String riskTier;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "gdpr_consent", length = 1)
  private String gdprConsent;

  @Column(name = "created_date", nullable = false)
  private LocalDate createdDate;

  @Column(name = "last_updated", nullable = false)
  private LocalDateTime lastUpdated;

  public String getCustId() {
    return custId;
  }

  public void setCustId(String custId) {
    this.custId = custId;
  }

  public String getCustType() {
    return custType;
  }

  public void setCustType(String custType) {
    this.custType = custType;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getMiddleInit() {
    return middleInit;
  }

  public void setMiddleInit(String middleInit) {
    this.middleInit = middleInit;
  }

  public String getCompanyName() {
    return companyName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }

  public String getAddrLine1() {
    return addrLine1;
  }

  public void setAddrLine1(String addrLine1) {
    this.addrLine1 = addrLine1;
  }

  public String getAddrLine2() {
    return addrLine2;
  }

  public void setAddrLine2(String addrLine2) {
    this.addrLine2 = addrLine2;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getStateCode() {
    return stateCode;
  }

  public void setStateCode(String stateCode) {
    this.stateCode = stateCode;
  }

  public String getZipCode() {
    return zipCode;
  }

  public void setZipCode(String zipCode) {
    this.zipCode = zipCode;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public LocalDate getDateOfBirth() {
    return dateOfBirth;
  }

  public void setDateOfBirth(LocalDate dateOfBirth) {
    this.dateOfBirth = dateOfBirth;
  }

  public String getSsnLast4() {
    return ssnLast4;
  }

  public void setSsnLast4(String ssnLast4) {
    this.ssnLast4 = ssnLast4;
  }

  public String getSsnLast4BlindIndex() {
    return ssnLast4BlindIndex;
  }

  public void setSsnLast4BlindIndex(String ssnLast4BlindIndex) {
    this.ssnLast4BlindIndex = ssnLast4BlindIndex;
  }

  public String getTaxId() {
    return taxId;
  }

  public void setTaxId(String taxId) {
    this.taxId = taxId;
  }

  public String getTaxIdBlindIndex() {
    return taxIdBlindIndex;
  }

  public void setTaxIdBlindIndex(String taxIdBlindIndex) {
    this.taxIdBlindIndex = taxIdBlindIndex;
  }

  public Integer getCreditScore() {
    return creditScore;
  }

  public void setCreditScore(Integer creditScore) {
    this.creditScore = creditScore;
  }

  public String getRiskTier() {
    return riskTier;
  }

  public void setRiskTier(String riskTier) {
    this.riskTier = riskTier;
  }

  public String getGdprConsent() {
    return gdprConsent;
  }

  public void setGdprConsent(String gdprConsent) {
    this.gdprConsent = gdprConsent;
  }

  public LocalDate getCreatedDate() {
    return createdDate;
  }

  public void setCreatedDate(LocalDate createdDate) {
    this.createdDate = createdDate;
  }

  public LocalDateTime getLastUpdated() {
    return lastUpdated;
  }

  public void setLastUpdated(LocalDateTime lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  @Override
  public String toString() {
    return "PolicyHolderEntity[custId=" + custId + ", pii=REDACTED]";
  }
}
