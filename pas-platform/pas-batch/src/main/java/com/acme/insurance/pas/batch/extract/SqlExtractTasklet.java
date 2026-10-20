package com.acme.insurance.pas.batch.extract;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Streams one SELECT into one fixed-length-record file. Header records (if any) are written first.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "shares Spring-managed collaborators (MeterRegistry, JDBC, properties) by design")
public class SqlExtractTasklet implements Tasklet {

  private static final Logger LOG = LoggerFactory.getLogger(SqlExtractTasklet.class);

  /** Maps the current row to exactly one LRECL-sized record. */
  @FunctionalInterface
  public interface RecordMapper {
    byte[] map(ResultSet rs) throws SQLException;
  }

  private final NamedParameterJdbcTemplate jdbc;
  private final ExtractOutputs outputs;
  private final MeterRegistry meters;
  private final String jobName;
  private final String fileName;
  private final String sql;
  private final int lrecl;
  private final List<byte[]> headers;
  private final RecordMapper mapper;

  public SqlExtractTasklet(
      NamedParameterJdbcTemplate jdbc,
      ExtractOutputs outputs,
      MeterRegistry meters,
      String jobName,
      String fileName,
      String sql,
      int lrecl,
      List<byte[]> headers,
      RecordMapper mapper) {
    this.jdbc = jdbc;
    this.outputs = outputs;
    this.meters = meters;
    this.jobName = jobName;
    this.fileName = fileName;
    this.sql = sql;
    this.lrecl = lrecl;
    this.headers = List.copyOf(headers);
    this.mapper = mapper;
  }

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    LocalDate businessDate = ExtractOutputs.businessDate(chunkContext);
    Path target = outputs.location(jobName, businessDate, fileName);
    ExtractFile file;
    try (ExtractFileWriter writer = new ExtractFileWriter(target, lrecl)) {
      headers.forEach(writer::write);
      jdbc.query(
          sql,
          ExtractOutputs.queryParameters(businessDate),
          rs -> {
            writer.write(mapper.map(rs));
            contribution.incrementReadCount();
            contribution.incrementWriteCount(1);
          });
      file = writer.commit();
    }
    meters
        .counter("pas.extract.records", "job", jobName, "file", fileName)
        .increment(file.records());
    chunkContext
        .getStepContext()
        .getStepExecution()
        .getExecutionContext()
        .putString("extract.sha256", file.sha256());
    LOG.info(
        "extract written job={} file={} businessDate={} records={} bytes={} sha256={}",
        jobName,
        target,
        businessDate,
        file.records(),
        file.bytes(),
        file.sha256());
    return RepeatStatus.FINISHED;
  }
}
