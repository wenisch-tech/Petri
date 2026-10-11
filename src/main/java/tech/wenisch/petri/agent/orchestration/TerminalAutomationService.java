package tech.wenisch.petri.agent.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tech.wenisch.petri.agent.run.TerminalCommandStatus;
import tech.wenisch.petri.agent.run.TerminalCommandView;
import tech.wenisch.petri.agent.run.TerminalService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/** Approves and runs agent terminal commands only when the project explicitly auto-approves them. */
@Service
class TerminalAutomationService {
  private static final Logger log = LoggerFactory.getLogger(TerminalAutomationService.class);

  private final AgentRunService runs;
  private final TerminalService terminal;
  private final AgentChatQueueService queue;
  private final AppUserRepository users;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  TerminalAutomationService(AgentRunService runs, TerminalService terminal,
      AgentChatQueueService queue, AppUserRepository users) {
    this.runs = runs;
    this.terminal = terminal;
    this.queue = queue;
    this.users = users;
  }

  // REQUIRES_NEW: an after-commit listener otherwise joins the finished transaction.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void requested(AgentActionRequestedEvent event) {
    if (event.kind() != AgentActionRequestedEvent.AgentActionKind.TERMINAL_COMMAND) return;
    AgentRunView run = runs.get(event.agentRunId());
    TerminalCommandView command = terminal.get(event.actionId());
    if (command.status() != TerminalCommandStatus.PROPOSED || !terminal.autoApprove(command.worktreeId())) {
      return;
    }
    AppUser creator = users.findById(run.createdBy()).orElse(null);
    if (creator == null) return;
    terminal.approve(command.id(), creator.getEmail());
    UUID chatId = run.chatId();
    executor.submit(() -> {
      try {
        terminal.execute(command.id());
      } catch (RuntimeException exception) {
        log.warn("Auto-approved terminal command {} failed", command.id(), exception);
      } finally {
        if (chatId != null) queue.approvalsChanged(chatId);
      }
    });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
