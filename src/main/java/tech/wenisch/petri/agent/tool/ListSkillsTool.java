package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.skill.SkillService;

@Component
public class ListSkillsTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "list_skills",
      "List available reusable workflows without loading their full instructions.",
      ToolSchemas.object(Map.of(), List.of()));

  private final SkillService skills;

  public ListSkillsTool(SkillService skills) {
    this.skills = skills;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    if (!input.isEmpty()) throw new IllegalArgumentException("list_skills does not accept arguments");
    return skills.list();
  }
}

