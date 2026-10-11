package tech.wenisch.petri.agent.orchestration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalStatus;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.agent.run.TestRunView;
import tech.wenisch.petri.git.WorkflowGitBaselineService;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Service
class WorkflowEvidenceService {
  private final AgentRunService runs;
  private final ChangeProposalService changes;
  private final TestRunService tests;
  private final WorkflowGitBaselineService baselines;
  private final WorktreeLocator worktrees;

  WorkflowEvidenceService(
      AgentRunService runs, ChangeProposalService changes, TestRunService tests,
      WorkflowGitBaselineService baselines, WorktreeLocator worktrees) {
    this.runs = runs; this.changes = changes; this.tests = tests; this.baselines = baselines;
    this.worktrees = worktrees;
  }

  Evidence collect(AgentWorkflow workflow) {
    List<AgentRunView> implementationRuns = runs.workflowRuns(workflow.id()).stream()
        .filter(run -> run.workflowRole() == AgentWorkflowRole.IMPLEMENTER).toList();
    List<ChangeProposalView> proposals = implementationRuns.stream()
        .flatMap(run -> changes.listForAgentRun(run.id()).stream()).toList();
    List<TestRunView> testRuns = implementationRuns.stream()
        .flatMap(run -> tests.listForAgentRun(run.id()).stream()).toList();
    Set<String> paths = new LinkedHashSet<>();
    proposals.stream().filter(value -> value.status() == ChangeProposalStatus.APPLIED)
        .map(ChangeProposalView::filePath).forEach(paths::add);
    String diff = baselines.diff(workflow.executionWorktreeId(), workflow.id(), List.copyOf(paths));
    boolean open = proposals.stream().anyMatch(value -> value.status() == ChangeProposalStatus.PROPOSED
        || value.status() == ChangeProposalStatus.APPROVED)
        || testRuns.stream().anyMatch(value -> value.status() == TestRunStatus.PROPOSED
            || value.status() == TestRunStatus.APPROVED || value.status() == TestRunStatus.RUNNING);
    boolean conflict = proposals.stream().anyMatch(value -> value.status() == ChangeProposalStatus.CONFLICT);
    Map<String, String> hashes = finalHashes(workflow.executionWorktreeId(), proposals);
    conflict = conflict || hashes.containsValue("EXTERNAL_CONFLICT");
    boolean testsFailed = testRuns.stream().anyMatch(value -> value.status() == TestRunStatus.FAILED
        || value.status() == TestRunStatus.ERROR || value.status() == TestRunStatus.TIMED_OUT);
    return new Evidence(List.copyOf(paths), diff, hashes, proposals, testRuns, open, conflict, testsFailed);
  }

  private Map<String, String> finalHashes(UUID worktreeId, List<ChangeProposalView> proposals) {
    Map<String, ChangeProposalView> latest = new LinkedHashMap<>();
    proposals.stream().filter(value -> value.status() == ChangeProposalStatus.APPLIED)
        .forEach(value -> latest.put(value.filePath(), value));
    Path root = worktrees.requireActiveWorktree(worktreeId);
    Map<String, String> hashes = new LinkedHashMap<>();
    for (ChangeProposalView proposal : latest.values()) {
      Path file = root.resolve(proposal.filePath()).normalize();
      try {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          hashes.put(proposal.filePath(), "EXTERNAL_CONFLICT");
        } else if (Files.size(file) > 10_000_000L) {
          hashes.put(proposal.filePath(), "EXTERNAL_CONFLICT");
        } else {
          String hash = HexFormat.of().formatHex(
              MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
          hashes.put(proposal.filePath(), hash.equals(proposal.replacementSha256())
              ? hash : "EXTERNAL_CONFLICT");
        }
      } catch (IOException | NoSuchAlgorithmException exception) {
        hashes.put(proposal.filePath(), "EXTERNAL_CONFLICT");
      }
    }
    return Map.copyOf(hashes);
  }

  record Evidence(
      List<String> touchedPaths,
      String diff,
      Map<String, String> finalHashes,
      List<ChangeProposalView> proposals,
      List<TestRunView> tests,
      boolean openActions,
      boolean conflict,
      boolean testsFailed) {}
}
