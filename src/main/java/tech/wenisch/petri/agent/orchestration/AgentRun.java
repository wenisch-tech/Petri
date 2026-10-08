package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;

@Entity
@Table(name = "agent_run")
class AgentRun {
  @Id private UUID id;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ModelProvider provider;

  @Column(name = "model_name", nullable = false, length = 200)
  private String model;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String prompt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AgentRunStatus status;

  @Column(name = "max_model_turns", nullable = false)
  private int maxModelTurns;

  @Column(name = "model_turn_count", nullable = false)
  private int modelTurnCount;

  @Column(name = "next_turn_index", nullable = false)
  private int nextTurnIndex;

  @Column(name = "final_output", columnDefinition = "TEXT")
  private String finalOutput;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected AgentRun() {}

  AgentRun(
      UUID id,
      UUID worktreeId,
      ModelProvider provider,
      String model,
      String prompt,
      int maxModelTurns,
      UUID createdBy,
      Instant createdAt) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.provider = provider;
    this.model = model;
    this.prompt = prompt;
    this.status = AgentRunStatus.PENDING;
    this.maxModelTurns = maxModelTurns;
    this.modelTurnCount = 0;
    this.nextTurnIndex = 1;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  void start(Instant now) {
    requireStatus(AgentRunStatus.PENDING);
    status = AgentRunStatus.RUNNING;
    startedAt = now;
  }

  int nextTurnIndex() {
    return nextTurnIndex++;
  }

  void modelTurnCompleted() {
    if (status != AgentRunStatus.RUNNING && status != AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new IllegalStateException("Agent run is not active");
    }
    modelTurnCount++;
  }

  void complete(String output, Instant now) {
    requireStatus(AgentRunStatus.RUNNING);
    status = AgentRunStatus.COMPLETED;
    finalOutput = output;
    completedAt = now;
  }

  void fail(String safeMessage, Instant now) {
    requireActive();
    status = AgentRunStatus.FAILED;
    errorMessage = safeMessage;
    completedAt = now;
  }

  void budgetExhausted(Instant now) {
    requireStatus(AgentRunStatus.RUNNING);
    status = AgentRunStatus.BUDGET_EXHAUSTED;
    errorMessage = "Maximum model turns reached";
    completedAt = now;
  }

  void requestCancellation(Instant now) {
    if (status == AgentRunStatus.PENDING) {
      status = AgentRunStatus.CANCELLED;
      completedAt = now;
    } else if (status == AgentRunStatus.RUNNING) {
      status = AgentRunStatus.CANCELLATION_REQUESTED;
    }
  }

  void cancelled(Instant now) {
    if (status != AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new IllegalStateException("Agent run has no cancellation request");
    }
    status = AgentRunStatus.CANCELLED;
    completedAt = now;
  }

  boolean terminal() {
    return switch (status) {
      case COMPLETED, FAILED, BUDGET_EXHAUSTED, CANCELLED -> true;
      default -> false;
    };
  }

  private void requireActive() {
    if (status != AgentRunStatus.RUNNING && status != AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new IllegalStateException("Agent run is not active");
    }
  }

  private void requireStatus(AgentRunStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Agent run must be " + expected + " but is " + status);
    }
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  ModelProvider provider() { return provider; }
  String model() { return model; }
  String prompt() { return prompt; }
  AgentRunStatus status() { return status; }
  int maxModelTurns() { return maxModelTurns; }
  int modelTurnCount() { return modelTurnCount; }
  String finalOutput() { return finalOutput; }
  String errorMessage() { return errorMessage; }
  UUID createdBy() { return createdBy; }
  Instant createdAt() { return createdAt; }
  Instant startedAt() { return startedAt; }
  Instant completedAt() { return completedAt; }
}
