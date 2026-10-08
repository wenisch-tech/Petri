package tech.wenisch.petri.agent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Component
public class SearchFilesTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "search_files",
      "Search UTF-8 workspace files for a literal string and return bounded line matches.",
      ToolSchemas.object(
          Map.of(
              "query", ToolSchemas.string("Case-sensitive literal text to find."),
              "path", ToolSchemas.string("Relative file or directory path; defaults to root."),
              "maxResults", ToolSchemas.integer("Maximum number of matches requested.", 1, 1000)),
          List.of("query")));

  private final WorktreeLocator worktrees;
  private final WorkspaceFileService files;

  public SearchFilesTool(WorktreeLocator worktrees, WorkspaceFileService files) {
    this.worktrees = worktrees;
    this.files = files;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    Path worktree = worktrees.requireActiveWorktree(context.worktreeId());
    String path = ToolInputs.string(input, "path", ".", 4096);
    String query = ToolInputs.requiredString(input, "query", 500);
    int maxResults = ToolInputs.integer(input, "maxResults", 100, 1, 1000);
    return files.search(worktree, path, query, maxResults);
  }
}
