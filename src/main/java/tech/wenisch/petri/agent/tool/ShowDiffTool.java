package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.change.ChangeProposalService;

@Component
public class ShowDiffTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "show_diff",
      "Return the persisted diff and lifecycle status for a change proposal in this worktree.",
      ToolSchemas.object(
          Map.of("proposalId", ToolSchemas.string("UUID of the change proposal.")),
          List.of("proposalId")));

  private final ChangeProposalService proposals;

  public ShowDiffTool(ChangeProposalService proposals) {
    this.proposals = proposals;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    String proposalId = ToolInputs.requiredString(input, "proposalId", 36);
    UUID parsed;
    try {
      parsed = UUID.fromString(proposalId);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("proposalId must be a valid proposal UUID", exception);
    }
    return proposals.get(parsed, context.worktreeId());
  }
}
