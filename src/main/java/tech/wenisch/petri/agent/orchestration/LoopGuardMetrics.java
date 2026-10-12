package tech.wenisch.petri.agent.orchestration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts loop guard interventions for Prometheus ({@code petri_agent_loop_guard_interventions_total}),
 * tagged by action and workflow role. The per-run counters are stored on the run itself.
 */
@Component
class LoopGuardMetrics {
  private final MeterRegistry registry;

  LoopGuardMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  void record(boolean toolsWithdrawn, AgentWorkflowRole role) {
    registry.counter("petri.agent.loop_guard.interventions",
        "action", toolsWithdrawn ? "tools_withdrawn" : "nudge",
        "role", role == null ? "direct" : role.name().toLowerCase(java.util.Locale.ROOT)).increment();
  }
}
