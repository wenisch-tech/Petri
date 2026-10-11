package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorktreeLocator;
import tech.wenisch.petri.git.ProjectWorktreeService;

@Service
public class TestRunService {
  private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

  private final WorktreeLocator worktrees;
  private final TestCommandPolicy policy;
  private final TestExecutor runner;
  private final TestRunStateService state;
  private final ProjectWorktreeService projectWorktrees;
  private final TestSandboxProfileService sandboxProfiles;

  TestRunService(
      WorktreeLocator worktrees,
      TestCommandPolicy policy,
      TestExecutor runner,
      TestRunStateService state,
      ProjectWorktreeService projectWorktrees,
      TestSandboxProfileService sandboxProfiles) {
    this.worktrees = worktrees;
    this.policy = policy;
    this.runner = runner;
    this.state = state;
    this.projectWorktrees = projectWorktrees;
    this.sandboxProfiles = sandboxProfiles;
  }

  public TestRunView request(UUID worktreeId) {
    return request(worktreeId, null);
  }

  public TestRunView request(UUID worktreeId, UUID agentRunId) {
    Path worktree = worktrees.requireActiveWorktree(worktreeId);
    return state.request(worktreeId, agentRunId, policy.detect(worktree));
  }

  public TestRunView get(UUID testRunId, UUID expectedWorktreeId) {
    return state.get(testRunId, expectedWorktreeId);
  }

  public TestRunView get(UUID testRunId) {
    return state.get(testRunId);
  }

  public java.util.List<TestRunView> listForAgentRun(UUID agentRunId) {
    return state.listForAgentRun(agentRunId);
  }

  public boolean hasOpenForAgentRun(UUID agentRunId) {
    return state.listForAgentRun(agentRunId).stream().anyMatch(value ->
        value.status() == TestRunStatus.PROPOSED || value.status() == TestRunStatus.APPROVED
            || value.status() == TestRunStatus.RUNNING);
  }

  public boolean hasActiveForWorktree(UUID worktreeId) {
    return state.hasActiveForWorktree(worktreeId);
  }

  public TestRunView approve(UUID testRunId, String actorEmail) {
    return state.approve(testRunId, actorEmail);
  }

  public TestRunView reject(UUID testRunId, String actorEmail) {
    return state.reject(testRunId, actorEmail);
  }

  public TestRunView execute(UUID testRunId) {
    TestRunClaim claim = state.claim(testRunId);
    TestCommandResult result;
    try {
      Path worktree = worktrees.requireActiveWorktree(claim.worktreeId());
      TestCommand command = policy.command(worktree, claim.buildSystem());
      if (!command.display().equals(claim.commandDisplay())) {
        throw new IllegalStateException("Approved test command no longer matches policy");
      }
      UUID projectId = projectWorktrees.get(claim.worktreeId()).projectId();
      TestSandboxProfileView profile = sandboxProfiles.resolve(projectId, claim.buildSystem());
      state.snapshot(testRunId, profile);
      result = runner.run(projectId, worktree, command, profile,
          text -> state.progress(testRunId, text));
    } catch (IOException | RuntimeException exception) {
      log.warn("Approved test run {} could not be executed", testRunId, exception);
      return state.error(testRunId, claim.approverEmail(), safeFailure(exception));
    }
    return state.complete(testRunId, result, claim.approverEmail());
  }

  private static String safeFailure(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) return "Test execution could not be started.";
    message = message.replaceAll("[\\r\\n]+", " ").strip();
    return message.substring(0, Math.min(1000, message.length()));
  }
}
