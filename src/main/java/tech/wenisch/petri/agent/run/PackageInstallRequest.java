package tech.wenisch.petri.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "package_install_request")
class PackageInstallRequest {
  @Id private UUID id;
  @Column(name = "worktree_id", nullable = false) private UUID worktreeId;
  @Column(name = "agent_run_id") private UUID agentRunId;
  @Column(nullable = false, length = 1500) private String packages;
  @Column(length = 1000) private String reason;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private PackageInstallStatus status;
  @Column(name = "exit_code") private Integer exitCode;
  @Column(columnDefinition = "TEXT") private String output;
  @Column(name = "output_truncated", nullable = false) private boolean outputTruncated;
  @Column(name = "requested_at", nullable = false) private Instant requestedAt;
  @Column(name = "approved_by") private UUID approvedBy;
  @Column(name = "approved_at") private Instant approvedAt;
  @Column(name = "rejected_by") private UUID rejectedBy;
  @Column(name = "rejected_at") private Instant rejectedAt;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "completed_at") private Instant completedAt;

  protected PackageInstallRequest() {}

  PackageInstallRequest(
      UUID id, UUID worktreeId, UUID agentRunId, List<String> packages, String reason, Instant now) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.agentRunId = agentRunId;
    this.packages = String.join(",", packages);
    this.reason = reason;
    this.status = PackageInstallStatus.PROPOSED;
    this.requestedAt = now;
  }

  void approve(UUID actorId, Instant now) {
    require(PackageInstallStatus.PROPOSED);
    status = PackageInstallStatus.APPROVED;
    approvedBy = actorId;
    approvedAt = now;
  }

  void reject(UUID actorId, Instant now) {
    require(PackageInstallStatus.PROPOSED);
    status = PackageInstallStatus.REJECTED;
    rejectedBy = actorId;
    rejectedAt = now;
  }

  void start(Instant now) {
    require(PackageInstallStatus.APPROVED);
    status = PackageInstallStatus.RUNNING;
    startedAt = now;
  }

  void complete(int exit, String text, boolean truncated, boolean timedOut, Instant now) {
    require(PackageInstallStatus.RUNNING);
    exitCode = timedOut ? null : exit;
    output = text;
    outputTruncated = truncated;
    completedAt = now;
    status = !timedOut && exit == 0 ? PackageInstallStatus.INSTALLED : PackageInstallStatus.FAILED;
  }

  void progress(String text) {
    if (status == PackageInstallStatus.RUNNING) output = text;
  }

  void error(String safeMessage, Instant now) {
    require(PackageInstallStatus.RUNNING);
    status = PackageInstallStatus.FAILED;
    output = safeMessage;
    completedAt = now;
  }

  private void require(PackageInstallStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Package install must be " + expected + " but is " + status);
    }
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  UUID agentRunId() { return agentRunId; }
  List<String> packageNames() { return Arrays.asList(packages.split(",")); }
  String reason() { return reason; }
  PackageInstallStatus status() { return status; }
  Integer exitCode() { return exitCode; }
  String output() { return output; }
  boolean outputTruncated() { return outputTruncated; }
  Instant requestedAt() { return requestedAt; }
  UUID approvedBy() { return approvedBy; }
  Instant approvedAt() { return approvedAt; }
  Instant startedAt() { return startedAt; }
  Instant completedAt() { return completedAt; }
}
