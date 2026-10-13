package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.agent.orchestration.AgentChatQueueService;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.mcp.McpToolCallService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** The review tray's view of MCP tool calls that wait for a decision. */
@RestController
@RequestMapping("/workspace/api")
public class McpApprovalController {
  private final McpToolCallService calls;
  private final AgentRunService runs;
  private final AgentChatQueueService queue;
  private final AppUserRepository users;

  public McpApprovalController(
      McpToolCallService calls, AgentRunService runs, AgentChatQueueService queue,
      AppUserRepository users) {
    this.calls = calls;
    this.runs = runs;
    this.queue = queue;
    this.users = users;
  }

  @GetMapping("/chats/{chatId}/mcp-calls")
  public List<McpToolCallService.CallView> list(@PathVariable UUID chatId, Principal principal) {
    runs.chat(chatId, actor(principal).getId());
    return calls.listForChat(chatId);
  }

  @PostMapping("/chats/{chatId}/mcp-calls/{callId}/approve")
  public List<McpToolCallService.CallView> approve(
      @PathVariable UUID chatId, @PathVariable UUID callId, Principal principal) {
    AppUser actor = actor(principal);
    require(chatId, callId, actor);
    calls.approve(callId, actor.getEmail());
    queue.approvalsChanged(chatId);
    return calls.listForChat(chatId);
  }

  @PostMapping("/chats/{chatId}/mcp-calls/{callId}/reject")
  public List<McpToolCallService.CallView> reject(
      @PathVariable UUID chatId, @PathVariable UUID callId, Principal principal) {
    AppUser actor = actor(principal);
    require(chatId, callId, actor);
    calls.reject(callId, actor.getEmail());
    queue.approvalsChanged(chatId);
    return calls.listForChat(chatId);
  }

  private void require(UUID chatId, UUID callId, AppUser actor) {
    runs.chat(chatId, actor.getId());
    if (calls.listForChat(chatId).stream().noneMatch(value -> value.id().equals(callId))) {
      throw new IllegalArgumentException("MCP tool call belongs to another chat");
    }
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }
}
