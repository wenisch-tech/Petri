package tech.wenisch.petri.agent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Component
public class ReadFileTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "read_file",
      "Read one UTF-8 text file and return its content plus a SHA-256 conflict-detection hash.",
      ToolSchemas.object(
          Map.of("path", ToolSchemas.string("Relative path of the text file to read.")),
          List.of("path")));

  private final WorktreeLocator worktrees;
  private final WorkspaceFileService files;

  public ReadFileTool(WorktreeLocator worktrees, WorkspaceFileService files) {
    this.worktrees = worktrees;
    this.files = files;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    Path worktree = worktrees.requireActiveWorktree(context.worktreeId());
    return files.read(worktree, ToolInputs.requiredString(input, "path", 4096));
  }
}
