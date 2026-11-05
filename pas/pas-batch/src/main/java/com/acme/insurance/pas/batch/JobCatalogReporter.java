package com.acme.insurance.pas.batch;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Logs the registered jobs at start-up so an empty or misconfigured deployment is visible. */
@Component
class JobCatalogReporter implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(JobCatalogReporter.class);
  private final ObjectProvider<Job> jobs;

  JobCatalogReporter(ObjectProvider<Job> jobs) {
    this.jobs = jobs;
  }

  List<String> jobNames() {
    return jobs.orderedStream().map(Job::getName).sorted().toList();
  }

  @Override
  public void run(ApplicationArguments args) {
    log.info("pas-batch registered jobs: {}", jobNames());
  }
}
