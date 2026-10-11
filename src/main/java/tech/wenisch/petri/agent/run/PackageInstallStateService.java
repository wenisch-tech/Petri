package tech.wenisch.petri.agent.run;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.orchestration.AgentActionRequestedEvent;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
class PackageInstallStateService {
  private final PackageInstallRepository requests;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final ApplicationEventPublisher events;
  private final Clock clock = Clock.systemUTC();

  PackageInstallStateService(
      PackageInstallRepository requests, AppUserRepository users, AuditLogService auditLog,
      ApplicationEventPublisher events) {
    this.requests = requests;
    this.users = users;
    this.auditLog = auditLog;
    this.events = events;
  }

  @Transactional
  PackageInstallView request(UUID worktreeId, UUID agentRunId, List<String> packages, String reason) {
    // A model that sees a pending request may ask again; return the open one instead of piling up.
    Set<String> wanted = new LinkedHashSet<>(packages);
    for (PackageInstallRequest open : requests.findByWorktreeIdAndStatusIn(worktreeId, EnumSet.of(
        PackageInstallStatus.PROPOSED, PackageInstallStatus.APPROVED, PackageInstallStatus.RUNNING))) {
      if (new LinkedHashSet<>(open.packageNames()).equals(wanted)) return view(open);
    }
    PackageInstallRequest request = requests.save(new PackageInstallRequest(
        UUID.randomUUID(), worktreeId, agentRunId, packages, reason, Instant.now(clock)));
    auditLog.record("agent", "PACKAGE_INSTALL_REQUESTED", "PACKAGE_INSTALL", request.id(),
        "Requested installation of " + String.join(", ", packages));
    if (agentRunId != null) {
      events.publishEvent(new AgentActionRequestedEvent(
          agentRunId, request.id(), AgentActionRequestedEvent.AgentActionKind.PACKAGE_INSTALL));
    }
    return view(request);
  }

  @Transactional(readOnly = true)
  PackageInstallView get(UUID id) { return view(find(id)); }

  @Transactional(readOnly = true)
  List<PackageInstallView> listForAgentRun(UUID agentRunId) {
    return requests.findByAgentRunIdOrderByRequestedAtAsc(agentRunId).stream()
        .map(PackageInstallStateService::view).toList();
  }

  @Transactional(readOnly = true)
  boolean hasOpenForAgentRun(UUID agentRunId) {
    return requests.existsByAgentRunIdAndStatusIn(agentRunId, EnumSet.of(
        PackageInstallStatus.PROPOSED, PackageInstallStatus.APPROVED, PackageInstallStatus.RUNNING));
  }

  @Transactional(readOnly = true)
  Set<String> installedPackages() {
    Set<String> names = new LinkedHashSet<>();
    requests.findByStatus(PackageInstallStatus.INSTALLED).stream()
        .sorted(java.util.Comparator.comparing(PackageInstallRequest::requestedAt))
        .forEach(value -> names.addAll(value.packageNames()));
    return names;
  }

  @Transactional
  PackageInstallView approve(UUID id, String actorEmail) {
    PackageInstallRequest request = locked(id);
    AppUser actor = actor(actorEmail);
    request.approve(actor.getId(), Instant.now(clock));
    auditLog.record(actor.getEmail(), "PACKAGE_INSTALL_APPROVED", "PACKAGE_INSTALL", id,
        "Approved installation of " + String.join(", ", request.packageNames()));
    return view(request);
  }

  @Transactional
  PackageInstallView reject(UUID id, String actorEmail) {
    PackageInstallRequest request = locked(id);
    AppUser actor = actor(actorEmail);
    request.reject(actor.getId(), Instant.now(clock));
    auditLog.record(actor.getEmail(), "PACKAGE_INSTALL_REJECTED", "PACKAGE_INSTALL", id,
        "Rejected installation of " + String.join(", ", request.packageNames()));
    return view(request);
  }

  @Transactional
  PackageInstallView start(UUID id) {
    PackageInstallRequest request = locked(id);
    request.start(Instant.now(clock));
    auditLog.record("petri", "PACKAGE_INSTALL_STARTED", "PACKAGE_INSTALL", id,
        "Installing " + String.join(", ", request.packageNames()));
    return view(request);
  }

  @Transactional
  void progress(UUID id, String text) {
    requests.findForUpdateById(id).ifPresent(value -> value.progress(text));
  }

  @Transactional
  PackageInstallView complete(UUID id, TestCommandResult result) {
    PackageInstallRequest request = locked(id);
    request.complete(result.exitCode(), result.output(), result.truncated(), result.timedOut(),
        Instant.now(clock));
    auditLog.record("petri", "PACKAGE_INSTALL_" + request.status(), "PACKAGE_INSTALL", id,
        "Package installation finished with status " + request.status());
    return view(request);
  }

  @Transactional
  PackageInstallView error(UUID id, String safeMessage) {
    PackageInstallRequest request = locked(id);
    request.error(safeMessage, Instant.now(clock));
    auditLog.record("petri", "PACKAGE_INSTALL_FAILED", "PACKAGE_INSTALL", id,
        "Package installation could not run");
    return view(request);
  }

  private PackageInstallRequest find(UUID id) {
    return requests.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown package install"));
  }

  private PackageInstallRequest locked(UUID id) {
    return requests.findForUpdateById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown package install"));
  }

  private AppUser actor(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static PackageInstallView view(PackageInstallRequest value) {
    return new PackageInstallView(value.id(), value.worktreeId(), value.agentRunId(),
        List.copyOf(value.packageNames()), value.reason(), value.status(), value.exitCode(),
        value.output(), value.outputTruncated(), value.requestedAt(), value.approvedAt(),
        value.startedAt(), value.completedAt());
  }
}
