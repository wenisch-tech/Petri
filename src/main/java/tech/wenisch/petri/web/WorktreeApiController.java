package tech.wenisch.petri.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.apikey.ApiKeyPrincipal;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.security.AppUserRepository;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/worktrees")
public class WorktreeApiController {
  private final ProjectWorktreeService worktrees;
  private final AppUserRepository users;

  public WorktreeApiController(ProjectWorktreeService worktrees, AppUserRepository users) {
    this.worktrees = worktrees;
    this.users = users;
  }

  @GetMapping
  public List<ProjectWorktreeView> list(@PathVariable UUID projectId) {
    return worktrees.list(projectId);
  }

  @PostMapping
  public ResponseEntity<?> create(
      @PathVariable UUID projectId,
      @RequestBody(required = false) CreateWorktreeCommand command,
      Authentication authentication) {
    try {
      String actor = actorEmail(authentication);
      String revision = command == null ? null : command.revision();
      return ResponseEntity.status(HttpStatus.CREATED).body(worktrees.create(projectId, revision, actor));
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  @DeleteMapping("/{worktreeId}")
  public ResponseEntity<?> remove(
      @PathVariable UUID projectId,
      @PathVariable UUID worktreeId,
      Authentication authentication) {
    try {
      return ResponseEntity.ok(worktrees.remove(worktreeId, projectId, actorEmail(authentication)));
    } catch (IllegalArgumentException exception) {
      return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  private String actorEmail(Authentication authentication) {
    if (!(authentication.getPrincipal() instanceof ApiKeyPrincipal principal)) {
      throw new IllegalStateException("API key authentication is required");
    }
    return users.findById(principal.createdBy())
        .orElseThrow(() -> new IllegalStateException("API key owner no longer exists"))
        .getEmail();
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}

