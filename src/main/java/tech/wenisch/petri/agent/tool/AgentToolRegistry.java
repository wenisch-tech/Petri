package tech.wenisch.petri.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentToolRegistry {
  private final Map<String, AgentTool> tools;

  public AgentToolRegistry(List<AgentTool> tools) {
    Map<String, AgentTool> registered = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      AgentTool previous = registered.put(tool.definition().name(), tool);
      if (previous != null) {
        throw new IllegalStateException("Duplicate agent tool: " + tool.definition().name());
      }
    }
    this.tools = Map.copyOf(registered);
  }

  public List<ToolDefinition> definitions() {
    return tools.values().stream().map(AgentTool::definition)
        .sorted(java.util.Comparator.comparing(ToolDefinition::name))
        .toList();
  }

  public ToolExecutionResult execute(
      String toolName, ToolExecutionContext context, Map<String, Object> input) {
    AgentTool tool = tools.get(toolName);
    if (tool == null) throw new IllegalArgumentException("Unknown agent tool: " + toolName);
    Map<String, Object> safeInput = input == null ? Map.of() : Map.copyOf(input);
    return new ToolExecutionResult(toolName, tool.execute(context, safeInput));
  }
}
