package com.acme.insurance.pas.batch.extract;

import com.acme.insurance.pas.persistence.ExtractJdbcRepository;
import com.acme.insurance.pas.persistence.ExtractRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The two jobs that replace DAILY-EXTRACT (PAS0100) and MONTHLY-EXPOSURE (PAS0200).
 *
 * <ul>
 *   <li>{@value #CLAIMS_JOB}: daily policy delta (POLOUT, LRECL 200) and active coverage dump
 *       (COVOUT, LRECL 150), delivered to Claims.
 *   <li>{@value #ACTUARIAL_JOB}: monthly exposure aggregation (EXPOUT CSV, LRECL 300), delivered to
 *       Actuarial.
 * </ul>
 *
 * <p>Extract steps re-run on restart so a restarted job always delivers a complete, consistent set.
 * Steps use a resourceless transaction manager: they only read the policy tables, and delivery
 * receipts must be committed even when the step fails.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtractProperties.class)
public final class ExtractJobsConfiguration {

  public static final String CLAIMS_JOB = "claimsDailyExtract";
  public static final String ACTUARIAL_JOB = "actuarialMonthlyExposure";

  private static final Logger log = LoggerFactory.getLogger(ExtractJobsConfiguration.class);
  private static final PlatformTransactionManager NO_TX = new ResourcelessTransactionManager();

  private final ExtractProperties properties;
  private final ExtractJdbcRepository repository;
  private final MeterRegistry meters;
  private final Clock clock;

  public ExtractJobsConfiguration(
      ExtractProperties properties,
      JdbcClient jdbc,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<Clock> clock) {
    this.properties = properties;
    this.repository = new ExtractJdbcRepository(jdbc);
    this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @Bean
  ExtractJdbcRepository extractJdbcRepository() {
    return repository;
  }

  @Bean
  FeedDelivery feedDelivery(
      ObjectProvider<FeedTransfer> transfer, ObjectProvider<FeedDelivery.Sleeper> sleeper) {
    return new FeedDelivery(
        transfer.getIfAvailable(SftpFeedTransfer::new),
        repository,
        properties,
        meters,
        clock,
        sleeper.getIfAvailable(() -> d -> Thread.sleep(d.toMillis())));
  }

  @Bean
  Job claimsDailyExtract(JobRepository jobs, FeedDelivery delivery) {
    return new JobBuilder(CLAIMS_JOB, jobs)
        .incrementer(parameters(ExtractFeed.Consumer.CLAIMS))
        .validator(parameters(ExtractFeed.Consumer.CLAIMS))
        .listener(new ExtractJobAlerting(meters))
        .start(
            extractStep(
                jobs,
                "claimsPolicyExtract",
                ExtractFeed.CLAIMS_POLICY,
                repository::streamDailyPolicies))
        .next(
            extractStep(
                jobs,
                "claimsCoverageExtract",
                ExtractFeed.CLAIMS_COVERAGE,
                (date, sink) -> repository.streamActiveCoverages(sink)))
        .next(
            deliveryStep(
                jobs,
                "claimsDelivery",
                delivery,
                ExtractFeed.CLAIMS_POLICY,
                ExtractFeed.CLAIMS_COVERAGE))
        .build();
  }

  @Bean
  Job actuarialMonthlyExposure(JobRepository jobs, FeedDelivery delivery) {
    return new JobBuilder(ACTUARIAL_JOB, jobs)
        .incrementer(parameters(ExtractFeed.Consumer.ACTUARIAL))
        .validator(parameters(ExtractFeed.Consumer.ACTUARIAL))
        .listener(new ExtractJobAlerting(meters))
        .start(
            extractStep(
                jobs,
                "actuarialExposureExtract",
                ExtractFeed.ACTUARIAL_EXPOSURE,
                (date, sink) -> repository.streamMonthlyExposure(sink)))
        .next(deliveryStep(jobs, "actuarialDelivery", delivery, ExtractFeed.ACTUARIAL_EXPOSURE))
        .build();
  }

  private BusinessDateParameters parameters(ExtractFeed.Consumer consumer) {
    return new BusinessDateParameters(
        clock, properties.businessZone(), () -> configurationProblems(consumer));
  }

  List<String> configurationProblems(ExtractFeed.Consumer consumer) {
    List<String> problems = new ArrayList<>();
    if (properties.stagingDir() == null || properties.stagingDir().toString().isBlank()) {
      problems.add("pas.extract.staging-dir is not set");
    }
    String name = "pas.extract." + consumer.name().toLowerCase(java.util.Locale.ROOT);
    problems.addAll(properties.destination(consumer).problems(name));
    return problems;
  }

  private Step extractStep(
      JobRepository jobs,
      String name,
      ExtractFeed feed,
      BiConsumer<LocalDate, Consumer<ExtractRow>> query) {
    Tasklet tasklet =
        (contribution, chunk) -> {
          JobExecution job = chunk.getStepContext().getStepExecution().getJobExecution();
          LocalDate businessDate = BusinessDateParameters.businessDate(job.getJobParameters());
          FeedFileWriter writer =
              new FeedFileWriter(
                  properties.stagingDir(),
                  properties.charset(),
                  properties.recordSeparator().value());
          StagedFile file =
              writer.write(feed, businessDate, sink -> query.accept(businessDate, sink));
          contribution.incrementWriteCount(file.recordCount());
          store(job.getExecutionContext(), file);
          meters
              .counter("pas.extract.records", "feed", feed.feedId())
              .increment(file.recordCount());
          log.info(
              "extract staged feed={} businessDate={} records={} bytes={} sha256={}",
              feed.feedId(),
              businessDate,
              file.recordCount(),
              file.byteCount(),
              file.sha256());
          return RepeatStatus.FINISHED;
        };
    return new StepBuilder(name, jobs).tasklet(tasklet, NO_TX).allowStartIfComplete(true).build();
  }

  private Step deliveryStep(
      JobRepository jobs, String name, FeedDelivery delivery, ExtractFeed... feeds) {
    Tasklet tasklet =
        (contribution, chunk) -> {
          JobExecution job = chunk.getStepContext().getStepExecution().getJobExecution();
          for (ExtractFeed feed : feeds) {
            StagedFile file =
                restore(
                    job.getExecutionContext(),
                    feed,
                    BusinessDateParameters.businessDate(job.getJobParameters()));
            delivery.deliver(file, job.getId());
          }
          return RepeatStatus.FINISHED;
        };
    return new StepBuilder(name, jobs).tasklet(tasklet, NO_TX).build();
  }

  static void store(ExecutionContext context, StagedFile file) {
    String key = file.feed().feedId();
    context.putString(key + ".path", file.path().toString());
    context.putLong(key + ".records", file.recordCount());
    context.putLong(key + ".bytes", file.byteCount());
    context.putString(key + ".sha256", file.sha256());
  }

  static StagedFile restore(ExecutionContext context, ExtractFeed feed, LocalDate businessDate) {
    String key = feed.feedId();
    if (!context.containsKey(key + ".path")) {
      throw new IllegalStateException(feed.feedId() + " was not staged by this job execution");
    }
    Path path = Path.of(context.getString(key + ".path"));
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException(feed.feedId() + " staged file is missing: " + path);
    }
    return new StagedFile(
        feed,
        businessDate,
        path,
        context.getLong(key + ".records"),
        context.getLong(key + ".bytes"),
        context.getString(key + ".sha256"));
  }
}
