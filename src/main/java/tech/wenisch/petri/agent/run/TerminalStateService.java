package tech.wenisch.petri.agent.run;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.orchestration.AgentActionRequestedEvent;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
class TerminalStateService {
  private final TerminalCommandRepository commands;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final ApplicationEventPublisher events;
  private final Clock clock = Clock.systemUTC();

  TerminalStateService(TerminalCommandRepository commands, AppUserRepository users,
      AuditLogService auditLog, ApplicationEventPublisher events) {
    this.commands = commands;
    this.users = users;
    this.auditLog = auditLog;
    this.events = events;
  }

  /** An agent-requested command: a proposal that waits for approval. */
  @Transactional
  TerminalCommandView requestForAgent(UUID worktreeId, UUID agentRunId, String command, String reason) {
    for (TerminalCommandRequest open : commands.findByWorktreeIdAndStatusIn(worktreeId, EnumSet.of(
        TerminalCommandStatus.PROPOSED, TerminalCommandStatus.APPROVED, TerminalCommandStatus.RUNNING))) {
      if (open.command().equals(command) && "AGENT".equals(open.source())) return view(open);
    }
    TerminalCommandRequest request = commands.save(new TerminalCommandRequest(UUID.randomUUID(),
        worktreeId, agentRunId, "AGENT", command, reason, null, Instant.now(clock)));
    auditLog.record("agent", "TERMINAL_COMMAND_REQUESTED", "TERMINAL_COMMAND", request.id(),
        "Requested terminal command");
    if (agentRunId != null) {
      events.publishEvent(new AgentActionRequestedEvent(agentRunId, request.id(),
          AgentActionRequestedEvent.AgentActionKind.TERMINAL_COMMAND));
    }
    return view(request);
  }

  /** A command typed by the user in the terminal panel: approved by definition. */
  @Transactional
  TerminalCommandView createForUser(UUID worktreeId, String actorEmail, String command) {
    AppUser actor = actor(actorEmail);
    TerminalCommandRequest request = new TerminalCommandRequest(UUID.randomUUID(), worktreeId, null,
        "USER", command, null, actor.getId(), Instant.now(clock));
    request.approve(actor.getId(), Instant.now(clock));
    commands.save(request);
    auditLog.record(actor.getEmail(), "TERMINAL_COMMAND_USER", "TERMINAL_COMMAND", request.id(),
        "User ran a terminal command");
    return view(request);
  }

  @Transactional(readOnly = true)
  TerminalCommandView get(UUID id) { return view(find(id)); }

  @Transactional(readOnly = true)
  List<TerminalCommandView> listForAgentRun(UUID agentRunId) {
    return commands.findByAgentRunIdOrderByRequestedAtAsc(agentRunId).stream()
        .map(TerminalStateService::view).toList();
  }

  @Transactional
  TerminalCommandView approve(UUID id, String actorEmail) {
    TerminalCommandRequest request = locked(id);
    AppUser actor = actor(actorEmail);
    request.approve(actor.getId(), Instant.now(clock));
    auditLog.record(actor.getEmail(), "TERMINAL_COMMAND_APPROVED", "TERMINAL_COMMAND", id,
        "Approved terminal command");
    return view(request);
  }

  @Transactional
  TerminalCommandView reject(UUID id, String actorEmail) {
    TerminalCommandRequest request = locked(id);
    AppUser actor = actor(actorEmail);
    request.reject(actor.getId(), Instant.now(clock));
    auditLog.record(actor.getEmail(), "TERMINAL_COMMAND_REJECTED", "TERMINAL_COMMAND", id,
        "Rejected terminal command");
    return view(request);
  }

  @Transactional
  TerminalCommandView start(UUID id) {
    TerminalCommandRequest request = locked(id);
    request.start(Instant.now(clock));
    return view(request);
  }

  @Transactional
  void progress(UUID id, String text) {
    commands.findForUpdateById(id).ifPresent(value -> value.progress(text));
  }

  @Transactional
  TerminalCommandView complete(UUID id, TestCommandResult result) {
    TerminalCommandRequest request = locked(id);
    request.complete(result.exitCode(), result.output(), result.truncated(), result.timedOut(),
        Instant.now(clock));
    auditLog.record("petri", "TERMINAL_COMMAND_" + request.status(), "TERMINAL_COMMAND", id,
        "Terminal command finished with status " + request.status());
    return view(request);
  }

  @Transactional
  TerminalCommandView error(UUID id, String safeMessage) {
    TerminalCommandRequest request = locked(id);
    request.error(safeMessage, Instant.now(clock));
    auditLog.record("petri", "TERMINAL_COMMAND_ERROR", "TERMINAL_COMMAND", id,
        "Terminal command could not run");
    return view(request);
  }

  private TerminalCommandRequest find(UUID id) {
    return commands.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown terminal command"));
  }

  private TerminalCommandRequest locked(UUID id) {
    return commands.findForUpdateById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown terminal command"));
  }

  private AppUser actor(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  static TerminalCommandView view(TerminalCommandRequest value) {
    return new TerminalCommandView(value.id(), value.worktreeId(), value.agentRunId(), value.source(),
        value.command(), value.reason(), value.status(), value.exitCode(), value.output(),
        value.outputTruncated(), value.requestedAt(), value.approvedAt(), value.startedAt(),
        value.completedAt());
  }
}
