package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentWorkflowStateTest {
  @Test
  void approvalIsBoundToTheCurrentPlanRevision() {
    AgentWorkflow workflow = workflow();
    workflow.currentPlan(2, Instant.now());

    assertThatThrownBy(() -> workflow.approvePlan(1, Instant.now()))
        .isInstanceOf(IllegalStateException.class);
    workflow.approvePlan(2, Instant.now());
    assertThat(workflow.approvedPlanRevision()).isEqualTo(2);

    workflow.currentPlan(3, Instant.now());
    assertThat(workflow.approvedPlanRevision()).isNull();
  }

  @Test
  void terminalWorkflowCannotTransitionAgain() {
    AgentWorkflow workflow = workflow();
    workflow.transition(AgentWorkflowStage.COMPLETED, AgentWorkflowStatus.COMPLETED,
        "done", Instant.now());

    assertThatThrownBy(() -> workflow.transition(AgentWorkflowStage.PLANNING,
        AgentWorkflowStatus.RUNNING, "invalid", Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AgentWorkflow workflow() {
    return new AgentWorkflow(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(),
        "Implement a feature", UUID.randomUUID(), Instant.now());
  }
}
