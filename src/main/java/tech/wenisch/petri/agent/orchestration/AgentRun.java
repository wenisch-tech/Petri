package tech.wenisch.petri.agent.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;

@Entity
@Table(name = "agent_run")
class AgentRun {
  @Id private UUID id;

  @Column(name = "chat_id", nullable = false)
  private UUID chatId;

  @Column(name = "chat_message_index")
  private Integer chatMessageIndex;

  @Column(name = "workflow_id")
  private UUID workflowId;

  @Enumerated(EnumType.STRING)
  @Column(name = "workflow_role", length = 32)
  private AgentWorkflowRole workflowRole;

  @Enumerated(EnumType.STRING)
  @Column(name = "workflow_phase", length = 48)
  private AgentWorkflowStage workflowPhase;

  @Column(name = "workflow_attempt")
  private Integer workflowAttempt;

  @Column(name = "instructions_snapshot", columnDefinition = "TEXT")
  private String instructionsSnapshot;

  /** Set for unattended automation runs; they get a restricted, read-only tool set. */
  @Column(name = "automation_run_id")
  private UUID automationRunId;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ModelProvider provider;

  /** Null on rows from before provider instances; those resolve to the global instance of the kind. */
  @Column(name = "provider_instance_id")
  private UUID providerInstanceId;

  @Column(name = "model_name", nullable = false, length = 200)
  private String model;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String prompt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AgentRunStatus status;

  @Column(name = "max_model_turns", nullable = false)
  private int maxModelTurns;

  @Column(name = "max_output_tokens", nullable = false)
  private int maxOutputTokens;

  @Column(name = "context_window_tokens", nullable = false)
  private int contextWindowTokens;

  @Enumerated(EnumType.STRING)
  @Column(name = "reasoning_effort", length = 16)
  private ReasoningEffort reasoningEffort;

  @Enumerated(EnumType.STRING)
  @Column(name = "text_verbosity", length = 16)
  private TextVerbosity textVerbosity;

  @Column(name = "model_turn_count", nullable = false)
  private int modelTurnCount;

  @Column(name = "next_turn_index", nullable = false)
  private int nextTurnIndex;

  @Column(name = "final_output", columnDefinition = "TEXT")
  private String finalOutput;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @Column(name = "partial_output", columnDefinition = "TEXT")
  private String partialOutput;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "execution_claim_id")
  private UUID executionClaimId;

  @Column(name = "execution_claimed_at")
  private Instant executionClaimedAt;

  protected AgentRun() {}

  AgentRun(
      UUID id,
      UUID chatId,
      int chatMessageIndex,
      UUID worktreeId,
      ModelProvider provider,
      String model,
      String prompt,
      int maxModelTurns,
      int maxOutputTokens,
      int contextWindowTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity,
      UUID createdBy,
      Instant createdAt) {
    this.id = id;
    this.chatId = chatId;
    this.chatMessageIndex = chatMessageIndex;
    this.worktreeId = worktreeId;
    this.provider = provider;
    this.model = model;
    this.prompt = prompt;
    this.status = AgentRunStatus.PENDING;
    this.maxModelTurns = maxModelTurns;
    this.maxOutputTokens = maxOutputTokens;
    this.contextWindowTokens = contextWindowTokens;
    this.reasoningEffort = reasoningEffort;
    this.textVerbosity = textVerbosity;
    this.modelTurnCount = 0;
    this.nextTurnIndex = 1;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  AgentRun(
      UUID id, UUID chatId, UUID workflowId, AgentWorkflowRole workflowRole,
      AgentWorkflowStage workflowPhase, int workflowAttempt, UUID worktreeId,
      ModelProvider provider, String model, String prompt, int maxModelTurns,
      int maxOutputTokens, int contextWindowTokens, ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity, String instructionsSnapshot, UUID createdBy, Instant createdAt) {
    this(id, chatId, 0, worktreeId, provider, model, prompt, maxModelTurns,
        maxOutputTokens, contextWindowTokens, reasoningEffort, textVerbosity, createdBy, createdAt);
    this.chatMessageIndex = null;
    this.workflowId = workflowId;
    this.workflowRole = workflowRole;
    this.workflowPhase = workflowPhase;
    this.workflowAttempt = workflowAttempt;
    this.instructionsSnapshot = instructionsSnapshot;
  }

  AgentRun(
      UUID id,
      UUID worktreeId,
      ModelProvider provider,
      String model,
      String prompt,
      int maxModelTurns,
      int maxOutputTokens,
      ReasoningEffort reasoningEffort,
      TextVerbosity textVerbosity,
      UUID createdBy,
      Instant createdAt) {
    this(id, id, 0, worktreeId, provider, model, prompt, maxModelTurns, maxOutputTokens,
        32768, reasoningEffort, textVerbosity, createdBy, createdAt);
  }

  void start(Instant now) {
    requireStatus(AgentRunStatus.PENDING);
    status = AgentRunStatus.RUNNING;
    startedAt = now;
  }

  void claimExecution(UUID claimId, Instant now, Duration leaseDuration) {
    if (terminal()) throw new IllegalStateException("Agent run is already terminal");
    if (executionClaimId != null
        && executionClaimedAt != null
        && executionClaimedAt.plus(leaseDuration).isAfter(now)) {
      throw new IllegalStateException("Agent run is already being executed");
    }
    if (status == AgentRunStatus.PENDING) start(now);
    executionClaimId = claimId;
    executionClaimedAt = now;
  }

  void refreshExecutionClaim(UUID claimId, Instant now) {
    requireExecutionClaim(claimId);
    executionClaimedAt = now;
  }

  void requireExecutionClaim(UUID claimId) {
    if (executionClaimId == null || !executionClaimId.equals(claimId)) {
      throw new IllegalStateException("Agent run execution claim was lost");
    }
  }

  int nextTurnIndex() {
    return nextTurnIndex++;
  }

  void modelTurnCompleted() {
    if (status != AgentRunStatus.RUNNING && status != AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new IllegalStateException("Agent run is not active");
    }
    modelTurnCount++;
    partialOutput = null;
  }

  void complete(String output, Instant now) {
    requireStatus(AgentRunStatus.RUNNING);
    status = AgentRunStatus.COMPLETED;
    finalOutput = output;
    partialOutput = null;
    completedAt = now;
    clearExecutionClaim();
  }

  void recordPartialOutput(String output) {
    partialOutput = output == null || output.isEmpty() ? null : output;
  }

  void fail(String safeMessage, Instant now) {
    requireActive();
    status = AgentRunStatus.FAILED;
    errorMessage = safeMessage;
    partialOutput = null;
    completedAt = now;
    clearExecutionClaim();
  }

  void budgetExhausted(Instant now) {
    requireStatus(AgentRunStatus.RUNNING);
    status = AgentRunStatus.BUDGET_EXHAUSTED;
    errorMessage = "Maximum model turns reached";
    partialOutput = null;
    completedAt = now;
    clearExecutionClaim();
  }

  void requestCancellation(Instant now) {
    if (status == AgentRunStatus.PENDING) {
      status = AgentRunStatus.CANCELLED;
      partialOutput = null;
      completedAt = now;
      clearExecutionClaim();
    } else if (status == AgentRunStatus.RUNNING) {
      status = AgentRunStatus.CANCELLATION_REQUESTED;
    }
  }

  void cancelled(Instant now) {
    if (status != AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new IllegalStateException("Agent run has no cancellation request");
    }
    status = AgentRunStatus.CANCELLED;
    partialOutput = null;
    completedAt = now;
    clearExecutionClaim();
  }

  boolean terminal() {
    return switch (status) {
      case COMPLETED, FAILED, BUDGET_EXHAUSTED, CANCELLED -> true;
      default -> false;
    };
  }

  boolean executionLeaseExpired(Instant now, Duration leaseDuration) {
    return executionClaimId == null || executionClaimedAt == null
        || !executionClaimedAt.plus(leaseDuration).isAfter(now);
  }

  private void requireActive() {
    if (status != AgentRunStatus.RUNNING && status != AgentRunStatus.CANCELLATION_REQUESTED) {
      throw new IllegalStateException("Agent run is not active");
    }
  }

  private void clearExecutionClaim() {
    executionClaimId = null;
    executionClaimedAt = null;
  }

  private void requireStatus(AgentRunStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Agent run must be " + expected + " but is " + status);
    }
  }

  UUID id() { return id; }
  UUID chatId() { return chatId; }
  Integer chatMessageIndex() { return chatMessageIndex; }
  UUID worktreeId() { return worktreeId; }
  ModelProvider provider() { return provider; }
  UUID providerInstanceId() { return providerInstanceId; }

  void useProviderInstance(UUID instanceId) {
    this.providerInstanceId = instanceId;
  }

  void useAutomation(UUID automationRunId, String instructions) {
    this.automationRunId = automationRunId;
    this.instructionsSnapshot = instructions;
  }

  UUID automationRunId() { return automationRunId; }
  String model() { return model; }
  String prompt() { return prompt; }
  AgentRunStatus status() { return status; }
  int maxModelTurns() { return maxModelTurns; }
  int maxOutputTokens() { return maxOutputTokens; }
  int contextWindowTokens() { return contextWindowTokens; }
  ReasoningEffort reasoningEffort() { return reasoningEffort; }
  TextVerbosity textVerbosity() { return textVerbosity; }
  int modelTurnCount() { return modelTurnCount; }
  String finalOutput() { return finalOutput; }
  String errorMessage() { return errorMessage; }
  String partialOutput() { return partialOutput; }
  UUID createdBy() { return createdBy; }
  Instant createdAt() { return createdAt; }
  Instant startedAt() { return startedAt; }
  Instant completedAt() { return completedAt; }
  UUID workflowId() { return workflowId; }
  AgentWorkflowRole workflowRole() { return workflowRole; }
  AgentWorkflowStage workflowPhase() { return workflowPhase; }
  Integer workflowAttempt() { return workflowAttempt; }
  String instructionsSnapshot() { return instructionsSnapshot; }
}
