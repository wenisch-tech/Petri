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
import tech.wenisch.petri.agent.run.PackageInstallService;
import tech.wenisch.petri.agent.run.PackageInstallStatus;
import tech.wenisch.petri.agent.run.PackageInstallView;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

/**
 * Approves and runs package installs only when the project explicitly auto-approves them. Workflow
 * plan approval deliberately does not cover installs, so this is the only automatic path.
 */
@Service
class PackageInstallAutomationService {
  private static final Logger log = LoggerFactory.getLogger(PackageInstallAutomationService.class);

  private final AgentRunService runs;
  private final PackageInstallService installs;
  private final AgentChatQueueService queue;
  private final AppUserRepository users;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  PackageInstallAutomationService(
      AgentRunService runs, PackageInstallService installs, AgentChatQueueService queue,
      AppUserRepository users) {
    this.runs = runs;
    this.installs = installs;
    this.queue = queue;
    this.users = users;
  }

  // REQUIRES_NEW: an after-commit listener otherwise joins the finished transaction.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void requested(AgentActionRequestedEvent event) {
    if (event.kind() != AgentActionRequestedEvent.AgentActionKind.PACKAGE_INSTALL) return;
    AgentRunView run = runs.get(event.agentRunId());
    PackageInstallView request = installs.get(event.actionId());
    if (request.status() != PackageInstallStatus.PROPOSED || !installs.autoApprove(request.worktreeId())) {
      return;
    }
    AppUser creator = users.findById(run.createdBy()).orElse(null);
    if (creator == null) return;
    installs.approve(request.id(), creator.getEmail());
    UUID chatId = run.chatId();
    executor.submit(() -> {
      try {
        installs.execute(request.id());
      } catch (RuntimeException exception) {
        log.warn("Auto-approved package install {} failed", request.id(), exception);
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
