package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import tech.wenisch.petri.agent.tool.ListFilesResult;
import tech.wenisch.petri.agent.tool.ReadFileResult;
import tech.wenisch.petri.agent.tool.ReplacementPreview;
import tech.wenisch.petri.agent.tool.SearchFilesResult;
import tech.wenisch.petri.agent.tool.WorkspaceEntryMutation;

/** Guarded file operations inside one worktree of a host. */
public interface WorkspaceFiles {
  ListFilesResult list(Path worktree, String requestedPath, int maxDepth);

  ReadFileResult read(Path worktree, String requestedPath);

  WorkspaceEntryMutation metadata(Path worktree, String requestedPath);

  SearchFilesResult search(Path worktree, String requestedPath, String query, int requestedMaxResults);

  ReplacementPreview previewReplacement(
      Path worktree, String requestedPath, String expectedSha256, String replacementContent);

  ReplacementPreview previewCreation(Path worktree, String requestedPath, String content);

  ReadFileResult applyReplacement(
      Path worktree, String requestedPath, String expectedSha256, String replacementContent);

  ReadFileResult applyCreation(Path worktree, String requestedPath, String content);

  WorkspaceEntryMutation createDirectory(Path worktree, String requestedPath);

  WorkspaceEntryMutation move(
      Path worktree, String sourcePath, String targetPath, String expectedSha256);

  void delete(Path worktree, String requestedPath, String expectedSha256, boolean recursive);

  /** Reads a regular, non-symlink file inside the worktree if it is at most {@code maxBytes}. */
  RawFile readRaw(Path worktree, String requestedPath, long maxBytes);

  /**
   * Copies regular files of {@code source} into {@code destination} (both on this host), skipping
   * the named directories. Returns false when the byte or file cap was exceeded.
   */
  boolean copyTree(Path source, Path destination, Set<String> skipDirectories, long maxBytes,
      int maxFiles) throws IOException;
}
