package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.change.ChangeProposalService;

@Component
public class CreatePatchTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "create_patch",
      "Create a reviewable full-file replacement proposal. This never modifies the worktree.",
      ToolSchemas.object(
          Map.of(
              "path", ToolSchemas.string("Relative path of an existing UTF-8 file."),
              "expectedSha256", ToolSchemas.string("SHA-256 returned by read_file."),
              "replacementContent", ToolSchemas.string("Complete proposed UTF-8 file content.")),
          List.of("path", "expectedSha256", "replacementContent")));

  private final ChangeProposalService proposals;

  public CreatePatchTool(ChangeProposalService proposals) {
    this.proposals = proposals;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    String replacement = ToolInputs.string(input, "replacementContent", null, 1_100_000);
    if (replacement == null) throw new IllegalArgumentException("replacementContent is required");
    return proposals.create(
        context.worktreeId(),
        ToolInputs.requiredString(input, "path", 2048),
        ToolInputs.requiredString(input, "expectedSha256", 64),
        replacement);
  }
}
