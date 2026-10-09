package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelConversationMessage;
import tech.wenisch.petri.agent.model.ModelMessageRole;

class AgentRunStateServiceTest {
  @Test
  void rebuildsChatConversationIncludingAssistantToolCallsAndResults() {
    UUID runId = UUID.randomUUID();
    Instant now = Instant.parse("2026-10-09T10:00:00Z");
    List<AgentTurn> history = List.of(
        turn(runId, 0, AgentTurnKind.USER_PROMPT, null, null, null, "Inspect it", now),
        turn(runId, 1, AgentTurnKind.MODEL_RESPONSE, "response-1", null, null, "", now),
        turn(
            runId,
            2,
            AgentTurnKind.TOOL_CALL,
            "response-1",
            "call-1",
            "read_file",
            "{\"path\":\"README.md\"}",
            now),
        turn(
            runId,
            3,
            AgentTurnKind.TOOL_RESULT,
            "response-1",
            "call-1",
            "read_file",
            "{\"content\":\"Petri\"}",
            now));

    List<ModelConversationMessage> conversation = AgentRunStateService.conversation(history);

    assertThat(conversation).hasSize(3);
    assertThat(conversation.get(0).role()).isEqualTo(ModelMessageRole.USER);
    assertThat(conversation.get(1).role()).isEqualTo(ModelMessageRole.ASSISTANT);
    assertThat(conversation.get(1).toolCalls()).singleElement().satisfies(call -> {
      assertThat(call.callId()).isEqualTo("call-1");
      assertThat(call.name()).isEqualTo("read_file");
    });
    assertThat(conversation.get(2).role()).isEqualTo(ModelMessageRole.TOOL);
    assertThat(conversation.get(2).toolCallId()).isEqualTo("call-1");
  }

  private static AgentTurn turn(
      UUID runId,
      int index,
      AgentTurnKind kind,
      String responseId,
      String callId,
      String toolName,
      String content,
      Instant createdAt) {
    return new AgentTurn(
        UUID.randomUUID(), runId, index, kind, responseId, callId, toolName, content, createdAt);
  }
}
