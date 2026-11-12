package tech.wenisch.petri.agent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.ActiveWorktree;
import tech.wenisch.petri.workspace.WorkspaceFiles;
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

  public ReadFileTool(WorktreeLocator worktrees) {
    this.worktrees = worktrees;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    ActiveWorktree active = worktrees.resolveActiveWorktree(context.worktreeId());
    Path worktree = active.root();
    WorkspaceFiles files = active.host().files();
    return files.read(worktree, ToolInputs.requiredString(input, "path", 4096));
  }
}
