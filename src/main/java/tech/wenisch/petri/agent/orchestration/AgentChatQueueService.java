package tech.wenisch.petri.agent.orchestration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.config.PetriProperties;

@Service
public class AgentChatQueueService {
  private final AgentChatRepository chats;
  private final AgentRunRepository runs;
  private final ChangeProposalService changes;
  private final TestRunService testRuns;
  private final AgentRunLauncher launcher;
  private final Clock clock;
  private final Duration executionLease;

  @Autowired
  AgentChatQueueService(
      AgentChatRepository chats,
      AgentRunRepository runs,
      ChangeProposalService changes,
      TestRunService testRuns,
      AgentRunLauncher launcher,
      PetriProperties properties) {
    this(chats, runs, changes, testRuns, launcher,
        properties.agent().modelRequestTimeout().plusMinutes(1), Clock.systemUTC());
  }

  AgentChatQueueService(
      AgentChatRepository chats,
      AgentRunRepository runs,
      ChangeProposalService changes,
      TestRunService testRuns,
      AgentRunLauncher launcher,
      Duration executionLease,
      Clock clock) {
    this.chats = chats;
    this.runs = runs;
    this.changes = changes;
    this.testRuns = testRuns;
    this.launcher = launcher;
    this.executionLease = executionLease;
    this.clock = clock;
  }

  @EventListener
  void terminal(AgentRunTerminalEvent event) {
    advance(event.chatId(), false, false);
  }

  public void resume(UUID chatId, boolean overrideApprovalGate) {
    advance(chatId, true, overrideApprovalGate);
  }

  public void approvalsChanged(UUID chatId) {
    advance(chatId, false, false);
  }

  /** Restarts queued work after a process restart and reclaims only expired execution leases. */
  @Scheduled(fixedDelayString = "${petri.agent.queue-recovery-delay-ms:30000}")
  public void recover() {
    runs.findByStatusOrderByCreatedAtAsc(AgentRunStatus.PENDING).stream()
        .map(AgentRun::chatId).distinct().forEach(chatId -> advance(chatId, false, false));
    Instant now = Instant.now(clock);
    for (AgentRun run : runs.findByStatusOrderByCreatedAtAsc(AgentRunStatus.RUNNING)) {
      if (run.executionLeaseExpired(now, executionLease)) {
        launcher.launch(run.id(), run.worktreeId(), run.createdBy(), "chat-recovery:" + run.chatId());
      }
    }
    for (AgentRun run : runs.findByStatusOrderByCreatedAtAsc(AgentRunStatus.CANCELLATION_REQUESTED)) {
      if (run.executionLeaseExpired(now, executionLease)) {
        launcher.launch(run.id(), run.worktreeId(), run.createdBy(), "chat-recovery:" + run.chatId());
      }
    }
  }

  @Transactional
  void advance(UUID chatId, boolean userRequested, boolean explicitOverride) {
    AgentChat chat = chats.findForUpdateById(chatId).orElse(null);
    if (chat == null) return;
    List<AgentRun> history = runs.findByChatIdOrderByChatMessageIndexAsc(chatId);
    if (history.stream().anyMatch(run -> active(run.status()))) return;
    if (chat.queuePaused() && !userRequested) return;
    AgentRun latestExecuted = history.stream().filter(run -> run.startedAt() != null)
        .reduce((first, second) -> second).orElse(null);
    if (!userRequested && latestExecuted != null && latestExecuted.status() != AgentRunStatus.COMPLETED) {
      chat.pauseQueue(Instant.now(clock));
      return;
    }
    AgentRun previous = history.stream()
        .filter(run -> run.startedAt() != null)
        .reduce((first, second) -> second)
        .orElse(null);
    boolean blocked = previous != null
        && (changes.hasOpenForAgentRun(previous.id()) || testRuns.hasOpenForAgentRun(previous.id()));
    boolean override = explicitOverride || chat.approvalGateOverridden();
    if (blocked && !override) {
      chat.pauseQueue(Instant.now(clock));
      return;
    }
    AgentRun next = history.stream()
        .filter(run -> run.status() == AgentRunStatus.PENDING)
        .findFirst()
        .orElse(null);
    if (next == null) return;
    chat.resumeQueue(false, Instant.now(clock));
    if (override) chat.consumeApprovalOverride(Instant.now(clock));
    launcher.launch(next.id(), next.worktreeId(), next.createdBy(), "chat-queue:" + chatId);
  }

  private static boolean active(AgentRunStatus status) {
    return status == AgentRunStatus.RUNNING || status == AgentRunStatus.CANCELLATION_REQUESTED;
  }
}
