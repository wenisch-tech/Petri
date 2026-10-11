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
  void complete(UUID id) {
    jobs.findLocked(id).ifPresent(job -> job.completed(Instant.now()));
  }

  @Transactional
  void fail(UUID id, String message) {
    jobs.findLocked(id).ifPresent(job -> job.failed(message, Instant.now()));
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
