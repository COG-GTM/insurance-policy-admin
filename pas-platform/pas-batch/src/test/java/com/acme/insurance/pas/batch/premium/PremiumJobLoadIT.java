package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

/**
 * Load test. CI runs a small book (20,000 active policies); the full-book run is
 *
 * <pre>
 * ./mvnw -pl pas-batch verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dit.test=PremiumJobLoadIT -Dpas.loadtest.policies=1000000
 * </pre>
 *
 * The measured duration is written to {@code target/loadtest/premium-load.txt}. The budget is the
 * AB-523 target (30 minutes) unless {@code pas.loadtest.budget-seconds} is set.
 */
@TestPropertySource(properties = "pas.batch.premium.partitions=8")
class PremiumJobLoadIT extends PremiumJobTestSupport {

  private static final Logger log = LoggerFactory.getLogger(PremiumJobLoadIT.class);

  @Test
  void bookIsRecalculatedWithinTheBatchWindow() throws Exception {
    int active = Integer.getInteger("pas.loadtest.policies", 20_000);
    long budgetSeconds = Long.getLong("pas.loadtest.budget-seconds", 30 * 60);
    resetBook();
    String generator =
        new ClassPathResource("loadtest/generate-book.sql")
            .getContentAsString(StandardCharsets.UTF_8)
            .replace(":active", Integer.toString(active));
    Instant genStart = Instant.now();
    jdbc.execute(generator);
    Duration generation = Duration.between(genStart, Instant.now());

    Instant start = Instant.now();
    JobExecution execution = run(LocalDate.of(2026, 10, 20), false);
    Duration elapsed = Duration.between(start, Instant.now());

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    PremiumRunTotals totals = summaries.find(execution.getId()).orElseThrow().totals();
    assertThat(totals).isEqualTo(new PremiumRunTotals(active, active, 0));
    String result =
        String.format(
            "premium load test: active=%d generated-in=%ds run=%.1fs rate=%.0f policies/s"
                + " partitions=8 chunk=1000 cpus=%d%n",
            active,
            generation.toSeconds(),
            elapsed.toMillis() / 1000.0,
            active / Math.max(0.001, elapsed.toMillis() / 1000.0),
            Runtime.getRuntime().availableProcessors());
    log.info(result.trim());
    Path out = Path.of("target", "loadtest", "premium-load.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(
        out,
        result,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(budgetSeconds));
  }
}
