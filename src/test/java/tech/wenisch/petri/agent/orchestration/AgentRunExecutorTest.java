package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelClientException;
import tech.wenisch.petri.agent.model.ModelRequest;

class AgentRunExecutorTest {

  @Test
  void retriesUnsupportedNativeJsonOnlyBeforeAnyOutputWasReceived() {
    ModelRequest structured = new ModelRequest(
        "local-model", "Return JSON.", "Plan", null, List.of(), List.of(),
        512, null, null, List.of(), true);

    assertThat(AgentRunExecutor.canRetryWithoutNativeJson(
        structured, new StringBuilder(), new ModelClientException("request failed with HTTP 400")))
        .isTrue();
    assertThat(AgentRunExecutor.canRetryWithoutNativeJson(
        structured, new StringBuilder("{"), new ModelClientException("request failed with HTTP 400")))
        .isFalse();
    assertThat(AgentRunExecutor.canRetryWithoutNativeJson(
        structured, new StringBuilder(), new ModelClientException("request failed with HTTP 500")))
        .isFalse();
    assertThat(AgentRunExecutor.canRetryWithoutNativeJson(
        structured.withJsonObjectOutput(false), new StringBuilder(),
        new ModelClientException("request failed with HTTP 400")))
        .isFalse();
  }
}
