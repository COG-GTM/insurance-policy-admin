package com.acme.insurance.pas.policy.persistence;

import com.acme.insurance.pas.common.domain.CoverageRecord;
import com.acme.insurance.pas.common.domain.CustomerRecord;
import com.acme.insurance.pas.common.domain.PolicyRecord;
import com.acme.insurance.pas.common.domain.PremiumRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Converts between copybook records (pas-common) and JPA entities. CHAR columns come back
 * blank-padded from the database, so record values are right-trimmed like the copybook decoder
 * does. Copybook YYYYMMDD "last updated" fields map to midnight timestamps and back.
 */
public final class CopybookMapping {

  private CopybookMapping() {}

  public static PolicyEntity toEntity(PolicyRecord r) {
    PolicyEntity e = new PolicyEntity();
    e.setPolicyNumber(r.policyNumber());
    e.setPolicyType(r.policyTypeCode());
    e.setPolicyStatus(r.policyStatusCode());
    e.setEffectiveDate(r.effectiveDate());
    e.setExpiryDate(r.expiryDate());
    e.setPolicyholderId(r.holderId());
    e.setAgentCode(r.agentCode());
    e.setBranchCode(r.branchCode());
    e.setTotalPremium(r.totalPremium());
    e.setDeductible(r.deductible());
    e.setCoverageLimit(r.coverageLimit());
    e.setInceptionDate(r.inceptionDate());
    e.setRenewalCount(toShort(r.renewalCount()));
    e.setUwStatus(r.uwStatusCode());
    e.setRiskScore(toShort(r.riskScore()));
    e.setWebIndicator(r.webIndicator());
    e.setApiFlag(r.apiFlag());
    e.setLastUpdated(atMidnight(r.lastUpdated()));
    e.setUpdatedBy(r.updatedBy());
    return e;
  }

  public static PolicyRecord toRecord(PolicyEntity e) {
    return new PolicyRecord(
        rtrim(e.getPolicyNumber()),
        rtrim(e.getPolicyType()),
        rtrim(e.getPolicyStatus()),
        e.getEffectiveDate(),
        e.getExpiryDate(),
        rtrim(e.getPolicyholderId()),
        rtrim(e.getAgentCode()),
        rtrim(e.getBranchCode()),
        e.getTotalPremium(),
        e.getDeductible(),
        e.getCoverageLimit(),
        e.getInceptionDate(),
        toInt(e.getRenewalCount()),
        rtrim(e.getUwStatus()),
        toInt(e.getRiskScore()),
        rtrim(e.getWebIndicator()),
        rtrim(e.getApiFlag()),
        toDate(e.getLastUpdated()),
        rtrim(e.getUpdatedBy()));
  }

  public static CoverageEntity toEntity(CoverageRecord r) {
    CoverageEntity e = new CoverageEntity();
    e.setId(new CoverageId(r.policyNumber(), toShort(r.sequenceNum())));
    e.setCoverageType(r.coverageTypeCode());
    e.setDescription(r.description());
    e.setCoverageLimit(r.coverageLimit());
    e.setDeductible(r.deductible());
    e.setPremium(r.premium());
    e.setEffectiveDate(r.effectiveDate());
    e.setExpiryDate(r.expiryDate());
    e.setStatus(r.statusCode());
    e.setCoinsurancePct(toShort(r.coinsurancePct()));
    e.setRatingTerritory(r.ratingTerritory());
    e.setClassCode(r.classCode());
    return e;
  }

  public static CoverageRecord toRecord(CoverageEntity e) {
    return new CoverageRecord(
        rtrim(e.getId().getPolicyNumber()),
        toInt(e.getId().getSequenceNum()),
        rtrim(e.getCoverageType()),
        rtrim(e.getDescription()),
        e.getCoverageLimit(),
        e.getDeductible(),
        e.getPremium(),
        e.getEffectiveDate(),
        e.getExpiryDate(),
        rtrim(e.getStatus()),
        toInt(e.getCoinsurancePct()),
        rtrim(e.getRatingTerritory()),
        rtrim(e.getClassCode()));
  }

  public static PremiumEntity toEntity(PremiumRecord r) {
    PremiumEntity e = new PremiumEntity();
    e.setId(new PremiumId(r.policyNumber(), toShort(r.coverageSeq()), r.termEffectiveDate()));
    e.setTermExpiryDate(r.termExpiryDate());
    e.setBaseRate(r.baseRate());
    e.setTerritoryFactor(r.territoryFactor());
    e.setClassFactor(r.classFactor());
    e.setExperienceMod(r.experienceMod());
    e.setScheduleMod(r.scheduleMod());
    e.setDiscountPct(r.discountPct());
    e.setSurchargeAmt(r.surchargeAmt());
    e.setTaxAmt(r.taxAmt());
    e.setTotalPremium(r.totalPremium());
    e.setInstallmentCode(r.installmentCode());
    e.setInstallmentAmt(r.installmentAmt());
    e.setCalcDate(r.calcDate());
    e.setCalcBy(r.calcBy());
    return e;
  }

  public static PremiumRecord toRecord(PremiumEntity e) {
    return new PremiumRecord(
        rtrim(e.getId().getPolicyNumber()),
        toInt(e.getId().getCoverageSeq()),
        e.getId().getTermEffectiveDate(),
        e.getTermExpiryDate(),
        e.getBaseRate(),
        e.getTerritoryFactor(),
        e.getClassFactor(),
        e.getExperienceMod(),
        e.getScheduleMod(),
        e.getDiscountPct(),
        e.getSurchargeAmt(),
        e.getTaxAmt(),
        e.getTotalPremium(),
        rtrim(e.getInstallmentCode()),
        e.getInstallmentAmt(),
        e.getCalcDate(),
        rtrim(e.getCalcBy()));
  }

  public static PolicyHolderEntity toEntity(CustomerRecord r) {
    PolicyHolderEntity e = new PolicyHolderEntity();
    e.setCustId(r.custId());
    e.setCustType(r.custTypeCode());
    e.setLastName(r.lastName());
    e.setFirstName(r.firstName());
    e.setMiddleInit(r.middleInit());
    e.setCompanyName(r.companyName());
    e.setAddrLine1(r.addrLine1());
    e.setAddrLine2(r.addrLine2());
    e.setCity(r.city());
    e.setStateCode(r.state());
    e.setZipCode(r.zip());
    e.setCountryCode(r.country());
    e.setPhone(r.phone());
    e.setEmail(r.email());
    e.setDateOfBirth(r.dateOfBirth());
    e.setSsnLast4(r.ssnLast4());
    e.setTaxId(r.taxId());
    e.setCreditScore(r.creditScore());
    e.setRiskTier(r.riskTierCode());
    e.setGdprConsent(r.gdprConsent());
    e.setCreatedDate(r.createdDate());
    e.setLastUpdated(atMidnight(r.lastUpdated()));
    return e;
  }

  public static CustomerRecord toRecord(PolicyHolderEntity e) {
    return new CustomerRecord(
        rtrim(e.getCustId()),
        rtrim(e.getCustType()),
        rtrim(e.getLastName()),
        rtrim(e.getFirstName()),
        rtrim(e.getMiddleInit()),
        rtrim(e.getCompanyName()),
        rtrim(e.getAddrLine1()),
        rtrim(e.getAddrLine2()),
        rtrim(e.getCity()),
        rtrim(e.getStateCode()),
        rtrim(e.getZipCode()),
        rtrim(e.getCountryCode()),
        rtrim(e.getPhone()),
        rtrim(e.getEmail()),
        e.getDateOfBirth(),
        rtrim(e.getSsnLast4()),
        rtrim(e.getTaxId()),
        e.getCreditScore() == null ? 0 : e.getCreditScore(),
        rtrim(e.getRiskTier()),
        rtrim(e.getGdprConsent()),
        e.getCreatedDate(),
        toDate(e.getLastUpdated()));
  }

  private static String rtrim(String value) {
    return value == null ? null : value.stripTrailing();
  }

  private static Short toShort(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new IllegalArgumentException("value " + value + " does not fit SMALLINT");
    }
    return (short) value;
  }

  private static int toInt(Short value) {
    return value == null ? 0 : value;
  }

  private static LocalDateTime atMidnight(LocalDate date) {
    return date == null ? null : date.atStartOfDay();
  }

  private static LocalDate toDate(LocalDateTime timestamp) {
    return timestamp == null ? null : timestamp.toLocalDate();
  }
}
