package com.acme.insurance.pas.batch.extract;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.insurance.pas.batch.extract.schedule.ExtractSchedulingConfiguration;
import com.acme.insurance.pas.common.routing.RouteFlags;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;

class ExtractSchedulingConfigurationTest {
  @Test
  void skipsScheduledJobsWhenTheirRoutesAreRetired() {
    JobLauncher jobLauncher = mock(JobLauncher.class);
    Job dailyExtractJob = mock(Job.class);
    Job monthlyExposureJob = mock(Job.class);
    when(dailyExtractJob.getName()).thenReturn("dailyExtractJob");
    when(monthlyExposureJob.getName()).thenReturn("monthlyExposureJob");
    RouteFlags routes =
        RouteFlags.of(
            Map.of(
                RouteFlags.DAILY_EXTRACT, "retired",
                RouteFlags.MONTHLY_EXPOSURE, "retired"));
    ExtractSchedulingConfiguration scheduler =
        new ExtractSchedulingConfiguration(
            jobLauncher, dailyExtractJob, monthlyExposureJob, routes, "America/New_York");

    scheduler.launchDailyExtract();
    scheduler.launchMonthlyExposure();

    verifyNoInteractions(jobLauncher);
  }
}
