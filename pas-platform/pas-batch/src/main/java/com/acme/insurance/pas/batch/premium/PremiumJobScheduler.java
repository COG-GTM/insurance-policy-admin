package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Nightly trigger replacing CA-7 job PAS0050 (daily 01:00 EST). Disabled ({@code -}) by default:
 * set {@code PAS_BATCH_PREMIUM_CRON} only at cut-over, when PAS0050 is switched off. The run date
 * is the identifying job parameter, so a second replica firing the same night cannot start a second
 * run for the same date.
 */
@Configuration
@EnableScheduling
public class PremiumJobScheduler {

  private static final Logger log = LoggerFactory.getLogger(PremiumJobScheduler.class);

  private final PremiumJobLauncher launcher;
  private final List<BatchAlertNotifier> notifiers;
  private final ZoneId zone;

  public PremiumJobScheduler(
      PremiumJobLauncher launcher,
      ObjectProvider<BatchAlertNotifier> notifiers,
      PremiumJobProperties props) {
    this.launcher = launcher;
    this.notifiers = notifiers.orderedStream().toList();
    this.zone = ZoneId.of(props.zone());
  }

  @Scheduled(
      cron = "${pas.batch.premium.cron:-}",
      zone = "${pas.batch.premium.zone:America/New_York}")
  public void nightlyRun() {
    LocalDate runDate = LocalDate.now(zone);
    try {
      launcher.start(runDate, false, false);
    } catch (JobInstanceAlreadyCompleteException e) {
      log.info("Premium run for {} already completed; nothing to do", runDate);
    } catch (Exception e) {
      PremiumAlerts.send(notifiers, PremiumAlerts.launchFailed(runDate, e));
    }
  }
}
