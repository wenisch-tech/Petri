package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelProvider;

class AgentRunTest {
  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

  @Test
  void preventsConcurrentExecutionUntilLeaseExpires() {
    AgentRun run = run();
    UUID firstClaim = UUID.randomUUID();
    UUID secondClaim = UUID.randomUUID();

    run.claimExecution(firstClaim, NOW, Duration.ofMinutes(3));

    assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
    assertThatIllegalStateException().isThrownBy(() ->
        run.claimExecution(secondClaim, NOW.plusSeconds(179), Duration.ofMinutes(3)));

    run.claimExecution(secondClaim, NOW.plusSeconds(181), Duration.ofMinutes(3));
    run.requireExecutionClaim(secondClaim);
    assertThatIllegalStateException().isThrownBy(() -> run.requireExecutionClaim(firstClaim));
  }

  @Test
  void pendingCancellationIsImmediatelyTerminal() {
    AgentRun run = run();

    run.requestCancellation(NOW);

    assertThat(run.status()).isEqualTo(AgentRunStatus.CANCELLED);
    assertThat(run.terminal()).isTrue();
    assertThat(run.completedAt()).isEqualTo(NOW);
  }

  private static AgentRun run() {
    return new AgentRun(
        UUID.randomUUID(), UUID.randomUUID(), ModelProvider.OPENAI, "model", "prompt", 4,
        4096, null, null, UUID.randomUUID(), NOW.minusSeconds(10));
  }
}
