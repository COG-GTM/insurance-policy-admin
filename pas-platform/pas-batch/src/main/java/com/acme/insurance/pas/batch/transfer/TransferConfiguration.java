package com.acme.insurance.pas.batch.transfer;

import com.acme.insurance.pas.batch.exposure.MonthlyExposureJobConfig;
import com.acme.insurance.pas.batch.extract.DailyExtractJobConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses SFTP delivery (default) or local-only mode ({@code pas.transfer.enabled=false}). */
@Configuration
@EnableConfigurationProperties(TransferProperties.class)
public class TransferConfiguration {

  @Bean
  public SecureFileTransfer secureFileTransfer(
      TransferProperties properties, MeterRegistry meters) {
    if (!properties.enabled()) {
      return new LocalOnlyFileTransfer();
    }
    properties.validate(
        List.of(DailyExtractJobConfig.DESTINATION, MonthlyExposureJobConfig.DESTINATION));
    return new SftpFileTransfer(properties, meters, SftpFileTransfer.THREAD_SLEEP);
  }
}
