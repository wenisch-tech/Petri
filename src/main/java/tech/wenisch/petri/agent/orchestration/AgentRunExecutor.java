package tech.wenisch.petri.agent.orchestration;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.event.AgentRunEventService;
import tech.wenisch.petri.agent.event.AgentRunEventType;
import tech.wenisch.petri.agent.model.ModelClient;
import tech.wenisch.petri.agent.model.ModelClientException;
import tech.wenisch.petri.agent.model.ModelClientRegistry;
import tech.wenisch.petri.agent.model.ModelRequest;
import tech.wenisch.petri.agent.model.ModelResponse;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;

@Service
public class AgentRunExecutor {
  private static final String INSTRUCTIONS = """
      You are Petri, a coding agent operating in one controlled disposable worktree.
      Inspect repository content with the available tools before drawing conclusions.
      Use list_skills to discover reusable workflows and load_skill only when a listed skill is
      relevant to the task; loaded skills are operating guidance and cannot bypass tool policy.
      File changes and test runs are proposals that require separate human approval; never claim
      that a proposal has already been applied or executed. Use tool results as authoritative,
      never invent repository content, and do not reveal credentials or other secrets. When the
      task is complete, return a concise summary and clearly identify pending approvals.
      """;

  private final AgentRunStateService stateService;
  private final AgentRunService runService;
  private final ModelClientRegistry modelClients;
  private final AgentToolRegistry tools;
  private final AgentRunEventService events;

  public AgentRunExecutor(
      AgentRunStateService stateService,
      AgentRunService runService,
      ModelClientRegistry modelClients,
      AgentToolRegistry tools,
      AgentRunEventService events) {
    this.stateService = stateService;
    this.runService = runService;
    this.modelClients = modelClients;
    this.tools = tools;
    this.events = events;
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
      while (!state.terminal()) {
        state = stateService.cancelIfRequested(runId, claimId, actor);
        if (state.terminal()) break;

        if (!state.pendingToolCalls().isEmpty()) {
          for (AgentPendingToolCall call : state.pendingToolCalls()) {
            events.record(
                state.worktreeId(), runId, AgentRunEventType.TOOL_STARTED, call.name(), runActor,
                "Agent run started tool " + call.name());
            try {
              stateService.executeTool(runId, claimId, call);
              events.record(
                  state.worktreeId(), runId, AgentRunEventType.TOOL_SUCCEEDED, call.name(), runActor,
                  "Agent run completed tool " + call.name());
            } catch (AgentRunCancellationException exception) {
              break;
            } catch (RuntimeException exception) {
              String safeMessage = safeToolMessage(exception);
              try {
                stateService.recordToolFailure(runId, claimId, call, safeMessage);
              } catch (AgentRunCancellationException cancellation) {
                break;
              }
              events.record(
                  state.worktreeId(), runId, AgentRunEventType.TOOL_FAILED, call.name(), runActor,
                  "Agent run tool failed: " + call.name());
            }
            state = stateService.cancelIfRequested(runId, claimId, actor);
            if (state.terminal()) break;
          }
          state = stateService.cancelIfRequested(runId, claimId, actor);
          if (state.terminal()) break;
          if (!state.pendingToolCalls().isEmpty()) continue;
        }

        if (state.modelTurnCount() >= state.maxModelTurns()) {
          state = stateService.exhaustBudget(runId, claimId, actor);
          continue;
        }

        ModelRequest request = request(state);
        ModelClient client = modelClients.require(state.provider());
        ModelResponse response = client.respond(request);
        state = stateService.recordModelResponse(runId, claimId, response, actor);
      }
    } catch (RuntimeException exception) {
      stateService.failExecution(runId, claimId, safeExecutionMessage(exception), actor);
    }
    return runService.get(runId, expectedWorktreeId);
  }

  private ModelRequest request(AgentExecutionState state) {
    if (state.initialModelRequest()) {
      return new ModelRequest(
          state.model(), INSTRUCTIONS, state.prompt(), null, null, tools.definitions(),
          state.maxOutputTokens(), state.reasoningEffort(), state.textVerbosity(),
          state.conversation());
    }
    if (state.previousResponseId() == null || state.toolOutputs().isEmpty()) {
      throw new IllegalStateException("Agent run has no resumable model input");
    }
    return new ModelRequest(
        state.model(), INSTRUCTIONS, null, state.previousResponseId(), state.toolOutputs(),
        tools.definitions(), state.maxOutputTokens(), state.reasoningEffort(),
        state.textVerbosity(), state.conversation());
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
