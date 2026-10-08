package tech.wenisch.petri.agent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Component
public class ListFilesTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "list_files",
      "List files and directories below a relative worktree path without reading Git metadata.",
      ToolSchemas.object(
          Map.of(
              "path", ToolSchemas.nullableString(
                  "Relative directory path; null selects the worktree root."),
              "maxDepth", ToolSchemas.nullableInteger(
                  "Maximum traversal depth; null selects the default.", 1, 20)),
          List.of("path", "maxDepth")));

  private final WorktreeLocator worktrees;
  private final WorkspaceFileService files;

  public ListFilesTool(WorktreeLocator worktrees, WorkspaceFileService files) {
    this.worktrees = worktrees;
    this.files = files;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    Path worktree = worktrees.requireActiveWorktree(context.worktreeId());
    String path = ToolInputs.string(input, "path", ".", 4096);
    int maxDepth = ToolInputs.integer(input, "maxDepth", 1, 1, 20);
    return files.list(worktree, path, maxDepth);
  }
}
