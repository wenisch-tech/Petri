package tech.wenisch.petri.agent.tool;

import java.util.Map;

public interface AgentTool {
  ToolDefinition definition();
  Object execute(ToolExecutionContext context, Map<String, Object> input);
}
