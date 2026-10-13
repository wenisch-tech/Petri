package tech.wenisch.petri.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** MCP tool calls that need approval: proposed by the agent, decided by a person, then executed. */
@Service
public class McpToolCallService {
  private static final Logger log = LoggerFactory.getLogger(McpToolCallService.class);

  /** What the review tray and the approval gate know about a call. */
  public record CallView(
      UUID id, UUID agentRunId, String serverName, String toolName, String arguments,
      McpToolCallStatus status, String output, String error, Instant createdAt) {}

  private final McpToolCallRepository calls;
  private final McpServerService servers;
  private final AuditLogService audit;
  private final ObjectMapper json;
  private final Clock clock = Clock.systemUTC();

  public McpToolCallService(
      McpToolCallRepository calls, McpServerService servers, AuditLogService audit, ObjectMapper json) {
    this.calls = calls;
    this.servers = servers;
    this.audit = audit;
    this.json = json;
  }

  @Transactional
  public UUID propose(
      UUID agentRunId, UUID worktreeId, UUID serverId, String serverName, String toolName,
      String argumentsJson) {
    McpToolCall call = new McpToolCall(
        UUID.randomUUID(), agentRunId, worktreeId, serverId, serverName, toolName, argumentsJson,
        Instant.now(clock));
    calls.save(call);
    return call.id();
  }

  @Transactional(readOnly = true)
  public CallView get(UUID id) {
    return calls.findById(id).map(McpToolCallService::view)
        .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool call"));
  }

  @Transactional(readOnly = true)
  public List<CallView> listForChat(UUID chatId) {
    return calls.findByChat(chatId).stream().map(McpToolCallService::view).toList();
  }

  /** Approves a proposed call and runs it in the background. */
  public void approve(UUID id, String actorEmail) {
    if (decide(id, McpToolCallStatus.APPROVED, actorEmail)) {
      Thread.ofVirtual().name("mcp-call-" + id).start(() -> execute(id));
    }
  }

  public void reject(UUID id, String actorEmail) {
    decide(id, McpToolCallStatus.REJECTED, actorEmail);
  }

  @Transactional
  boolean decide(UUID id, McpToolCallStatus next, String actorEmail) {
    McpToolCall call = calls.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool call"));
    if (call.status() != McpToolCallStatus.PROPOSED) return false;
    call.decide(next, Instant.now(clock));
    calls.save(call);
    audit.record(actorEmail, next == McpToolCallStatus.APPROVED ? "MCP_TOOL_CALL_APPROVED" : "MCP_TOOL_CALL_REJECTED",
        "MCP_TOOL_CALL", id, (next == McpToolCallStatus.APPROVED ? "Approved " : "Declined ")
            + call.serverName() + " · " + call.toolName());
    return true;
  }

  private void execute(UUID id) {
    McpToolCall call;
    try {
      call = markRunning(id);
      Map<String, Object> arguments = json.readValue(
          call.argumentsJson(), new TypeReference<Map<String, Object>>() {});
      McpClient.CallResult result = servers.callServer(call.serverId(), call.toolName(), arguments);
      finish(id, result.error() ? McpToolCallStatus.FAILED : McpToolCallStatus.COMPLETED,
          result.text(), result.error() ? "The tool reported an error" : null);
    } catch (RuntimeException exception) {
      log.warn("MCP tool call {} failed: {}", id, exception.getMessage());
      finish(id, McpToolCallStatus.FAILED, null, exception.getMessage());
    }
  }

  @Transactional
  McpToolCall markRunning(UUID id) {
    McpToolCall call = calls.findById(id).orElseThrow();
    call.decide(McpToolCallStatus.RUNNING, Instant.now(clock));
    return calls.save(call);
  }

  @Transactional
  void finish(UUID id, McpToolCallStatus status, String result, String error) {
    calls.findById(id).ifPresent(call -> {
      call.finish(status, result, error, Instant.now(clock));
      calls.save(call);
    });
  }

  private static CallView view(McpToolCall call) {
    return new CallView(call.id(), call.agentRunId(), call.serverName(), call.toolName(),
        call.argumentsJson(), call.status(), call.resultText(), call.error(), call.createdAt());
  }
}
