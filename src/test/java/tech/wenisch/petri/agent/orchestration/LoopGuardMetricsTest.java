package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LoopGuardMetricsTest {
  @Test
  void interventionsAreCountedByActionAndRole() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LoopGuardMetrics metrics = new LoopGuardMetrics(registry);

    metrics.record(false, AgentWorkflowRole.ORCHESTRATOR);
    metrics.record(false, AgentWorkflowRole.ORCHESTRATOR);
    metrics.record(true, AgentWorkflowRole.ORCHESTRATOR);
    metrics.record(false, null);

    String name = "petri.agent.loop_guard.interventions";
    assertThat(registry.get(name).tags("action", "nudge", "role", "orchestrator").counter().count())
        .isEqualTo(2);
    assertThat(registry.get(name).tags("action", "tools_withdrawn", "role", "orchestrator")
        .counter().count()).isEqualTo(1);
    assertThat(registry.get(name).tags("action", "nudge", "role", "direct").counter().count())
        .isEqualTo(1);
  }
}
