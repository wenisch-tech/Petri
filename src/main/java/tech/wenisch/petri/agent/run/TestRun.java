package tech.wenisch.petri.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_run")
class TestRun {
  @Id private UUID id;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "build_system", nullable = false, length = 16)
  private TestBuildSystem buildSystem;

  @Column(name = "command_display", nullable = false, length = 500)
  private String commandDisplay;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TestRunStatus status;

  @Column(name = "exit_code")
  private Integer exitCode;

  @Column(columnDefinition = "TEXT")
  private String output;

  @Column(name = "output_truncated", nullable = false)
  private boolean outputTruncated;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "approved_by")
  private UUID approvedBy;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "rejected_by")
  private UUID rejectedBy;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected TestRun() {}

  TestRun(
      UUID id,
      UUID worktreeId,
      TestBuildSystem buildSystem,
      String commandDisplay,
      Instant requestedAt) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.buildSystem = buildSystem;
    this.commandDisplay = commandDisplay;
    this.status = TestRunStatus.PROPOSED;
    this.requestedAt = requestedAt;
  }

  void approve(UUID actorId, Instant now) {
    requireStatus(TestRunStatus.PROPOSED);
    status = TestRunStatus.APPROVED;
    approvedBy = actorId;
    approvedAt = now;
  }

  void reject(UUID actorId, Instant now) {
    requireStatus(TestRunStatus.PROPOSED);
    status = TestRunStatus.REJECTED;
    rejectedBy = actorId;
    rejectedAt = now;
  }

  void start(Instant now) {
    requireStatus(TestRunStatus.APPROVED);
    status = TestRunStatus.RUNNING;
    startedAt = now;
  }

  void complete(TestCommandResult result, Instant now) {
    requireStatus(TestRunStatus.RUNNING);
    exitCode = result.timedOut() ? null : result.exitCode();
    output = result.output();
    outputTruncated = result.truncated();
    completedAt = now;
    status = result.timedOut()
        ? TestRunStatus.TIMED_OUT
        : result.exitCode() == 0 ? TestRunStatus.PASSED : TestRunStatus.FAILED;
  }

  void error(String safeMessage, Instant now) {
    requireStatus(TestRunStatus.RUNNING);
    status = TestRunStatus.ERROR;
    output = safeMessage;
    completedAt = now;
  }

  private void requireStatus(TestRunStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Test run must be " + expected + " but is " + status);
    }
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  TestBuildSystem buildSystem() { return buildSystem; }
  String commandDisplay() { return commandDisplay; }
  TestRunStatus status() { return status; }
  Integer exitCode() { return exitCode; }
  String output() { return output; }
  boolean outputTruncated() { return outputTruncated; }
  Instant requestedAt() { return requestedAt; }
  UUID approvedBy() { return approvedBy; }
  Instant approvedAt() { return approvedAt; }
  Instant startedAt() { return startedAt; }
  Instant completedAt() { return completedAt; }
}
