package tech.wenisch.petri.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tool call that waits for a person's approval before it reaches an MCP server. */
@Entity
@Table(name = "mcp_tool_call")
class McpToolCall {
  @Id private UUID id;

  @Column(name = "agent_run_id", nullable = false)
  private UUID agentRunId;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Column(name = "server_id", nullable = false)
  private UUID serverId;

  @Column(name = "server_name", nullable = false, length = 80)
  private String serverName;

  @Column(name = "tool_name", nullable = false, length = 200)
  private String toolName;

  @Column(name = "arguments_json", nullable = false, columnDefinition = "TEXT")
  private String argumentsJson;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private McpToolCallStatus status;

  @Column(name = "result_text", columnDefinition = "TEXT")
  private String resultText;

  @Column(length = 1000)
  private String error;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected McpToolCall() {}

  McpToolCall(
      UUID id, UUID agentRunId, UUID worktreeId, UUID serverId, String serverName, String toolName,
      String argumentsJson, Instant now) {
    this.id = id;
    this.agentRunId = agentRunId;
    this.worktreeId = worktreeId;
    this.serverId = serverId;
    this.serverName = serverName;
    this.toolName = toolName;
    this.argumentsJson = argumentsJson;
    this.status = McpToolCallStatus.PROPOSED;
    this.createdAt = now;
  }

  void decide(McpToolCallStatus next, Instant now) {
    this.status = next;
    this.decidedAt = now;
  }

  void finish(McpToolCallStatus next, String result, String failure, Instant now) {
    this.status = next;
    this.resultText = result;
    this.error = failure == null ? null : (failure.length() > 1000 ? failure.substring(0, 1000) : failure);
    this.completedAt = now;
  }

  UUID id() { return id; }
  UUID agentRunId() { return agentRunId; }
  UUID worktreeId() { return worktreeId; }
  UUID serverId() { return serverId; }
  String serverName() { return serverName; }
  String toolName() { return toolName; }
  String argumentsJson() { return argumentsJson; }
  McpToolCallStatus status() { return status; }
  String resultText() { return resultText; }
  String error() { return error; }
  Instant createdAt() { return createdAt; }
}
