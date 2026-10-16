package tech.wenisch.petri.agent.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.tool.ToolDefinition;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.mcp.McpProjectService;
import tech.wenisch.petri.mcp.McpServerService;
import tech.wenisch.petri.mcp.McpToolCallService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Connects the agent loop to the MCP servers a project activated. MCP tools are dynamic, so they
 * are not in the {@code AgentToolRegistry}: they are advertised per run and executed here, outside
 * the transaction that holds the run row, because a remote call can take seconds.
 */
@Component
class McpToolBridge {
  private final McpProjectService projects;
  private final McpServerService servers;
  private final McpToolCallService calls;
  private final ProjectWorktreeService worktrees;
  private final AgentRunStateService state;
  private final ObjectMapper json;

  McpToolBridge(
      McpProjectService projects, McpServerService servers, McpToolCallService calls,
      ProjectWorktreeService worktrees, AgentRunStateService state, ObjectMapper json) {
    this.projects = projects;
    this.servers = servers;
    this.calls = calls;
    this.worktrees = worktrees;
    this.state = state;
    this.json = json;
  }

  /** MCP tools this run may call: none for automations, read-only ones for review-only roles. */
  List<ToolDefinition> definitions(AgentExecutionState run) {
    return allowed(run).stream().map(tool -> new ToolDefinition(
        tool.exposedName(), tool.description(), tool.inputSchema(), false)).toList();
  }

  private List<McpProjectService.ActiveTool> allowed(AgentExecutionState run) {
    if (run.automationRunId() != null) return List.of();
    boolean writer = run.workflowRole() == null || run.workflowRole() == AgentWorkflowRole.IMPLEMENTER;
    UUID projectId = worktrees.get(run.worktreeId()).projectId();
    return projects.activeTools(projectId).stream()
        .filter(tool -> writer || tool.readOnly()).toList();
  }

  /** Runs or proposes one MCP tool call and returns the tool result JSON that was recorded. */
  String execute(AgentExecutionState run, UUID claimId, AgentPendingToolCall call) {
    McpProjectService.ActiveTool tool = allowed(run).stream()
        .filter(value -> value.exposedName().equals(call.name())).findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "Unknown tool " + call.name() + ": it is not enabled for this project"));
    Map<String, Object> arguments;
    try {
      arguments = json.readValue(call.argumentsJson(), new TypeReference<Map<String, Object>>() {});
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Tool arguments are not valid JSON", exception);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    if (tool.readOnly() || tool.autoApprove()) {
      var outcome = servers.callServer(tool.serverId(), tool.actingUserId(), tool.remoteName(), arguments);
      result.put("source", "mcp:" + tool.serverName());
      result.put("untrustedContent", true);
      if (outcome.error()) result.put("isError", true);
      result.put("content", outcome.text());
    } else {
      UUID id = calls.propose(run.runId(), run.worktreeId(), tool.serverId(), tool.serverName(),
          tool.remoteName(), json.writeValueAsString(arguments), tool.actingUserId());
      result.put("mcpToolCallId", id.toString());
      result.put("status", "PROPOSED");
      result.put("note", "This MCP tool call needs the user's approval before it runs. "
          + "Do not assume it ran until the result says so.");
    }
    String output = json.writeValueAsString(result);
    state.recordToolResult(run.runId(), claimId, call, output);
    return output;
  }
}
