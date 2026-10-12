package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoopGuardTest {
  private static final String ASSESSMENT = "I've verified the repository state locally. Verified facts: "
      + "pom.xml uses spring-boot-starter-parent 4.0.8 with java.version 25 and exactly the two minimal "
      + "dependencies, so AC1 is met. SampleApplication is the only SpringBootApplication class, so AC2 "
      + "is met. The latest mvn test run passed with two tests and no failures, so AC4 is met.";

  private final UUID runId = UUID.randomUUID();
  private final List<AgentTurn> history = new ArrayList<>();
  private int index;

  private void turn(AgentTurnKind kind, String responseId, String callId, String tool, String content) {
    history.add(new AgentTurn(UUID.randomUUID(), runId, index++, kind, responseId, callId, tool,
        content, Instant.now()));
  }

  private void response(String id, String text, String... calls) {
    turn(AgentTurnKind.MODEL_RESPONSE, id, null, null, text);
    for (int call = 0; call < calls.length; call += 2) {
      turn(AgentTurnKind.TOOL_CALL, id, id + "-" + call, calls[call], calls[call + 1]);
      turn(AgentTurnKind.TOOL_RESULT, id, id + "-" + call, calls[call], "{}");
    }
  }

  @Test
  void aModelThatMakesProgressIsNeverFlagged() {
    response("r1", "Let me look.", "list_files", "{\"path\":\"\"}");
    response("r2", "Reading the pom.", "read_file", "{\"path\":\"pom.xml\"}");
    response("r3", "Now the controller.", "read_file", "{\"path\":\"Greeting.java\"}");
    response("r4", "Done: {\"decision\":\"PASS\"}");

    assertThat(LoopGuard.strikes(history)).isZero();
  }

  @Test
  void theSameToolCallsAgainAreARepetitionAndThreeInARowWithdrawTools() {
    response("r1", "Checking the diff.", "show_diff", "{\"proposalId\":\"x\"}");
    assertThat(LoopGuard.strikes(history)).isZero();
    response("r2", "Checking the diff again.", "show_diff", "{ \"proposalId\": \"x\" }");
    assertThat(LoopGuard.strikes(history)).isEqualTo(1);
    response("r3", "One more look.", "show_diff", "{\"proposalId\":\"x\"}");
    assertThat(LoopGuard.strikes(history)).isEqualTo(LoopGuard.WITHDRAW_TOOLS_AT);
  }

  @Test
  void nearlyIdenticalLongTextIsARepetitionEvenWithDifferentToolCalls() {
    response("r1", ASSESSMENT, "list_files", "{\"path\":\"\"}");
    response("r2", ASSESSMENT + " Final check complete.", "read_file", "{\"path\":\"pom.xml\"}");

    assertThat(LoopGuard.strikes(history)).isEqualTo(1);
  }

  @Test
  void shortReplyTextAndAChangedCallDoNotCount() {
    response("r1", "Reading.", "read_file", "{\"path\":\"a\"}");
    response("r2", "Reading.", "read_file", "{\"path\":\"b\"}");

    assertThat(LoopGuard.strikes(history)).isZero();
  }

  @Test
  void aNewStepResetsTheCount() {
    response("r1", "Look.", "show_diff", "{\"proposalId\":\"x\"}");
    response("r2", "Look again.", "show_diff", "{\"proposalId\":\"x\"}");
    response("r3", "Different now.", "read_file", "{\"path\":\"pom.xml\"}");

    assertThat(LoopGuard.strikes(history)).isZero();
  }

  @Test
  void guidanceEscalatesFromANudgeToWithdrawingTools() {
    assertThat(LoopGuard.guidance(0)).isEmpty();
    assertThat(LoopGuard.guidance(1)).contains("Read your last message", "Do not repeat it")
        .doesNotContain("no longer available");
    assertThat(LoopGuard.guidance(2)).contains("Tools are no longer available", "final response");
  }
}
