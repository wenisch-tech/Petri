package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowService;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowStage;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowStatus;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowView;

class FixWorkflowDriverTest {
  private final AgentWorkflowService workflows = mock(AgentWorkflowService.class);
  private final FixWorkflowDriver driver = new FixWorkflowDriver(workflows);
  private final UUID id = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();

  private void at(AgentWorkflowStage stage, AgentWorkflowStatus status, Integer revision) {
    AgentWorkflowView view = mock(AgentWorkflowView.class);
    when(view.id()).thenReturn(id);
    when(view.currentStage()).thenReturn(stage);
    when(view.status()).thenReturn(status);
    when(view.currentPlanRevision()).thenReturn(revision);
    when(workflows.get(id, owner)).thenReturn(view);
  }

  private FixWorkflowDriver.Step advance() {
    return driver.advance(id, owner, Instant.now(), Duration.ofHours(3));
  }

  @Test
  void completedWorkflowIsDone() {
    at(AgentWorkflowStage.COMPLETED, AgentWorkflowStatus.COMPLETED, null);
    assertThat(advance().state()).isEqualTo(FixWorkflowDriver.State.COMPLETED);
  }

  @Test
  void planGateIsApprovedAndFinalGateIsApproved() {
    at(AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL, AgentWorkflowStatus.WAITING_FOR_USER, 2);
    assertThat(advance().state()).isEqualTo(FixWorkflowDriver.State.WORKING);
    verify(workflows).approvePlan(id, 2, owner, "automation");

    at(AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW, AgentWorkflowStatus.WAITING_FOR_USER, null);
    advance();
    verify(workflows).approveFinal(id, owner, "automation");
  }

  @Test
  void holdAndClarificationNeedAPerson() {
    at(AgentWorkflowStage.WAITING_FOR_CLARIFICATION, AgentWorkflowStatus.WAITING_FOR_USER, null);
    assertThat(advance().state()).isEqualTo(FixWorkflowDriver.State.STOPPED);
    verify(workflows, never()).approvePlan(any(), anyInt(), any(), any());
  }

  @Test
  void anOldWorkflowIsCancelled() {
    FixWorkflowDriver.Step step =
        driver.advance(id, owner, Instant.now().minus(Duration.ofHours(4)), Duration.ofHours(3));
    assertThat(step.state()).isEqualTo(FixWorkflowDriver.State.STOPPED);
    verify(workflows).cancel(id, owner, "automation");
  }
}
