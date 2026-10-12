package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.git.CommitMessageService;
import tech.wenisch.petri.git.GitBranchKind;
import tech.wenisch.petri.git.GitBranchView;
import tech.wenisch.petri.git.GitOperationException;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.WorkspaceDiffScope;
import tech.wenisch.petri.git.WorkspaceDiffView;
import tech.wenisch.petri.git.WorkspaceGitDiffService;
import tech.wenisch.petri.git.WorkspaceGitService;
import tech.wenisch.petri.git.WorkspaceGitStatusView;
import tech.wenisch.petri.project.ProjectAccessService;

/** Safe JSON boundary for branch-bound workspace Git operations. */
@RestController
@RequestMapping("/workspace/api")
public class WorkspaceGitController {
  private final WorkspaceGitService git;
  private final WorkspaceGitDiffService diffs;
  private final ProjectAccessService access;
  private final CommitMessageService commitMessages;

  public WorkspaceGitController(
      WorkspaceGitService git, WorkspaceGitDiffService diffs, ProjectAccessService access,
      CommitMessageService commitMessages) {
    this.commitMessages = commitMessages;
    this.git = git;
    this.diffs = diffs;
    this.access = access;
  }

  @GetMapping("/projects/{projectId}/branches")
  public List<GitBranchView> branches(@PathVariable UUID projectId, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return git.branches(projectId);
  }

  @PostMapping("/projects/{projectId}/git/fetch")
  public List<GitBranchView> fetch(@PathVariable UUID projectId, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return git.fetch(projectId, principal.getName());
  }

  @PostMapping("/projects/{projectId}/branches")
  public ProjectWorktreeView createBranch(
      @PathVariable UUID projectId, @RequestBody CreateBranchRequest request, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return git.createBranch(projectId, request.name(), request.startPoint(), principal.getName());
  }

  @PostMapping("/projects/{projectId}/branches/open")
  public ProjectWorktreeView openBranch(
      @PathVariable UUID projectId, @RequestBody OpenBranchRequest request, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return git.openBranch(projectId, request.name(), request.kind(), principal.getName());
  }

  @PostMapping("/projects/{projectId}/branches/delete")
  public List<GitBranchView> deleteBranch(
      @PathVariable UUID projectId, @RequestBody DeleteBranchRequest request, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return git.deleteBranch(projectId, request.name(), request.kind(), request.force(),
        request.confirmation(), principal.getName());
  }

  @GetMapping("/worktrees/{worktreeId}/git/status")
  public WorkspaceGitStatusView status(@PathVariable UUID worktreeId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.status(worktreeId);
  }

  @GetMapping("/worktrees/{worktreeId}/git/diff")
  public WorkspaceDiffView diff(
      @PathVariable UUID worktreeId,
      @RequestParam String path,
      @RequestParam(defaultValue = "COMBINED") WorkspaceDiffScope scope,
      Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return diffs.diff(worktreeId, path, scope);
  }

  @PostMapping("/worktrees/{worktreeId}/git/stage")
  public WorkspaceGitStatusView stage(
      @PathVariable UUID worktreeId, @RequestBody PathsRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.stage(worktreeId, request.paths(), principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/unstage")
  public WorkspaceGitStatusView unstage(
      @PathVariable UUID worktreeId, @RequestBody PathsRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.unstage(worktreeId, request.paths(), principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/discard")
  public WorkspaceGitStatusView discard(
      @PathVariable UUID worktreeId, @RequestBody PathsRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.discard(worktreeId, request.paths(), principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/commit")
  public WorkspaceGitStatusView commit(
      @PathVariable UUID worktreeId, @RequestBody CommitRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.commit(worktreeId, request.message(), request.stageAll(), principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/commit-message")
  public CommitMessageResponse commitMessage(
      @PathVariable UUID worktreeId, @RequestBody CommitMessageRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return new CommitMessageResponse(
        commitMessages.generate(worktreeId, request.stageAll(), principal.getName()));
  }

  @PostMapping("/worktrees/{worktreeId}/git/pull")
  public WorkspaceGitStatusView pull(@PathVariable UUID worktreeId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.pull(worktreeId, principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/rebase")
  public WorkspaceGitStatusView rebase(
      @PathVariable UUID worktreeId, @RequestBody RebaseRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.rebase(worktreeId, request.target(), principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/rebase/continue")
  public WorkspaceGitStatusView continueRebase(@PathVariable UUID worktreeId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.continueRebase(worktreeId, principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/rebase/abort")
  public WorkspaceGitStatusView abortRebase(@PathVariable UUID worktreeId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.abortRebase(worktreeId, principal.getName());
  }

  @PostMapping("/worktrees/{worktreeId}/git/push")
  public WorkspaceGitStatusView push(
      @PathVariable UUID worktreeId, @RequestBody PushRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return git.push(worktreeId, request.forceWithLease(), principal.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
    return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler({IllegalStateException.class, GitOperationException.class})
  ResponseEntity<ProblemDetail> conflict(RuntimeException exception) {
    return problem(HttpStatus.CONFLICT, exception.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  public record CreateBranchRequest(String name, String startPoint) {}
  public record OpenBranchRequest(String name, GitBranchKind kind) {}
  public record DeleteBranchRequest(
      String name, GitBranchKind kind, boolean force, String confirmation) {}
  public record PathsRequest(List<String> paths) {}
  public record CommitRequest(String message, boolean stageAll) {}
  public record CommitMessageRequest(boolean stageAll) {}
  public record CommitMessageResponse(String message) {}
  public record RebaseRequest(String target) {}
  public record PushRequest(boolean forceWithLease) {}
}
