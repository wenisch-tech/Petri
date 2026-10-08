package tech.wenisch.petri.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.orchestration.CreateAgentRunCommand;
import tech.wenisch.petri.apikey.ApiKeyPrincipal;

@RestController
@RequestMapping("/api/v1/agent/worktrees/{worktreeId}/runs")
public class AgentRunApiController {
  private final AgentRunService runs;

  public AgentRunApiController(AgentRunService runs) {
    this.runs = runs;
  }

  @PostMapping
  public ResponseEntity<?> create(
      @PathVariable UUID worktreeId,
      @RequestBody CreateAgentRunCommand command,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
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
  public List<AgentRunView> list(@PathVariable UUID worktreeId) {
    return runs.list(worktreeId);
  }

  @GetMapping("/{runId}")
  public AgentRunView get(@PathVariable UUID worktreeId, @PathVariable UUID runId) {
    return runs.get(runId, worktreeId);
  }

  @PostMapping("/{runId}/cancel")
  public ResponseEntity<?> cancel(
      @PathVariable UUID worktreeId,
      @PathVariable UUID runId,
      Authentication authentication) {
    ApiKeyPrincipal principal = principal(authentication);
    try {
      runs.get(runId, worktreeId);
      return ResponseEntity.ok(runs.requestCancellation(
          runId, principal.createdBy(), "api-key:" + principal.id()));
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  private static ApiKeyPrincipal principal(Authentication authentication) {
    if (authentication.getPrincipal() instanceof ApiKeyPrincipal principal) return principal;
    throw new IllegalStateException("API key authentication is required");
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}
