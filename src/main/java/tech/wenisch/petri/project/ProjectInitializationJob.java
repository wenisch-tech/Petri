package tech.wenisch.petri.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "project_initialization_job")
public class ProjectInitializationJob {
  @Id private UUID id;

  @Column(name = "project_id", nullable = false, unique = true)
  private UUID projectId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private ProjectInitializationStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "lease_owner", length = 255)
  private String leaseOwner;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "error_message", length = 2000)
  private String errorMessage;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ProjectInitializationJob() {}

  public ProjectInitializationJob(UUID id, UUID projectId, Instant now) {
    this.id = id;
    this.projectId = projectId;
    this.status = ProjectInitializationStatus.PENDING;
    this.attempts = 0;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void claim(String owner, Instant now, Instant until) {
    this.status = ProjectInitializationStatus.RUNNING;
    this.leaseOwner = owner;
    this.leaseUntil = until;
    this.startedAt = now;
    this.updatedAt = now;
    this.errorMessage = null;
    this.attempts++;
  }

  public boolean renew(String owner, Instant now, Instant until) {
    if (!ownsLease(owner)) return false;
    this.leaseUntil = until;
    this.updatedAt = now;
    return true;
  }

  public boolean completed(String owner, Instant now) {
    if (!ownsLease(owner)) return false;
    this.status = ProjectInitializationStatus.COMPLETED;
    this.completedAt = now;
    clearLease(now);
    return true;
  }

  public boolean failed(String owner, String message, Instant now) {
    if (!ownsLease(owner)) return false;
    this.status = ProjectInitializationStatus.FAILED;
    this.errorMessage = message == null ? "Initialization failed" : message.substring(0, Math.min(2000, message.length()));
    clearLease(now);
    return true;
  }

  public void retry(Instant now) {
    if (status != ProjectInitializationStatus.FAILED) {
      throw new IllegalStateException("Only failed initialization jobs can be retried");
    }
    this.status = ProjectInitializationStatus.PENDING;
    this.errorMessage = null;
    this.completedAt = null;
    clearLease(now);
  }

  private void clearLease(Instant now) {
    this.leaseOwner = null;
    this.leaseUntil = null;
    this.updatedAt = now;
  }

  private boolean ownsLease(String owner) {
    return status == ProjectInitializationStatus.RUNNING && Objects.equals(leaseOwner, owner);
  }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public ProjectInitializationStatus getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public Instant getLeaseUntil() { return leaseUntil; }
  public String getErrorMessage() { return errorMessage; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
