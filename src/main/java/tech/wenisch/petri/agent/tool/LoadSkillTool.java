package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.skill.SkillService;

@Component
public class LoadSkillTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "load_skill",
      "Load one relevant SKILL.md workflow. Call list_skills first and load only when useful.",
      ToolSchemas.object(
          Map.of("name", ToolSchemas.string("Exact skill name returned by list_skills.")),
          List.of("name")));

  private final SkillService skills;

  public LoadSkillTool(SkillService skills) {
    this.skills = skills;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    return skills.load(ToolInputs.requiredString(input, "name", 64));
  }
}

