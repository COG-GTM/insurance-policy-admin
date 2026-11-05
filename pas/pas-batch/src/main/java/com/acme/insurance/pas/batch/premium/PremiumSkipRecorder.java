package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.persistence.PremiumRunItem;
import com.acme.insurance.pas.persistence.PremiumRunJdbcRepository;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a policy whose PREMIUMS write the database rejected (a constraint or invalid value, the
 * SQLCODE -803 / -181 cases that ended PREMBAT's loop) as an error of the run, in its own
 * transaction, and lets the run continue (Q-25).
 */
public class PremiumSkipRecorder implements SkipListener<Object, PremiumOutcome> {

  private static final Logger log = LoggerFactory.getLogger(PremiumSkipRecorder.class);

  private final PremiumRunJdbcRepository repository;
  private final TransactionTemplate requiresNew;
  private final long runId;
  private final LocalDate businessDate;

  public PremiumSkipRecorder(
      PremiumRunJdbcRepository repository,
      PlatformTransactionManager transactions,
      long runId,
      LocalDate businessDate) {
    this.repository = repository;
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.runId = runId;
    this.businessDate = businessDate;
  }

  @Override
  public void onSkipInWrite(PremiumOutcome item, Throwable t) {
    log.warn(
        "pas.premium.policy.error policyNumber={} businessDate={} error={}",
        item.policyNumber(),
        businessDate,
        t.getClass().getSimpleName());
    PremiumRunItem error =
        new PremiumRunItem(
            item.policyNumber(),
            PremiumRunItem.Status.ERROR,
            item.rated() ? item.premium().totalPremium() : null,
            t.getClass().getSimpleName(),
            detail(t));
    requiresNew.executeWithoutResult(
        status -> repository.recordOutcomes(runId, businessDate, List.of(error)));
  }

  /**
   * What the database said, without the driver's message text: some drivers echo the rejected row
   * in it (PostgreSQL "Failing row contains ..."). Keeps the SQLSTATE, the vendor error code (the
   * DB2 SQLCODE) and the root exception class.
   */
  static String detail(Throwable t) {
    Throwable root = t;
    SQLException sql = null;
    for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
      if (c instanceof SQLException e && e.getSQLState() != null) {
        sql = e;
      }
      root = c;
    }
    String rootClass = root.getClass().getSimpleName();
    if (sql == null) {
      return rootClass;
    }
    String vendor = sql.getErrorCode() == 0 ? "" : " vendor code " + sql.getErrorCode();
    return "SQLSTATE " + sql.getSQLState() + vendor + " (" + rootClass + ")";
  }
}
