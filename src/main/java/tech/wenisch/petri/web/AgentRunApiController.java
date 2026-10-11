package tech.wenisch.petri.web;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.petri.agent.event.AgentRunEventService;
import tech.wenisch.petri.agent.event.AgentRunEventStream;
import tech.wenisch.petri.agent.event.AgentRunEventView;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunExecutor;
import tech.wenisch.petri.agent.orchestration.AgentRunLauncher;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.orchestration.CreateAgentRunCommand;
import tech.wenisch.petri.apikey.ApiKeyPrincipal;
import tech.wenisch.petri.project.ProjectAccessService;

@RestController
@RequestMapping("/api/v1/agent/worktrees/{worktreeId}/runs")
public class AgentRunApiController {
  private final AgentRunService runs;
  private final AgentRunExecutor executor;
  private final AgentRunLauncher launcher;
  private final AgentRunEventService events;
  private final AgentRunEventStream eventStream;
  private final ProjectAccessService access;

  public AgentRunApiController(
      AgentRunService runs,
      AgentRunExecutor executor,
      AgentRunLauncher launcher,
      AgentRunEventService events,
      AgentRunEventStream eventStream,
      ProjectAccessService access) {
    this.runs = runs;
    this.executor = executor;
    this.launcher = launcher;
    this.events = events;
    this.eventStream = eventStream;
    this.access = access;
  }

  @PostMapping("/start")
  public ResponseEntity<?> createAndStart(
      @PathVariable UUID worktreeId,
      @RequestBody CreateAgentRunCommand command,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
      access.requireWorktree(worktreeId, principal.createdBy());
      AgentRunView run = runs.create(
          worktreeId, command, principal.createdBy(), "api-key:" + principal.id());
      launcher.launch(
          run.id(), run.worktreeId(), principal.createdBy(), "api-key:" + principal.id());
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  @PostMapping
  public ResponseEntity<?> create(
      @PathVariable UUID worktreeId,
      @RequestBody CreateAgentRunCommand command,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
      access.requireWorktree(worktreeId, principal.createdBy());
      AgentRunView run = runs.create(
          worktreeId, command, principal.createdBy(), "api-key:" + principal.id());
      return ResponseEntity.status(HttpStatus.CREATED).body(run);
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  @GetMapping
  public List<AgentRunView> list(
      @PathVariable UUID worktreeId,
      @RequestParam(defaultValue = "false") boolean includeWorkflowStages,
      Authentication authentication) {
    access.requireWorktree(worktreeId, principal(authentication).createdBy());
    return runs.list(worktreeId, includeWorkflowStages);
  }

  @GetMapping("/{runId}")
  public AgentRunView get(
      @PathVariable UUID worktreeId, @PathVariable UUID runId, Authentication authentication) {
    access.requireWorktree(worktreeId, principal(authentication).createdBy());
    return runs.get(runId, worktreeId);
  }

  @PostMapping("/{runId}/cancel")
  public ResponseEntity<?> cancel(
      @PathVariable UUID worktreeId,
      @PathVariable UUID runId,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
      access.requireWorktree(worktreeId, principal.createdBy());
      runs.get(runId, worktreeId);
      return ResponseEntity.ok(runs.requestCancellation(
          runId, principal.createdBy(), "api-key:" + principal.id()));
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  @PostMapping("/{runId}/execute")
  public ResponseEntity<?> execute(
      @PathVariable UUID worktreeId,
      @PathVariable UUID runId,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
      access.requireWorktree(worktreeId, principal.createdBy());
      return ResponseEntity.ok(executor.execute(
          runId, worktreeId, principal.createdBy(), "api-key:" + principal.id()));
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  @PostMapping("/{runId}/resume")
  public ResponseEntity<?> resume(
      @PathVariable UUID worktreeId,
      @PathVariable UUID runId,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
      access.requireWorktree(worktreeId, principal.createdBy());
      AgentRunView run = runs.get(runId, worktreeId);
      if (!run.createdBy().equals(principal.createdBy())) {
        return problem(HttpStatus.BAD_REQUEST, "Agent run belongs to another creator");
      }
      launcher.launch(
          run.id(), run.worktreeId(), principal.createdBy(), "api-key:" + principal.id());
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  @GetMapping("/{runId}/events")
  public List<AgentRunEventView> events(
      @PathVariable UUID worktreeId, @PathVariable UUID runId, Authentication authentication) {
    access.requireWorktree(worktreeId, principal(authentication).createdBy());
    runs.get(runId, worktreeId);
    return events.recentForRun(runId);
  }

  @GetMapping(value = "/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable UUID worktreeId, @PathVariable UUID runId, Authentication authentication) {
    access.requireWorktree(worktreeId, principal(authentication).createdBy());
    runs.get(runId, worktreeId);
    return eventStream.subscribeToRun(runId, events.recentForRun(runId));
  }

  private static ApiKeyPrincipal principal(Authentication authentication) {
    if (authentication.getPrincipal() instanceof ApiKeyPrincipal principal) return principal;
    throw new IllegalStateException("API key authentication is required");
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}
