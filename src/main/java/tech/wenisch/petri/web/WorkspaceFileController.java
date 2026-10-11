package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.agent.tool.FileConflictException;
import tech.wenisch.petri.agent.tool.ListFilesResult;
import tech.wenisch.petri.agent.tool.ReadFileResult;
import tech.wenisch.petri.agent.tool.WorkspaceEntryMutation;
import tech.wenisch.petri.workspace.WorkspaceEditorService;
import tech.wenisch.petri.project.ProjectAccessService;

@RestController
@RequestMapping("/workspace/api/worktrees/{worktreeId}")
public class WorkspaceFileController {
  private final WorkspaceEditorService editor;
  private final ProjectAccessService access;

  public WorkspaceFileController(WorkspaceEditorService editor, ProjectAccessService access) {
    this.editor = editor;
    this.access = access;
  }

  @GetMapping("/tree")
  public ResponseEntity<?> tree(@PathVariable UUID worktreeId, @RequestParam(required = false) String path,
      Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return invoke(() -> editor.tree(worktreeId, path));
  }

  @GetMapping("/files")
  public ResponseEntity<?> read(@PathVariable UUID worktreeId, @RequestParam String path, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return invoke(() -> editor.read(worktreeId, path));
  }

  @PutMapping("/files")
  public ResponseEntity<?> save(
      @PathVariable UUID worktreeId, @RequestBody SaveWorkspaceFileRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return invoke(() -> editor.save(
        worktreeId, request.path(), request.content(), request.expectedSha256(), principal.getName()));
  }

  @PostMapping("/entries")
  public ResponseEntity<?> create(
      @PathVariable UUID worktreeId, @RequestBody CreateWorkspaceEntryRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return invoke(HttpStatus.CREATED, () -> editor.create(worktreeId, request.path(),
        "DIRECTORY".equals(request.type()), request.content(), principal.getName()));
  }

  @PostMapping("/entries/move")
  public ResponseEntity<?> move(
      @PathVariable UUID worktreeId, @RequestBody MoveWorkspaceEntryRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return invoke(() -> editor.move(worktreeId, request.sourcePath(), request.targetPath(),
        request.expectedSha256(), principal.getName()));
  }

  @PostMapping("/entries/delete")
  public ResponseEntity<?> delete(
      @PathVariable UUID worktreeId, @RequestBody DeleteWorkspaceEntryRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    if (request.recursive() && !request.path().equals(request.confirmationPath())) {
      return problem(HttpStatus.BAD_REQUEST, "Recursive deletion requires the exact path confirmation");
    }
    return invoke(() -> {
      editor.delete(worktreeId, request.path(), request.expectedSha256(), request.recursive(), principal.getName());
      return java.util.Map.of("deleted", request.path());
    });
  }

  private ResponseEntity<?> invoke(WorkspaceOperation operation) {
    return invoke(HttpStatus.OK, operation);
  }

  private ResponseEntity<?> invoke(HttpStatus success, WorkspaceOperation operation) {
    try {
      return ResponseEntity.status(success).body(operation.run());
    } catch (FileConflictException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    } catch (IllegalArgumentException exception) {
      String message = exception.getMessage() == null ? "Invalid workspace request" : exception.getMessage();
      if (message.contains("does not exist")) return problem(HttpStatus.NOT_FOUND, message);
      if (message.contains("exceeds")) return problem(HttpStatus.PAYLOAD_TOO_LARGE, message);
      return problem(HttpStatus.BAD_REQUEST, message);
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.CONFLICT, exception.getMessage());
    }
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  @FunctionalInterface
  private interface WorkspaceOperation { Object run(); }

  public record SaveWorkspaceFileRequest(String path, String content, String expectedSha256) {}
  public record CreateWorkspaceEntryRequest(String path, String type, String content) {
    public CreateWorkspaceEntryRequest {
      if (!"FILE".equals(type) && !"DIRECTORY".equals(type)) {
        throw new IllegalArgumentException("Entry type must be FILE or DIRECTORY");
      }
    }
  }
  public record MoveWorkspaceEntryRequest(String sourcePath, String targetPath, String expectedSha256) {}
  public record DeleteWorkspaceEntryRequest(
      String path, String expectedSha256, boolean recursive, String confirmationPath) {}
}
