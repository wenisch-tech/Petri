package tech.wenisch.petri.project;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectInitializationStateService {
  private final ProjectInitializationJobRepository jobs;

  ProjectInitializationStateService(ProjectInitializationJobRepository jobs) {
    this.jobs = jobs;
  }

  @Transactional
  ProjectInitializationJob claim(UUID id, String leaseOwner) {
    ProjectInitializationJob job = jobs.findLocked(id)
        .orElseThrow(() -> new IllegalArgumentException("Initialization job not found"));
    Instant now = Instant.now();
    if (job.getStatus() == ProjectInitializationStatus.COMPLETED) return null;
    if (job.getStatus() == ProjectInitializationStatus.RUNNING
        && job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now)) return null;
    job.claim(leaseOwner, now, now.plus(15, ChronoUnit.MINUTES));
    return job;
  }

  @Transactional
  boolean renew(UUID id, String leaseOwner) {
    Instant now = Instant.now();
    return jobs.findLocked(id)
        .map(job -> job.renew(leaseOwner, now, now.plus(15, ChronoUnit.MINUTES)))
        .orElse(false);
  }

  @Transactional
  boolean complete(UUID id, String leaseOwner) {
    return jobs.findLocked(id)
        .map(job -> job.completed(leaseOwner, Instant.now()))
        .orElse(false);
  }

  @Transactional
  boolean fail(UUID id, String leaseOwner, String message) {
    return jobs.findLocked(id)
        .map(job -> job.failed(leaseOwner, message, Instant.now()))
        .orElse(false);
  }

  @Transactional
  ProjectInitializationJob retry(UUID projectId) {
    ProjectInitializationJob job = jobs.findByProjectId(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Initialization job not found"));
    job.retry(Instant.now());
    return job;
  }

  @Transactional(readOnly = true)
  List<ProjectInitializationJob> recoverable() {
    return jobs.recoverable(Instant.now());
  }
}
