package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** pas.premiums (ACMEINS.PREMIUMS, PREMIUM-RECORD.cpy). */
@Entity
@Table(name = "premiums")
public class PremiumEntity {

  @EmbeddedId private PremiumId id;

  @Column(name = "term_expiry_date", nullable = false)
  private LocalDate termExpiryDate;

  @Column(name = "base_rate", precision = 11, scale = 4)
  private BigDecimal baseRate;

  @Column(name = "territory_factor", precision = 7, scale = 4)
  private BigDecimal territoryFactor;

  @Column(name = "class_factor", precision = 7, scale = 4)
  private BigDecimal classFactor;

  @Column(name = "experience_mod", precision = 7, scale = 4)
  private BigDecimal experienceMod;

  @Column(name = "schedule_mod", precision = 7, scale = 4)
  private BigDecimal scheduleMod;

  @Column(name = "discount_pct", precision = 5, scale = 2)
  private BigDecimal discountPct;

  @Column(name = "surcharge_amt", precision = 9, scale = 2)
  private BigDecimal surchargeAmt;

  @Column(name = "tax_amt", precision = 9, scale = 2)
  private BigDecimal taxAmt;

  @Column(name = "total_premium", precision = 11, scale = 2)
  private BigDecimal totalPremium;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "installment_code", length = 2)
  private String installmentCode;

  @Column(name = "installment_amt", precision = 9, scale = 2)
  private BigDecimal installmentAmt;

  @Column(name = "calc_date")
  private LocalDate calcDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "calc_by", length = 8)
  private String calcBy;

  public PremiumId getId() {
    return id;
  }

  public void setId(PremiumId id) {
    this.id = id;
  }

  public LocalDate getTermExpiryDate() {
    return termExpiryDate;
  }

  public void setTermExpiryDate(LocalDate termExpiryDate) {
    this.termExpiryDate = termExpiryDate;
  }

  public BigDecimal getBaseRate() {
    return baseRate;
  }

  public void setBaseRate(BigDecimal baseRate) {
    this.baseRate = baseRate;
  }

  public BigDecimal getTerritoryFactor() {
    return territoryFactor;
  }

  public void setTerritoryFactor(BigDecimal territoryFactor) {
    this.territoryFactor = territoryFactor;
  }

  public BigDecimal getClassFactor() {
    return classFactor;
  }

  public void setClassFactor(BigDecimal classFactor) {
    this.classFactor = classFactor;
  }

  public BigDecimal getExperienceMod() {
    return experienceMod;
  }

  public void setExperienceMod(BigDecimal experienceMod) {
    this.experienceMod = experienceMod;
  }

  public BigDecimal getScheduleMod() {
    return scheduleMod;
  }

  public void setScheduleMod(BigDecimal scheduleMod) {
    this.scheduleMod = scheduleMod;
  }

  public BigDecimal getDiscountPct() {
    return discountPct;
  }

  public void setDiscountPct(BigDecimal discountPct) {
    this.discountPct = discountPct;
  }

  public BigDecimal getSurchargeAmt() {
    return surchargeAmt;
  }

  public void setSurchargeAmt(BigDecimal surchargeAmt) {
    this.surchargeAmt = surchargeAmt;
  }

  public BigDecimal getTaxAmt() {
    return taxAmt;
  }

  public void setTaxAmt(BigDecimal taxAmt) {
    this.taxAmt = taxAmt;
  }

  public BigDecimal getTotalPremium() {
    return totalPremium;
  }

  public void setTotalPremium(BigDecimal totalPremium) {
    this.totalPremium = totalPremium;
  }

  public String getInstallmentCode() {
    return installmentCode;
  }

  public void setInstallmentCode(String installmentCode) {
    this.installmentCode = installmentCode;
  }

  public BigDecimal getInstallmentAmt() {
    return installmentAmt;
  }

  public void setInstallmentAmt(BigDecimal installmentAmt) {
    this.installmentAmt = installmentAmt;
  }

  public LocalDate getCalcDate() {
    return calcDate;
  }

  public void setCalcDate(LocalDate calcDate) {
    this.calcDate = calcDate;
  }

  public String getCalcBy() {
    return calcBy;
  }

  public void setCalcBy(String calcBy) {
    this.calcBy = calcBy;
  }

  @Override
  public String toString() {
    return "PremiumEntity[id=" + id + "]";
  }
}
