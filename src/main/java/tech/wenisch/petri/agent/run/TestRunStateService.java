package tech.wenisch.petri.agent.run;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.agent.orchestration.AgentActionRequestedEvent;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
class TestRunStateService {
  private final TestRunRepository testRuns;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  @Autowired
  TestRunStateService(
      TestRunRepository testRuns, AppUserRepository users, AuditLogService auditLog,
      ApplicationEventPublisher events) {
    this(testRuns, users, auditLog, Clock.systemUTC(), events);
  }

  TestRunStateService(
      TestRunRepository testRuns,
      AppUserRepository users,
      AuditLogService auditLog,
      Clock clock) {
    this(testRuns, users, auditLog, clock, null);
  }

  TestRunStateService(
      TestRunRepository testRuns,
      AppUserRepository users,
      AuditLogService auditLog,
      Clock clock,
      ApplicationEventPublisher events) {
    this.testRuns = testRuns;
    this.users = users;
    this.auditLog = auditLog;
    this.clock = clock;
    this.events = events;
  }

  @Transactional
  public TestRunView request(UUID worktreeId, UUID agentRunId, TestCommand command) {
    TestRun testRun = testRuns.save(new TestRun(
        UUID.randomUUID(), worktreeId, agentRunId, command.buildSystem(), command.display(),
        Instant.now(clock)));
    auditLog.record(
        "agent", "TEST_RUN_REQUESTED", "TEST_RUN", testRun.id(),
        "Requested approved execution of " + command.display());
    if (events != null && agentRunId != null) {
      events.publishEvent(new AgentActionRequestedEvent(
          agentRunId, testRun.id(), AgentActionRequestedEvent.AgentActionKind.TEST_RUN));
    }
    return view(testRun);
  }

  @Transactional(readOnly = true)
  public java.util.List<TestRunView> listForAgentRun(UUID agentRunId) {
    return testRuns.findByAgentRunIdOrderByRequestedAtAsc(agentRunId).stream()
        .map(TestRunStateService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public boolean hasActiveForWorktree(UUID worktreeId) {
    return testRuns.existsByWorktreeIdAndStatusIn(worktreeId, java.util.EnumSet.of(
        TestRunStatus.PROPOSED, TestRunStatus.APPROVED, TestRunStatus.RUNNING));
  }

  @Transactional(readOnly = true)
  public TestRunView get(UUID testRunId, UUID expectedWorktreeId) {
    TestRun testRun = testRun(testRunId);
    requireWorktree(testRun, expectedWorktreeId);
    return view(testRun);
  }

  @Transactional
  public TestRunView approve(UUID testRunId, String actorEmail) {
    TestRun testRun = locked(testRunId);
    AppUser actor = actor(actorEmail);
    testRun.approve(actor.getId(), Instant.now(clock));
    auditLog.record(
        actor.getEmail(), "TEST_RUN_APPROVED", "TEST_RUN", testRun.id(),
        "Approved " + testRun.commandDisplay());
    return view(testRun);
  }

  @Transactional
  public TestRunView reject(UUID testRunId, String actorEmail) {
    TestRun testRun = locked(testRunId);
    AppUser actor = actor(actorEmail);
    testRun.reject(actor.getId(), Instant.now(clock));
    auditLog.record(
        actor.getEmail(), "TEST_RUN_REJECTED", "TEST_RUN", testRun.id(),
        "Rejected " + testRun.commandDisplay());
    return view(testRun);
  }

  @Transactional
  public TestRunClaim claim(UUID testRunId) {
    TestRun testRun = locked(testRunId);
    testRun.start(Instant.now(clock));
    AppUser approver = users.findById(testRun.approvedBy())
        .orElseThrow(() -> new IllegalStateException("Approving user no longer exists"));
    auditLog.record(
        approver.getEmail(), "TEST_RUN_STARTED", "TEST_RUN", testRun.id(),
        "Started " + testRun.commandDisplay());
    return new TestRunClaim(
        testRun.id(), testRun.worktreeId(), testRun.buildSystem(), testRun.commandDisplay(),
        approver.getEmail());
  }

  @Transactional
  public TestRunView complete(UUID testRunId, TestCommandResult result, String actorEmail) {
    TestRun testRun = locked(testRunId);
    testRun.complete(result, Instant.now(clock));
    auditLog.record(
        actorEmail, "TEST_RUN_" + testRun.status(), "TEST_RUN", testRun.id(),
        "Completed " + testRun.commandDisplay() + " with status " + testRun.status());
    return view(testRun);
  }

  @Transactional
  public TestRunView error(UUID testRunId, String actorEmail) {
    TestRun testRun = locked(testRunId);
    testRun.error("Test execution could not be started.", Instant.now(clock));
    auditLog.record(
        actorEmail, "TEST_RUN_ERROR", "TEST_RUN", testRun.id(),
        "Could not execute " + testRun.commandDisplay());
    return view(testRun);
  }

  private TestRun testRun(UUID id) {
    return testRuns.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown test run"));
  }

  private TestRun locked(UUID id) {
    return testRuns.findForUpdateById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown test run"));
  }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static void requireWorktree(TestRun testRun, UUID expectedWorktreeId) {
    if (!testRun.worktreeId().equals(expectedWorktreeId)) {
      throw new IllegalArgumentException("Test run belongs to another worktree");
    }
  }

  private static TestRunView view(TestRun testRun) {
    return new TestRunView(
        testRun.id(), testRun.worktreeId(), testRun.agentRunId(), testRun.buildSystem(),
        testRun.commandDisplay(), testRun.status(), testRun.exitCode(), testRun.output(),
        testRun.outputTruncated(), testRun.requestedAt(), testRun.approvedAt(),
        testRun.startedAt(), testRun.completedAt());
  }
}
