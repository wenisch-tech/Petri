package tech.wenisch.petri.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.petri.agent.event.AgentRunEventService;
import tech.wenisch.petri.agent.event.AgentRunEventStream;
import tech.wenisch.petri.agent.event.AgentRunEventType;
import tech.wenisch.petri.agent.event.AgentRunEventView;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;
import tech.wenisch.petri.agent.tool.ToolDefinition;
import tech.wenisch.petri.agent.tool.ToolExecutionContext;
import tech.wenisch.petri.agent.tool.ToolExecutionResult;
import tech.wenisch.petri.apikey.ApiKeyPrincipal;
import tech.wenisch.petri.workspace.WorktreeLocator;
import tech.wenisch.petri.project.ProjectAccessService;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentApiController {
  private final AgentToolRegistry tools;
  private final WorktreeLocator worktrees;
  private final AgentRunEventService events;
  private final AgentRunEventStream eventStream;
  private final ProjectAccessService access;

  public AgentApiController(
      AgentToolRegistry tools,
      WorktreeLocator worktrees,
      AgentRunEventService events,
      AgentRunEventStream eventStream,
      ProjectAccessService access) {
    this.tools = tools;
    this.worktrees = worktrees;
    this.events = events;
    this.eventStream = eventStream;
    this.access = access;
  }

  @GetMapping("/tools")
  public List<ToolDefinition> tools() {
    return tools.definitions();
  }

  @PostMapping("/worktrees/{worktreeId}/tools/{toolName}")
  public ResponseEntity<?> execute(
      @PathVariable UUID worktreeId,
      @PathVariable String toolName,
      @RequestBody(required = false) Map<String, Object> input,
      Authentication authentication) {
    String safeToolName = safeToolName(toolName);
    if ("invalid".equals(safeToolName)) {
      return problem(HttpStatus.BAD_REQUEST, "Invalid tool name");
    }
    try {
      access.requireWorktree(worktreeId, principal(authentication).createdBy());
      worktrees.requireActiveWorktree(worktreeId);
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
    String actor = actor(authentication);
    events.record(
        worktreeId, AgentRunEventType.TOOL_STARTED, safeToolName, actor,
        "Started agent tool " + safeToolName);
    try {
      ToolExecutionResult result = tools.execute(
          toolName, new ToolExecutionContext(worktreeId), input == null ? Map.of() : input);
      events.record(
          worktreeId, AgentRunEventType.TOOL_SUCCEEDED, safeToolName, actor,
          "Completed agent tool " + safeToolName);
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException exception) {
      events.record(
          worktreeId, AgentRunEventType.TOOL_FAILED, safeToolName, actor,
          "Rejected agent tool " + safeToolName);
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      events.record(
          worktreeId, AgentRunEventType.TOOL_FAILED, safeToolName, actor,
          "Agent tool could not run: " + safeToolName);
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    } catch (RuntimeException exception) {
      events.record(
          worktreeId, AgentRunEventType.TOOL_FAILED, safeToolName, actor,
          "Agent tool failed: " + safeToolName);
      throw exception;
    }
  }

  @GetMapping("/worktrees/{worktreeId}/events")
  public List<AgentRunEventView> events(
      @PathVariable UUID worktreeId, Authentication authentication) {
    access.requireWorktree(worktreeId, principal(authentication).createdBy());
    worktrees.requireActiveWorktree(worktreeId);
    return events.recent(worktreeId);
  }

  @GetMapping(
      value = "/worktrees/{worktreeId}/events/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID worktreeId, Authentication authentication) {
    access.requireWorktree(worktreeId, principal(authentication).createdBy());
    worktrees.requireActiveWorktree(worktreeId);
    return eventStream.subscribe(worktreeId);
  }

  private static String actor(Authentication authentication) {
    if (authentication.getPrincipal() instanceof ApiKeyPrincipal principal) {
      return "api-key:" + principal.id();
    }
    return authentication.getName();
  }

  private static ApiKeyPrincipal principal(Authentication authentication) {
    if (authentication.getPrincipal() instanceof ApiKeyPrincipal principal) return principal;
    throw new IllegalStateException("API key authentication is required");
  }

  private static String safeToolName(String toolName) {
    if (toolName == null || !toolName.matches("[a-z0-9_]{1,80}")) return "invalid";
    return toolName;
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    return ResponseEntity.status(status).body(problem);
  }
}
