package tech.wenisch.petri.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowService;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowStage;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowStatus;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowView;

/**
 * Drives a fix workflow that nobody is watching through its gates: approves the plan and the final
 * review, and says when it is done or needs a person. One step per call; the caller decides what
 * to do with the outcome. This is the part the pull request improvement and the vulnerability fix
 * pipelines used to repeat.
 */
@Component
class FixWorkflowDriver {
  enum State {
    /** Still working, or waiting for the next call. */
    WORKING,
    /** Finished successfully; the caller commits and pushes. */
    COMPLETED,
    /** Cannot continue without a person (or was cancelled, or ran too long). */
    STOPPED
  }

  /** @param reason why the workflow stopped; null unless {@code state} is STOPPED */
  record Step(State state, String reason) {
    static final Step WORKING = new Step(State.WORKING, null);
    static final Step COMPLETED = new Step(State.COMPLETED, null);

    static Step stopped(String reason) {
      return new Step(State.STOPPED, reason);
    }
  }

  private static final String ACTOR = "automation";
  private final AgentWorkflowService workflows;

  FixWorkflowDriver(AgentWorkflowService workflows) {
    this.workflows = workflows;
  }

  /**
   * @param startedOrUpdated when the run last made progress; a workflow older than {@code limit}
   *     is cancelled
   */
  Step advance(UUID workflowId, UUID ownerId, Instant startedOrUpdated, Duration limit) {
    if (workflowId == null) return Step.stopped("The fix has no workflow.");
    if (startedOrUpdated.isBefore(Instant.now().minus(limit))) {
      workflows.cancel(workflowId, ownerId, ACTOR);
      return Step.stopped("The fix workflow took longer than " + limit.toHours()
          + " hours and was stopped.");
    }
    AgentWorkflowView view = workflows.get(workflowId, ownerId);
    AgentWorkflowStage stage = view.currentStage();
    if (stage == AgentWorkflowStage.COMPLETED) return Step.COMPLETED;
    if (stage == AgentWorkflowStage.CANCELLED) return Step.stopped("The fix workflow was cancelled.");
    if (stage == AgentWorkflowStage.HUMAN_HOLD || view.status() == AgentWorkflowStatus.HUMAN_HOLD) {
      return Step.stopped("The fix workflow needs a person: "
          + (view.holdReason() == null ? "it is on hold." : view.holdReason()));
    }
    if (stage == AgentWorkflowStage.WAITING_FOR_CLARIFICATION) {
      return Step.stopped("The planner has questions that only a person can answer.");
    }
    if (stage == AgentWorkflowStage.WAITING_FOR_PLAN_APPROVAL && view.currentPlanRevision() != null) {
      workflows.approvePlan(view.id(), view.currentPlanRevision(), ownerId, ACTOR);
    } else if (stage == AgentWorkflowStage.ORCHESTRATOR_FINAL_REVIEW
        && view.status() == AgentWorkflowStatus.WAITING_FOR_USER) {
      workflows.approveFinal(view.id(), ownerId, ACTOR);
    }
    return Step.WORKING;
  }
}
