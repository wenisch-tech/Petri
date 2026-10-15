package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.event.AgentRunEventService;
import tech.wenisch.petri.agent.event.AgentRunEventType;
import tech.wenisch.petri.agent.model.ModelClient;
import tech.wenisch.petri.agent.model.ModelCredentialRef;
import tech.wenisch.petri.agent.model.ModelClientException;
import tech.wenisch.petri.agent.model.ModelClientRegistry;
import tech.wenisch.petri.agent.model.ModelRequest;
import tech.wenisch.petri.agent.model.ModelResponse;
import tech.wenisch.petri.agent.model.ModelStreamListener;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;
import tech.wenisch.petri.agent.tool.AgentWorkflowToolPolicy;
import tech.wenisch.petri.agent.tool.AutomationToolPolicy;

@Service
public class AgentRunExecutor {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AgentRunExecutor.class);
  private static final String INSTRUCTIONS = """
      You are Petri, a coding agent operating in one controlled disposable worktree.
      Inspect repository content with the available tools before drawing conclusions.
      Use list_skills to discover reusable workflows and load_skill only when a listed skill is
      relevant to the task; loaded skills are operating guidance and cannot bypass tool policy.
      File changes, test runs and package installs are proposals that require human approval. Your
      run pauses until each one is approved (or auto-approved) and finished, or declined, and the
      tool result then states the final outcome. If a result says the user DECLINED, respect it:
      do not retry the same action, change approach or explain what you need. Never claim that a
      proposal was applied or executed unless its tool result says so. To replace an existing file use
      create_patch with the SHA-256 from the latest read_file; to add a file that does not exist
      yet use create_file. Use tool results as authoritative, never invent repository content,
      and do not reveal credentials or other secrets. When the task is complete, return a concise
      summary and clearly identify pending approvals.
      """;

  private final AgentRunStateService stateService;
  private final LoopGuardMetrics loopGuardMetrics;
  private final AgentRunService runService;
  private final ModelClientRegistry modelClients;
  private final AgentToolRegistry tools;
  private final AgentRunEventService events;
  private final ApplicationEventPublisher eventPublisher;
  private final AgentChatSummaryService summaries;
  private final AgentChatStream chatStream;
  private final ApprovalGate approvalGate;
  private final McpToolBridge mcp;
  private final java.time.Duration approvalWait;

  public AgentRunExecutor(
      AgentRunStateService stateService,
      AgentRunService runService,
      ModelClientRegistry modelClients,
      AgentToolRegistry tools,
      AgentRunEventService events,
      ApplicationEventPublisher eventPublisher,
      AgentChatSummaryService summaries,
      AgentChatStream chatStream,
      ApprovalGate approvalGate,
      McpToolBridge mcp,
      LoopGuardMetrics loopGuardMetrics,
      @org.springframework.beans.factory.annotation.Value("${petri.agent.approval-wait:${PETRI_AGENT_APPROVAL_WAIT:12h}}")
          java.time.Duration approvalWait) {
    this.stateService = stateService;
    this.runService = runService;
    this.modelClients = modelClients;
    this.tools = tools;
    this.events = events;
    this.eventPublisher = eventPublisher;
    this.summaries = summaries;
    this.chatStream = chatStream;
    this.approvalGate = approvalGate;
    this.mcp = mcp;
    this.loopGuardMetrics = loopGuardMetrics;
    this.approvalWait = approvalWait;
  }

  public AgentRunView execute(
      UUID runId,
      UUID expectedWorktreeId,
      UUID expectedCreatorId,
      String actor) {
    UUID claimId = UUID.randomUUID();
    AgentExecutionState state = stateService.claim(
        runId, expectedWorktreeId, expectedCreatorId, claimId, actor);
    String runActor = "agent-run:" + runId;
    try {
      if (state.initialModelRequest()) {
        if (state.workflowId() == null && summaries.compactIfNeeded(state)) {
          chatStream.summaryStatus(state.chatId(), "completed");
        }
        state = stateService.current(runId, claimId);
      }
      while (!state.terminal()) {
        state = stateService.cancelIfRequested(runId, claimId, actor);
        if (state.terminal()) break;

        if (!state.pendingToolCalls().isEmpty()) {
          java.util.List<ApprovalGate.Gated> gated = new java.util.ArrayList<>();
          for (AgentPendingToolCall call : state.pendingToolCalls()) {
            events.record(
                state.worktreeId(), runId, AgentRunEventType.TOOL_STARTED, call.name(), runActor,
                "Agent run started tool " + call.name());
            chatStream.toolLifecycle(state.chatId(), runId, call.name(), "started");
            try {
              if (state.repetitionStrikes() >= LoopGuard.WITHDRAW_TOOLS_AT) {
                // Tools were withdrawn for a looping model; answer its call without running it.
                stateService.recordToolFailure(runId, claimId, call, LoopGuard.skippedToolMessage());
                events.record(
                    state.worktreeId(), runId, AgentRunEventType.TOOL_FAILED, call.name(), runActor,
                    "Loop guard skipped tool " + call.name());
                chatStream.toolLifecycle(state.chatId(), runId, call.name(), "failed");
                continue;
              }
              String toolOutput = tech.wenisch.petri.mcp.McpToolNaming.isMcpTool(call.name())
                  ? mcp.execute(state, claimId, call)
                  : stateService.executeTool(runId, claimId, call);
              approvalGate.detect(call.name(), call.callId(), toolOutput).ifPresent(gated::add);
              events.record(
                  state.worktreeId(), runId, AgentRunEventType.TOOL_SUCCEEDED, call.name(), runActor,
                  "Agent run completed tool " + call.name());
              chatStream.toolLifecycle(state.chatId(), runId, call.name(), "completed");
            } catch (AgentRunCancellationException exception) {
              break;
            } catch (RuntimeException exception) {
              if (!(exception instanceof IllegalArgumentException
                  || exception instanceof IllegalStateException)) {
                // The model only sees a generic message; the cause belongs in the log.
                log.warn("Tool {} failed unexpectedly in run {}", call.name(), runId, exception);
              }
              String safeMessage = safeToolMessage(exception);
              try {
                stateService.recordToolFailure(runId, claimId, call, safeMessage);
              } catch (AgentRunCancellationException cancellation) {
                break;
              }
              events.record(
                  state.worktreeId(), runId, AgentRunEventType.TOOL_FAILED, call.name(), runActor,
                  "Agent run tool failed: " + call.name());
              chatStream.toolLifecycle(state.chatId(), runId, call.name(), "failed");
            }
            state = stateService.cancelIfRequested(runId, claimId, actor);
            if (state.terminal()) break;
          }
          state = stateService.cancelIfRequested(runId, claimId, actor);
          if (state.terminal()) break;
          // Only interactive runs (workspace chats, workflows) wait for a person to decide; runs
          // started by API keys cannot be approved by their client and keep returning pending.
          if (!gated.isEmpty() && actor != null && !actor.startsWith("api-key:")) {
            state = awaitApprovals(state, claimId, gated, actor);
            if (state.terminal()) break;
          }
          if (!state.pendingToolCalls().isEmpty()) continue;
        }

        if (state.modelTurnCount() >= state.maxModelTurns()) {
          state = stateService.exhaustBudget(runId, claimId, actor);
          continue;
        }

        ModelRequest request = request(state).withCredential(credentialOf(state));
        ModelClient client = modelClients.require(state.provider());
        UUID chatId = state.chatId();
        UUID workflowId = state.workflowId();
        AgentWorkflowRole workflowRole = state.workflowRole();
        StringBuilder partial = new StringBuilder();
        long[] checkpoint = {System.nanoTime()};
        ModelStreamListener listener = delta -> {
          partial.append(delta);
          chatStream.textDelta(chatId, runId, workflowId, workflowRole, delta);
          long elapsedMillis = (System.nanoTime() - checkpoint[0]) / 1_000_000;
          if (partial.length() >= 1024 || elapsedMillis >= 500) {
            stateService.recordPartialOutput(runId, claimId, partial.toString());
            checkpoint[0] = System.nanoTime();
          }
        };
        ModelResponse response;
        try {
          response = client.respondStreaming(request, listener);
        } catch (ModelClientException exception) {
          if (!canRetryWithoutNativeJson(request, partial, exception)) throw exception;
          response = client.respondStreaming(request.withJsonObjectOutput(false), listener);
        }
        if (!partial.isEmpty()) stateService.recordPartialOutput(runId, claimId, partial.toString());
        state = stateService.recordModelResponse(runId, claimId, response, actor);
        if (!state.terminal() && state.repetitionStrikes() > 0) {
          boolean withdrawn = state.repetitionStrikes() >= LoopGuard.WITHDRAW_TOOLS_AT;
          stateService.recordLoopGuard(runId, claimId, state.repetitionStrikes(), withdrawn);
          loopGuardMetrics.record(withdrawn, workflowRole);
          chatStream.toolLifecycle(chatId, runId, "loop_guard", "failed");
        }
      }
    } catch (RuntimeException exception) {
      // The message can carry request data, so only the type and the call site are logged.
      StackTraceElement[] frames = exception.getStackTrace();
      StringBuilder site = new StringBuilder();
      for (int index = 0; index < Math.min(6, frames.length); index++) {
        site.append("\n    at ").append(frames[index]);
      }
      log.warn("Agent run {} failed with {}{}", runId, exception.getClass().getName(), site);
      stateService.failExecution(runId, claimId, safeExecutionMessage(exception), actor);
    }
    AgentRunView result = runService.get(runId, expectedWorktreeId);
    if (result.status() == AgentRunStatus.COMPLETED || result.status() == AgentRunStatus.FAILED
        || result.status() == AgentRunStatus.BUDGET_EXHAUSTED || result.status() == AgentRunStatus.CANCELLED) {
      chatStream.runStatus(result.chatId(), result);
      eventPublisher.publishEvent(new AgentRunTerminalEvent(result.id(), result.chatId()));
    }
    return result;
  }

  /**
   * Holds the run until every approval-gated action created by the last tool calls is decided (or
   * auto-approved and finished), then gives the model the final outcome, including a decline.
   */
  private AgentExecutionState awaitApprovals(
      AgentExecutionState state, UUID claimId, java.util.List<ApprovalGate.Gated> gated,
      String actor) {
    UUID runId = state.runId();
    java.util.List<ApprovalGate.Gated> open = new java.util.ArrayList<>(gated);
    long deadline = System.nanoTime() + approvalWait.toNanos();
    long lastBeat = System.nanoTime();
    while (!open.isEmpty()) {
      for (java.util.Iterator<ApprovalGate.Gated> iterator = open.iterator(); iterator.hasNext();) {
        ApprovalGate.Gated item = iterator.next();
        var outcome = approvalGate.resolved(item);
        if (outcome.isPresent()) {
          stateService.amendToolResult(runId, claimId, item.callId(), outcome.get());
          iterator.remove();
        }
      }
      if (open.isEmpty()) break;
      if (System.nanoTime() >= deadline) {
        for (ApprovalGate.Gated item : open) {
          stateService.amendToolResult(runId, claimId, item.callId(), approvalGate.timedOut(item));
        }
        break;
      }
      state = stateService.cancelIfRequested(runId, claimId, actor);
      if (state.terminal()) return state;
      if (System.nanoTime() - lastBeat > 20_000_000_000L) {
        stateService.heartbeat(runId, claimId);
        lastBeat = System.nanoTime();
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for approval", exception);
      }
    }
    return stateService.cancelIfRequested(runId, claimId, actor);
  }

  /** Runs that predate provider instances resolve by kind inside the transport. */
  private static ModelCredentialRef credentialOf(AgentExecutionState state) {
    return state.providerInstanceId() == null
        ? null
        : new ModelCredentialRef(state.providerInstanceId(), state.createdBy());
  }

  private ModelRequest request(AgentExecutionState state) {
    AgentWorkflowRole role = state.workflowRole();
    boolean automation = state.automationRunId() != null;
    String instructions = automation && state.instructionsSnapshot() != null
        ? state.instructionsSnapshot()
        : role == null
            ? INSTRUCTIONS + EnvironmentGuidance.current() + VerificationGuidance.current()
            : state.instructionsSnapshot();
    if (instructions == null || instructions.isBlank()) {
      instructions = role == null
          ? INSTRUCTIONS + EnvironmentGuidance.current() + VerificationGuidance.current()
          : WorkflowPrompts.instructions(role, null);
    }
    var definitions = automation
        ? tools.definitions(state.automationTools() == null
            ? AutomationToolPolicy.allowed() : state.automationTools())
        : role == null
            ? tools.definitions() : tools.definitions(AgentWorkflowToolPolicy.allowed(role));
    var extra = mcp.definitions(state);
    if (!extra.isEmpty()) {
      var combined = new java.util.ArrayList<>(definitions);
      combined.addAll(extra);
      definitions = combined;
    }
    if (state.repetitionStrikes() > 0) {
      instructions = instructions + LoopGuard.guidance(state.repetitionStrikes());
      // Every tool is withdrawn, also in automations: the model has to answer with what it has.
      if (state.repetitionStrikes() >= LoopGuard.WITHDRAW_TOOLS_AT) definitions = java.util.List.of();
    }
    if (state.initialModelRequest()) {
      return new ModelRequest(
          state.model(), instructions, state.prompt(), null, null, definitions,
          state.maxOutputTokens(), state.reasoningEffort(), state.textVerbosity(),
          state.conversation(), role != null);
    }
    if (state.previousResponseId() == null || state.toolOutputs().isEmpty()) {
      throw new IllegalStateException("Agent run has no resumable model input");
    }
    return new ModelRequest(
        state.model(), instructions, null, state.previousResponseId(), state.toolOutputs(),
        definitions, state.maxOutputTokens(), state.reasoningEffort(),
        state.textVerbosity(), state.conversation(), role != null);
  }

  private static String safeToolMessage(RuntimeException exception) {
    if ((exception instanceof IllegalArgumentException
            || exception instanceof IllegalStateException)
        && exception.getMessage() != null
        && !exception.getMessage().isBlank()) {
      return exception.getMessage();
    }
    return "Tool execution failed";
  }

  static boolean canRetryWithoutNativeJson(
      ModelRequest request, StringBuilder partial, ModelClientException exception) {
    if (!request.jsonObjectOutput() || !partial.isEmpty() || exception.getMessage() == null) {
      return false;
    }
    String message = exception.getMessage();
    return message.contains("HTTP 400") || message.contains("HTTP 422");
  }

  private static String safeExecutionMessage(RuntimeException exception) {
    if (exception instanceof ModelClientException
        && exception.getMessage() != null
        && !exception.getMessage().isBlank()) {
      return exception.getMessage();
    }
    if (exception instanceof IllegalArgumentException) return "Model response was invalid";
    return "Agent execution failed";
  }
}
