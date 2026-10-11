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
@Table(name = "terminal_command")
class TerminalCommandRequest {
  @Id private UUID id;
  @Column(name = "worktree_id", nullable = false) private UUID worktreeId;
  @Column(name = "agent_run_id") private UUID agentRunId;
  @Column(nullable = false, length = 16) private String source;
  @Column(nullable = false, length = 4000) private String command;
  @Column(length = 1000) private String reason;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private TerminalCommandStatus status;
  @Column(name = "exit_code") private Integer exitCode;
  @Column(columnDefinition = "TEXT") private String output;
  @Column(name = "output_truncated", nullable = false) private boolean outputTruncated;
  @Column(name = "requested_by") private UUID requestedBy;
  @Column(name = "requested_at", nullable = false) private Instant requestedAt;
  @Column(name = "approved_by") private UUID approvedBy;
  @Column(name = "approved_at") private Instant approvedAt;
  @Column(name = "rejected_by") private UUID rejectedBy;
  @Column(name = "rejected_at") private Instant rejectedAt;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "completed_at") private Instant completedAt;

  protected TerminalCommandRequest() {}

  TerminalCommandRequest(UUID id, UUID worktreeId, UUID agentRunId, String source, String command,
      String reason, UUID requestedBy, Instant now) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.agentRunId = agentRunId;
    this.source = source;
    this.command = command;
    this.reason = reason;
    this.requestedBy = requestedBy;
    this.status = TerminalCommandStatus.PROPOSED;
    this.requestedAt = now;
  }

  void approve(UUID actorId, Instant now) {
    require(TerminalCommandStatus.PROPOSED);
    status = TerminalCommandStatus.APPROVED;
    approvedBy = actorId;
    approvedAt = now;
  }

  void reject(UUID actorId, Instant now) {
    require(TerminalCommandStatus.PROPOSED);
    status = TerminalCommandStatus.REJECTED;
    rejectedBy = actorId;
    rejectedAt = now;
  }

  void start(Instant now) {
    require(TerminalCommandStatus.APPROVED);
    status = TerminalCommandStatus.RUNNING;
    startedAt = now;
  }

  void complete(int exit, String text, boolean truncated, boolean timedOut, Instant now) {
    require(TerminalCommandStatus.RUNNING);
    exitCode = timedOut ? null : exit;
    output = text;
    outputTruncated = truncated;
    completedAt = now;
    status = timedOut ? TerminalCommandStatus.TIMED_OUT
        : exit == 0 ? TerminalCommandStatus.COMPLETED : TerminalCommandStatus.FAILED;
  }

  /** Live output while the command is running. */
  void progress(String text) {
    if (status == TerminalCommandStatus.RUNNING) output = text;
  }

  void error(String safeMessage, Instant now) {
    require(TerminalCommandStatus.RUNNING);
    status = TerminalCommandStatus.ERROR;
    output = safeMessage;
    completedAt = now;
  }

  private void require(TerminalCommandStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Terminal command must be " + expected + " but is " + status);
    }
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  UUID agentRunId() { return agentRunId; }
  String source() { return source; }
  String command() { return command; }
  String reason() { return reason; }
  TerminalCommandStatus status() { return status; }
  Integer exitCode() { return exitCode; }
  String output() { return output; }
  boolean outputTruncated() { return outputTruncated; }
  UUID approvedBy() { return approvedBy; }
  Instant requestedAt() { return requestedAt; }
  Instant approvedAt() { return approvedAt; }
  Instant startedAt() { return startedAt; }
  Instant completedAt() { return completedAt; }
}
