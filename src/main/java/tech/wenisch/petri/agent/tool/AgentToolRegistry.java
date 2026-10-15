package tech.wenisch.petri.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  public List<ToolDefinition> definitions(Set<String> allowed) {
    if (allowed == null) return definitions();
    return tools.entrySet().stream().filter(entry -> allowed.contains(entry.getKey()))
        .map(entry -> entry.getValue().definition())
        .sorted(java.util.Comparator.comparing(ToolDefinition::name)).toList();
  }

  public ToolExecutionResult execute(
      String toolName, ToolExecutionContext context, Map<String, Object> input) {
    AgentTool tool = tools.get(toolName);
    if (tool == null) throw new IllegalArgumentException("Unknown agent tool: " + toolName);
    if (context.automationRunId() != null) {
      java.util.Set<String> granted = context.automationTools() == null
          ? AutomationToolPolicy.allowed() : context.automationTools();
      if (!AutomationToolPolicy.allowed().contains(toolName) || !granted.contains(toolName)) {
        throw new IllegalArgumentException("Tool is not permitted for automation runs");
      }
    } else if (AutomationToolPolicy.isAutomationOnly(toolName)) {
      throw new IllegalArgumentException("Tool is only available to automation runs");
    } else if (!AgentWorkflowToolPolicy.allowed(context.workflowRole()).contains(toolName)) {
      throw new IllegalArgumentException("Tool is not permitted for role " + context.workflowRole());
    }
    // Models send explicit nulls for optional parameters; Map.copyOf would reject them.
    Map<String, Object> safeInput = input == null ? Map.of()
        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
    return new ToolExecutionResult(toolName, tool.execute(context, safeInput));
  }
}
