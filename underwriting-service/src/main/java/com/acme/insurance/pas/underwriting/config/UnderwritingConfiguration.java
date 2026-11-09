package com.acme.insurance.pas.underwriting.config;

import com.acme.insurance.pas.underwriting.messaging.LoggingNotificationPublisher;
import com.acme.insurance.pas.underwriting.messaging.UnderwritingNotificationPublisher;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnderwritingConfiguration {

    /**
     * Replaces EXEC CICS ASKTIME/FORMATTIME in 1000-INITIALIZE. {@code pas.underwriting.business-date}
     * pins the date (YYYY-MM-DD) for parity replays and tests.
     */
    @Bean
    public Clock underwritingClock(@Value("${pas.underwriting.zone:America/New_York}") String zone,
            @Value("${pas.underwriting.business-date:}") String businessDate) {
        ZoneId zoneId = ZoneId.of(zone);
        if (businessDate.isBlank()) {
            return Clock.system(zoneId);
        }
        return Clock.fixed(LocalDate.parse(businessDate).atStartOfDay(zoneId).toInstant(), zoneId);
    }

    @Bean
    @ConditionalOnMissingBean
    public UnderwritingNotificationPublisher underwritingNotificationPublisher() {
        return new LoggingNotificationPublisher();
    }
}
