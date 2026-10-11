package tech.wenisch.petri.agent.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalStatus;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** Applies the explicitly enabled per-chat automation only after proposals are committed. */
@Service
public class AgentChatAutomationService {
  private final AgentRunService runs;
  private final ChangeProposalService changes;
  private final TestRunService tests;
  private final AgentChatQueueService queue;
  private final AppUserRepository users;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  AgentChatAutomationService(
      AgentRunService runs,
      ChangeProposalService changes,
      TestRunService tests,
      AgentChatQueueService queue,
      AppUserRepository users) {
    this.runs = runs;
    this.changes = changes;
    this.tests = tests;
    this.queue = queue;
    this.users = users;
  }

  // REQUIRES_NEW: an after-commit listener otherwise joins the finished transaction and loses its writes.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void requested(AgentActionRequestedEvent event) {
    AgentRunView run = runs.get(event.agentRunId());
    AgentChatView chat = runs.chat(run.chatId(), run.createdBy());
    AppUser creator = users.findById(chat.createdBy()).orElse(null);
    if (creator == null) return;
    if (event.kind() == AgentActionRequestedEvent.AgentActionKind.CHANGE_PROPOSAL
        && chat.autoApproveChanges()) {
      changes.approveAndApply(event.actionId(), creator.getEmail());
      queue.approvalsChanged(chat.id());
    }
    if (event.kind() == AgentActionRequestedEvent.AgentActionKind.TEST_RUN && chat.autoApproveTests()) {
      approveAndExecuteTest(event.actionId(), run.worktreeId(), creator.getEmail(), chat.id());
    }
  }

  /** Processes actions already waiting when a user enables one of the two switches. */
  public void processExisting(UUID chatId, UUID actorId) {
    AgentChatView chat = runs.chat(chatId, actorId);
    AppUser creator = users.findById(chat.createdBy())
        .orElseThrow(() -> new IllegalStateException("Chat creator no longer exists"));
    for (AgentRunView run : runs.chatRuns(chatId)) {
      if (chat.autoApproveChanges()) {
        for (var proposal : changes.listForAgentRun(run.id())) {
          if (proposal.status() == ChangeProposalStatus.PROPOSED
              || proposal.status() == ChangeProposalStatus.APPROVED) {
            changes.approveAndApply(proposal.id(), creator.getEmail());
          }
        }
      }
      if (chat.autoApproveTests()) {
        for (var test : tests.listForAgentRun(run.id())) {
          if (test.status() == TestRunStatus.PROPOSED || test.status() == TestRunStatus.APPROVED) {
            approveAndExecuteTest(test.id(), test.worktreeId(), creator.getEmail(), chatId);
          }
        }
      }
    }
    queue.approvalsChanged(chatId);
  }

  private void approveAndExecuteTest(UUID testId, UUID worktreeId, String actorEmail, UUID chatId) {
    if (tests.get(testId, worktreeId).status() == TestRunStatus.PROPOSED) {
      tests.approve(testId, actorEmail);
    }
    executor.submit(() -> {
      try {
        tests.execute(testId);
      } finally {
        queue.approvalsChanged(chatId);
      }
    });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
