package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowService;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowStartView;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowView;
import tech.wenisch.petri.agent.orchestration.CreateAgentWorkflowCommand;
import tech.wenisch.petri.agent.orchestration.OrchestrationProfileService;
import tech.wenisch.petri.agent.orchestration.OrchestrationRoleProfileView;
import tech.wenisch.petri.agent.orchestration.SaveOrchestrationRoleProfile;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/workspace/api")
public class AgentWorkflowController {
  private final AgentWorkflowService workflows;
  private final OrchestrationProfileService profiles;
  private final ProjectAccessService access;
  private final AppUserRepository users;

  public AgentWorkflowController(
      AgentWorkflowService workflows, OrchestrationProfileService profiles,
      ProjectAccessService access, AppUserRepository users) {
    this.workflows = workflows; this.profiles = profiles; this.access = access; this.users = users;
  }

  @GetMapping("/projects/{projectId}/orchestration-profile")
  public List<OrchestrationRoleProfileView> profile(
      @PathVariable UUID projectId, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return profiles.get(projectId, actor(principal).getId());
  }

  @PutMapping("/projects/{projectId}/orchestration-profile")
  public List<OrchestrationRoleProfileView> saveProfile(
      @PathVariable UUID projectId,
      @RequestBody List<SaveOrchestrationRoleProfile> request,
      Principal principal) {
    access.requireProject(projectId, principal.getName());
    AppUser actor = actor(principal);
    return profiles.save(projectId, actor.getId(), actor.getEmail(), request);
  }

  @PostMapping("/worktrees/{worktreeId}/workflows")
  public AgentWorkflowStartView create(
      @PathVariable UUID worktreeId,
      @RequestBody CreateAgentWorkflowCommand request,
      Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    AppUser actor = actor(principal);
    return workflows.create(worktreeId, request, actor.getId(), actor.getEmail());
  }

  @GetMapping("/workflows/{workflowId}")
  public AgentWorkflowView get(@PathVariable UUID workflowId, Principal principal) {
    return workflows.get(workflowId, actor(principal).getId());
  }

  @PostMapping("/workflows/{workflowId}/clarifications")
  public AgentWorkflowView answer(
      @PathVariable UUID workflowId, @RequestBody ClarificationRequest request, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.answer(workflowId, actor.getId(), request.answers(), actor.getEmail());
  }

  @PostMapping("/workflows/{workflowId}/plan/approve")
  public AgentWorkflowView approve(
      @PathVariable UUID workflowId, @RequestBody PlanApprovalRequest request, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.approvePlan(workflowId, request.revision(), actor.getId(), actor.getEmail());
  }

  @PostMapping("/workflows/{workflowId}/final/approve")
  public AgentWorkflowView approveFinal(@PathVariable UUID workflowId, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.approveFinal(workflowId, actor.getId(), actor.getEmail());
  }

  @PostMapping("/workflows/{workflowId}/plan/revise")
  public AgentWorkflowView revise(
      @PathVariable UUID workflowId, @RequestBody TextRequest request, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.revisePlan(workflowId, request.text(), actor.getId(), actor.getEmail());
  }

  @PostMapping("/workflows/{workflowId}/guidance")
  public AgentWorkflowView guidance(
      @PathVariable UUID workflowId, @RequestBody TextRequest request, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.guidance(workflowId, request.text(), actor.getId(), actor.getEmail());
  }

  @PostMapping("/workflows/{workflowId}/retry")
  public AgentWorkflowView retry(@PathVariable UUID workflowId, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.retry(workflowId, actor.getId(), actor.getEmail());
  }

  @PostMapping("/workflows/{workflowId}/cancel")
  public AgentWorkflowView cancel(@PathVariable UUID workflowId, Principal principal) {
    AppUser actor = actor(principal);
    return workflows.cancel(workflowId, actor.getId(), actor.getEmail());
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
    if ("Unknown workflow".equals(exception.getMessage())) {
      return problem(HttpStatus.NOT_FOUND, "Workflow not found");
    }
    return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<ProblemDetail> conflict(IllegalStateException exception) {
    return problem(HttpStatus.CONFLICT, exception.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,
        detail == null || detail.isBlank() ? status.getReasonPhrase() : detail));
  }

  public record ClarificationRequest(JsonNode answers) {}
  public record PlanApprovalRequest(int revision) {}
  public record TextRequest(String text) {}
}
