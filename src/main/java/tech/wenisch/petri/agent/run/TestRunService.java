package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Service
public class TestRunService {
  private static final Logger log = LoggerFactory.getLogger(TestRunService.class);

  private final WorktreeLocator worktrees;
  private final TestCommandPolicy policy;
  private final TestCommandRunner runner;
  private final TestRunStateService state;

  TestRunService(
      WorktreeLocator worktrees,
      TestCommandPolicy policy,
      TestCommandRunner runner,
      TestRunStateService state) {
    this.worktrees = worktrees;
    this.policy = policy;
    this.runner = runner;
    this.state = state;
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

  public java.util.List<TestRunView> listForAgentRun(UUID agentRunId) {
    return state.listForAgentRun(agentRunId);
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
      result = runner.run(worktree, command);
    } catch (IOException | RuntimeException exception) {
      log.warn("Approved test run {} could not be executed", testRunId, exception);
      return state.error(testRunId, claim.approverEmail());
    }
    return state.complete(testRunId, result, claim.approverEmail());
  }
}
