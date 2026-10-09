package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.change.ChangeProposalService;

@Component
public class CreateFileTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "create_file",
      "Create a reviewable proposal for a new file. The file must not exist yet; "
          + "use create_patch to replace an existing file. This never modifies the worktree.",
      ToolSchemas.object(
          Map.of(
              "path", ToolSchemas.string("Relative path of the new UTF-8 file."),
              "content", ToolSchemas.string("Complete UTF-8 content of the new file.")),
          List.of("path", "content")));

  private final ChangeProposalService proposals;

  public CreateFileTool(ChangeProposalService proposals) {
    this.proposals = proposals;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    String content = ToolInputs.string(input, "content", null, 1_100_000);
    if (content == null) throw new IllegalArgumentException("content is required");
    return proposals.createFile(
        context.worktreeId(),
        context.agentRunId(),
        ToolInputs.requiredString(input, "path", 2048),
        content);
  }
}