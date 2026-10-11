package tech.wenisch.petri.workspace;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.tool.ListFilesResult;
import tech.wenisch.petri.agent.tool.ReadFileResult;
import tech.wenisch.petri.agent.tool.WorkspaceEntryMutation;
import tech.wenisch.petri.agent.tool.WorkspaceFileService;
import tech.wenisch.petri.audit.AuditLogService;

/** Browser-facing workspace operations; all paths remain confined by WorkspaceFileService. */
@Service
public class WorkspaceEditorService {
  private final WorktreeLocator worktrees;
  private final WorkspaceFileService files;
  private final AuditLogService auditLog;

  public WorkspaceEditorService(
      WorktreeLocator worktrees, WorkspaceFileService files, AuditLogService auditLog) {
    this.worktrees = worktrees;
    this.files = files;
    this.auditLog = auditLog;
  }

  public ListFilesResult tree(UUID worktreeId, String path) {
    return files.list(worktree(worktreeId), path == null || path.isBlank() ? "." : path, 1);
  }

  public ReadFileResult read(UUID worktreeId, String path) {
    return files.read(worktree(worktreeId), path);
  }

  public WorkspaceEntryMutation metadata(UUID worktreeId, String path) {
    return files.metadata(worktree(worktreeId), path);
  }

  public ReadFileResult save(
      UUID worktreeId, String path, String content, String expectedSha256, String actor) {
    ReadFileResult saved = files.applyReplacement(worktree(worktreeId), path, expectedSha256, content);
    audit(actor, "WORKSPACE_FILE_SAVED", worktreeId, "Saved " + saved.path());
    return saved;
  }

  public WorkspaceEntryMutation create(
      UUID worktreeId, String path, boolean directory, String content, String actor) {
    WorkspaceEntryMutation created;
    if (directory) {
      created = files.createDirectory(worktree(worktreeId), path);
    } else {
      ReadFileResult file = files.applyCreation(worktree(worktreeId), path, content == null ? "" : content);
      created = new WorkspaceEntryMutation(file.path(), "FILE", file.sha256(), file.size());
    }
    audit(actor, "WORKSPACE_ENTRY_CREATED", worktreeId, "Created " + created.path());
    return created;
  }

  public WorkspaceEntryMutation move(
      UUID worktreeId, String sourcePath, String targetPath, String expectedSha256, String actor) {
    WorkspaceEntryMutation moved = files.move(
        worktree(worktreeId), sourcePath, targetPath, expectedSha256);
    audit(actor, "WORKSPACE_ENTRY_MOVED", worktreeId, "Moved " + sourcePath + " to " + moved.path());
    return moved;
  }

  public void delete(
      UUID worktreeId, String path, String expectedSha256, boolean recursive, String actor) {
    files.delete(worktree(worktreeId), path, expectedSha256, recursive);
    audit(actor, "WORKSPACE_ENTRY_DELETED", worktreeId, "Deleted " + path);
  }

  private Path worktree(UUID worktreeId) {
    return worktrees.requireActiveWorktree(worktreeId);
  }

  private void audit(String actor, String action, UUID worktreeId, String summary) {
    auditLog.record(actor, action, "PROJECT_WORKTREE", worktreeId, summary);
  }
}
